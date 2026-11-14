/*
 * hakuX HLE GPU — the window's presenter (swapchain.h): the front frame
 * (display.h) drawn into the app's window through a Vulkan swapchain, once
 * per display refresh, on the window's thread (native/app/present.c).
 *
 * Each refresh: a swapchain image (waiting for one paces the thread to the
 * panel: FIFO), the front frame, and one pass that letterboxes the frame
 * into the image, the rest black, through the display's gamma ramp. The
 * anti-aliasing on the way:
 *  - FXAA over each new frame at its own size, into an image of ours, once a
 *    frame however many refreshes show it;
 *  - a frame larger than its place in the window (a render scale above the
 *    screen's) is averaged down, each window pixel the mean of the frame
 *    pixels it covers, where a bilinear sample would skip most of them and
 *    keep the edges' stairs;
 *  - a smaller one is scaled up bilinearly, or by Snapdragon Game Super
 *    Resolution 1, which rebuilds its edges.
 * FXAA is FXAA 3.11 Quality (Timothy Lottes) as PCSX2's fxaa.fx tunes it
 * (GPL-3.0-or-later); SGSR is Qualcomm's sgsr1_shader_mobile.frag
 * (BSD-3-Clause, licenses/snapdragon-gsr.license.txt).
 *
 * The queue is the render thread's too (hle_vk_queue_lock): queue order
 * puts our reads of a frame after its writes, and the render thread's next
 * write of the slot after our reads (its barriers). The device table is the
 * presenter's own: the render thread's wraps its commands for
 * strip batching (batch.c). The surface follows the window: a new
 * ANativeWindow (the activity's surface made again) gets a new surface and
 * swapchain, none drops them.
 */
#include "hw/xbox/hle/base.h"
#include "hw/xbox/hle/hle.h"
#include "hw/xbox/hle/shader/glsl_compile.h"
#include "hw/xbox/hle/vk/back_int.h"
#include "hw/xbox/hle/vk/compat.h"
#include "hw/xbox/hle/vk/display.h"
#include "hw/xbox/hle/vk/quirks.h"
#include "hw/xbox/hle/vk/swapchain.h"
#include <math.h>
#include "hw/xbox/hle/vk/nis_coefficients.h"
#include "hw/xbox/hle/vk/lsfg_native.h"
#include "nis_shader_sources.h"

#include <string.h>
#include <stdlib.h>

#include <android/native_window.h>

#define T (g_ps.t)
#define PS_FRAMES 3             /* the presenter's submits in flight */
#define PS_RAMP 1024            /* a frame's share of the ramp buffer (768 used) */

/* FSR 1.0 on, replacing the SGSR scaling path (0: SGSR as before) */
#ifndef HAKUX_FSR
#define HAKUX_FSR 1
#endif

/* P_RCAS draws into the window (its format), P_EASU into an RGBA8 image */
enum { P_BILINEAR, P_AREA, P_SGSR, P_RCAS, P_FXAA, P_EASU, P_COUNT };

typedef struct LsfgAlphaImage {
    VkImage image;
    VkDeviceMemory memory;
    VkImageView view;
    VkImageLayout layout;
} LsfgAlphaImage;

typedef struct LsfgAlphaLevel {
    LsfgAlphaImage temp1, temp2;
    LsfgAlphaImage temp3[2];
    LsfgAlphaImage outputs[3][2];
} LsfgAlphaLevel;

typedef struct LsfgGammaLevel {
    LsfgAlphaImage temp1[3], temp2[2], output;
} LsfgGammaLevel;

typedef struct LsfgDeltaLevel {
    LsfgAlphaImage temp1[3], temp2[2], output1, output2;
} LsfgDeltaLevel;

typedef struct PsFrame {
    VkCommandPool pool;
    VkCommandBuffer cb;
    VkSemaphore acquired;       /* its swapchain image is ready */
    VkDescriptorSet set[3];     /* the window pass's, FXAA's */
    VkDescriptorSet nis_set;
    uint64_t value;             /* its last submit's on the timeline */
    bool ramp_stale;            /* its share of the ramp buffer is behind */
} PsFrame;

static struct {
    struct VolkDeviceTable t;
    bool inited, failed;
    VkShaderModule lsfg_modules[25];
    VkDescriptorSetLayout lsfg_set_layouts[25];
    VkPipelineLayout lsfg_pipeline_layouts[25];
    VkPipeline lsfg_pipelines[25];
    VkDescriptorPool lsfg_descriptor_pool;
    VkDescriptorSet lsfg_descriptor_sets[25];
    VkDescriptorSet lsfg_mipmap_sets[6];
    VkImage lsfg_history[6];
    VkDeviceMemory lsfg_history_mem[6];
    VkImageView lsfg_history_view[6];
    VkImageLayout lsfg_history_layout[6];
    uint32_t lsfg_history_w, lsfg_history_h;
    /* Seven R8 flow pyramid levels consumed by the LSFG mipmap pass. */
    VkImage lsfg_flow[7];
    VkDeviceMemory lsfg_flow_mem[7];
    VkImageView lsfg_flow_view[7];
    VkImageLayout lsfg_flow_layout[7];
    LsfgAlphaLevel lsfg_alpha[7];
    VkDescriptorSet lsfg_alpha_sets[7][6];
    LsfgAlphaImage lsfg_beta_temp1[2], lsfg_beta_temp2[2], lsfg_beta_outputs[6];
    VkDescriptorSet lsfg_beta_sets[7];
    VkDescriptorSet lsfg_gamma_sets[7][6][7];
    LsfgGammaLevel lsfg_gamma[7];
    VkDescriptorSet lsfg_delta_sets[3][6][14];
    LsfgDeltaLevel lsfg_delta[3];
    VkBuffer lsfg_delta_constants_buffer[3][6];
    VkDeviceMemory lsfg_delta_constants_memory[3][6];
    uint8_t *lsfg_delta_constants_map[3][6];
    uint32_t lsfg_delta_flow_w, lsfg_delta_flow_h;
    bool lsfg_delta_sets_allocated, lsfg_delta_ready;
    VkBuffer lsfg_gamma_constants_buffer[6][2];
    VkDeviceMemory lsfg_gamma_constants_memory[6][2];
    uint8_t *lsfg_gamma_constants_map[6][2];
    uint32_t lsfg_gamma_flow_w, lsfg_gamma_flow_h;
    bool lsfg_gamma_sets_allocated, lsfg_gamma_ready;
    VkSampler lsfg_beta_sampler;
    VkSampler lsfg_gamma_border_sampler;
    VkBuffer lsfg_beta_constants_buffer;
    VkDeviceMemory lsfg_beta_constants_memory;
    uint8_t *lsfg_beta_constants_map;
    uint32_t lsfg_alpha_w, lsfg_alpha_h;
    bool lsfg_alpha_ready, lsfg_alpha_sets_allocated, lsfg_beta_ready;

    uint32_t lsfg_flow_w, lsfg_flow_h;
    int lsfg_flow_scale_allocated;
    bool lsfg_flow_ready, lsfg_flow_failed;
    VkBuffer lsfg_constants_buffer;
    VkDeviceMemory lsfg_constants_memory;
    uint8_t *lsfg_constants_map;
    VkSampler lsfg_sampler;
    bool lsfg_mipmap_descriptors_ready, lsfg_mipmap_sets_ready;
    int lsfg_constants_flow_scale;
    uint64_t lsfg_history_last_serial;
    uint32_t lsfg_history_count;
    bool lsfg_history_ready, lsfg_history_failed;
    bool lsfg_modules_ready, lsfg_modules_failed, lsfg_pipelines_ready, lsfg_pipelines_failed;
    VkSemaphore tl;             /* timeline: each submit's value */
    uint64_t submitted;
    VkSampler smp;
    VkDescriptorSetLayout dsl;
    VkPipelineLayout pl;
    VkDescriptorPool dp;
    VkBuffer ubo;
    VkDeviceMemory ubo_mem;
    uint8_t *ubo_map;
    VkShaderModule vs, fs[P_COUNT];
    VkShaderModule nis_cs;           /* optional NIS compute shader; presenter remains usable without it */
    VkDescriptorSetLayout nis_dsl;
    VkPipelineLayout nis_pl;
    VkPipeline nis_pipe;
    VkBuffer nis_config;
    VkDeviceMemory nis_config_mem;
    uint8_t *nis_config_map;
    VkDeviceSize nis_config_stride;
    VkSampler nis_sampler;
    VkImage nis_coef_scale, nis_coef_usm, nis_out;
    VkDeviceMemory nis_coef_scale_mem, nis_coef_usm_mem, nis_out_mem;
    VkImageView nis_coef_scale_view, nis_coef_usm_view, nis_out_view;
    uint32_t nis_out_w, nis_out_h;
    VkImageLayout nis_out_layout;
    bool nis_resources_ready;
    VkPipeline pipe[P_COUNT];
    VkFormat pipe_fmt;          /* the window pipelines' */
    PsFrame fr[PS_FRAMES];
    unsigned cur;
    /* the window */
    struct ANativeWindow *win;
    int64_t lost_ns;            /* its surface was lost then: tried again a while after */
    VkSurfaceKHR surf;
    VkSwapchainKHR sc;
    VkFormat fmt;
    VkExtent2D ext, wsize;      /* the images' (the panel's orientation), the window's */
    float rot[4];               /* the window's clip space into the images' (k_vs) */
    uint32_t nimg, idx;
    VkImage *img;               /* nimg each */
    VkImageView *view;
    VkSemaphore *drawn;         /* its pass done: what its present waits for */
    bool stale;                 /* out of date: made again at the next begin */
    bool follows;               /* its transform is the panel's: SUBOPTIMAL means it turned */
    /* the frame shown */
    HleDispFrame f;
    bool have, using;           /* f held; inside hle_disp_use_begin */
    uint64_t serial;            /* frames taken */
    /* FXAA's */
    VkImage fx_img;
    VkDeviceMemory fx_mem;
    VkImageView fx_view;
    uint32_t fx_w, fx_h;
    uint64_t fx_serial;         /* the frame it holds */
    /* FSR's EASU image, at the frame's place in the window */
    VkImage fs_img;
    VkDeviceMemory fs_mem;
    VkImageView fs_view;
    uint32_t fs_w, fs_h;
    uint64_t fs_serial;         /* the frame it holds */
    bool fs_fxaa;               /* ... made from FXAA's image */
    /* the display's gamma ramp */
    uint8_t ramp[3][256];
    uint32_t ramp_gen;
    /* the last present went through, of f (have), the ramp and the caller's
     * key (hle_present_unchanged) */
    bool shown;
    uint64_t shown_key;
    /* User settings consumed by the native LSFG resource chain. */
    int lsfg_multiplier, lsfg_flow_scale;
    bool lsfg_performance;
} g_ps;

void hle_present_set_lsfg(int multiplier, bool performance, int flow_scale)
{
    g_ps.lsfg_multiplier = multiplier < 2 ? 2 : multiplier > 4 ? 4 : multiplier;
    g_ps.lsfg_performance = performance;
    g_ps.lsfg_flow_scale = flow_scale < 25 ? 25 : flow_scale > 100 ? 100 : flow_scale;
}

/* Used by temporal capture before the presenter's helper implementation. */
static void barrier(VkCommandBuffer cb, VkImage img, VkImageLayout from, VkImageLayout to,
                    VkPipelineStageFlags2 src, VkAccessFlags2 src_access, VkPipelineStageFlags2 dst,
                    VkAccessFlags2 dst_access);

/* Read by the Android debug overlay; each field is independently atomic. */
/* Create the user's validated SPIR-V modules on this Vulkan device. This is
 * deliberately separate from DLL parsing: the PE is never executed, and
 * every module is owned by the presenter device for its lifetime. */
typedef struct LsfgPassSpec {
    uint32_t id;
    uint8_t uniform_buffers, samplers, sampled_images, storage_images;
} LsfgPassSpec;

/* Descriptor order and counts mirror EmuCoreX's LsfgPass declarations.
 * Binding numbers are dense because lsfg_shader.cpp adopts/renumbers SPIR-V. */
static const LsfgPassSpec k_lsfg_passes[25] = {
    {255, 1, 1, 1, 7},  /* mipmap pyramid */
    {256, 1, 2, 5, 1},  /* generated output */
    {280, 1, 2, 5, 3},  /* gamma[0] / delta[0] */
    {281, 1, 2, 6, 1},  /* delta[5] */
    {282, 0, 1, 3, 2},  /* gamma[1] */
    {283, 0, 1, 2, 2},  /* gamma[2] */
    {284, 0, 1, 2, 2},  /* gamma[3] */
    {285, 1, 2, 4, 1},  /* gamma[4] */
    {286, 0, 1, 3, 2},  /* delta[1] */
    {287, 0, 1, 2, 2},  /* delta[2] */
    {288, 0, 1, 2, 2},  /* delta[3] */
    {289, 1, 2, 4, 1},  /* delta[4] */
    {290, 0, 1, 1, 1},  /* alpha[0] */
    {291, 0, 1, 1, 1},  /* alpha[1] */
    {292, 0, 1, 1, 2},  /* alpha[2] */
    {293, 0, 1, 2, 2},  /* alpha[3] */
    {294, 0, 1, 1, 1},  /* delta[6] */
    {295, 0, 1, 1, 1},  /* delta[7] */
    {296, 0, 1, 1, 1},  /* delta[8] */
    {297, 1, 2, 2, 1},  /* delta[9] */
    {298, 0, 1, 6, 2},  /* beta[0] */
    {299, 0, 1, 2, 2},  /* beta[1] */
    {300, 0, 1, 2, 2},  /* beta[2] */
    {301, 0, 1, 2, 2},  /* beta[3] */
    {302, 1, 1, 2, 6},  /* beta[4] */
};

static void lsfg_pipeline_destroy(void)
{
    if (g_ps.lsfg_descriptor_pool) {
        T.vkDestroyDescriptorPool(g_hvk.dev, g_ps.lsfg_descriptor_pool, NULL);
        g_ps.lsfg_descriptor_pool = VK_NULL_HANDLE;
        memset(g_ps.lsfg_descriptor_sets, 0, sizeof(g_ps.lsfg_descriptor_sets));
        memset(g_ps.lsfg_mipmap_sets, 0, sizeof(g_ps.lsfg_mipmap_sets));
        memset(g_ps.lsfg_alpha_sets, 0, sizeof(g_ps.lsfg_alpha_sets));
        memset(g_ps.lsfg_beta_sets, 0, sizeof(g_ps.lsfg_beta_sets));
        memset(g_ps.lsfg_gamma_sets, 0, sizeof(g_ps.lsfg_gamma_sets));
        memset(g_ps.lsfg_delta_sets, 0, sizeof(g_ps.lsfg_delta_sets));
        g_ps.lsfg_delta_sets_allocated = false;
        g_ps.lsfg_alpha_sets_allocated = false;
        g_ps.lsfg_gamma_sets_allocated = false;

    }
    for (unsigned i = 0; i < ARRAY_SIZE(k_lsfg_passes); ++i) {
        if (g_ps.lsfg_pipelines[i]) {
            T.vkDestroyPipeline(g_hvk.dev, g_ps.lsfg_pipelines[i], NULL);
            g_ps.lsfg_pipelines[i] = VK_NULL_HANDLE;
        }
        if (g_ps.lsfg_pipeline_layouts[i]) {
            T.vkDestroyPipelineLayout(g_hvk.dev, g_ps.lsfg_pipeline_layouts[i], NULL);
            g_ps.lsfg_pipeline_layouts[i] = VK_NULL_HANDLE;
        }
        if (g_ps.lsfg_set_layouts[i]) {
            T.vkDestroyDescriptorSetLayout(g_hvk.dev, g_ps.lsfg_set_layouts[i], NULL);
            g_ps.lsfg_set_layouts[i] = VK_NULL_HANDLE;
        }
    }
    g_ps.lsfg_pipelines_ready = false;
}

static bool lsfg_pipelines_init(void)
{
    if (g_ps.lsfg_pipelines_ready) return true;
    if (g_ps.lsfg_pipelines_failed) return false;
    if (!g_ps.lsfg_modules_ready || !g_hvk.caps.lsfg_memory_model ||
        !g_hvk.caps.lsfg_null_descriptor) return false;

    for (unsigned i = 0; i < ARRAY_SIZE(k_lsfg_passes); ++i) {
        const LsfgPassSpec *spec = &k_lsfg_passes[i];
        VkDescriptorSetLayoutBinding bindings[16];
        uint32_t n = 0;
        const uint32_t counts[4] = {
            spec->uniform_buffers, spec->samplers,
            spec->sampled_images, spec->storage_images
        };
        const VkDescriptorType types[4] = {
            VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, VK_DESCRIPTOR_TYPE_SAMPLER,
            VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
        };
        for (unsigned t = 0; t < ARRAY_SIZE(counts); ++t) {
            for (uint32_t j = 0; j < counts[t]; ++j) {
                if (n >= ARRAY_SIZE(bindings)) goto fail;
                bindings[n] = (VkDescriptorSetLayoutBinding) {
                    .binding = n,
                    .descriptorType = types[t],
                    .descriptorCount = 1,
                    .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT
                };
                ++n;
            }
        }
        VkDescriptorSetLayoutCreateInfo dci = {
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
            .bindingCount = n,
            .pBindings = bindings
        };
        VkResult r = T.vkCreateDescriptorSetLayout(g_hvk.dev, &dci, NULL,
                                                    &g_ps.lsfg_set_layouts[i]);
        if (r != VK_SUCCESS) {
            hle_log("hakuX-lsfg", "descriptor layout creation failed for shader %u: %d", spec->id, r);
            goto fail;
        }
        VkPipelineLayoutCreateInfo lci = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
            .setLayoutCount = 1,
            .pSetLayouts = &g_ps.lsfg_set_layouts[i]
        };
        r = T.vkCreatePipelineLayout(g_hvk.dev, &lci, NULL, &g_ps.lsfg_pipeline_layouts[i]);
        if (r != VK_SUCCESS) {
            hle_log("hakuX-lsfg", "pipeline layout creation failed for shader %u: %d", spec->id, r);
            goto fail;
        }
        VkComputePipelineCreateInfo pci = {
            .sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO,
            .stage = {
                .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
                .stage = VK_SHADER_STAGE_COMPUTE_BIT,
                .module = g_ps.lsfg_modules[i],
                .pName = "main"
            },
            .layout = g_ps.lsfg_pipeline_layouts[i]
        };
        r = T.vkCreateComputePipelines(g_hvk.dev, VK_NULL_HANDLE, 1, &pci, NULL,
                                       &g_ps.lsfg_pipelines[i]);
        if (r != VK_SUCCESS) {
            hle_log("hakuX-lsfg", "compute pipeline creation failed for shader %u: %d", spec->id, r);
            goto fail;
        }
    }
    uint32_t descriptor_totals[4] = { 0, 0, 0, 0 };
    for (unsigned i = 0; i < ARRAY_SIZE(k_lsfg_passes); ++i) {
        descriptor_totals[0] += k_lsfg_passes[i].uniform_buffers;
        descriptor_totals[1] += k_lsfg_passes[i].samplers;
        descriptor_totals[2] += k_lsfg_passes[i].sampled_images;
        descriptor_totals[3] += k_lsfg_passes[i].storage_images;
    }
    /* Six persistent mipmap sets map one-to-one to the six history images. */
    for (unsigned i = 1; i < ARRAY_SIZE(g_ps.lsfg_mipmap_sets); ++i) {
        descriptor_totals[0] += k_lsfg_passes[0].uniform_buffers;
        descriptor_totals[1] += k_lsfg_passes[0].samplers;
        descriptor_totals[2] += k_lsfg_passes[0].sampled_images;
        descriptor_totals[3] += k_lsfg_passes[0].storage_images;
    }
    /* Alpha: seven levels, three early-stage and three temporal sets. */
    descriptor_totals[1] += 7u * 6u;
    descriptor_totals[2] += 7u * 9u;
    descriptor_totals[3] += 7u * 10u;
    /* Beta: three history-indexed first-pass sets plus four later passes. */
    descriptor_totals[0] += 1u;
    descriptor_totals[1] += 7u;
    descriptor_totals[2] += 26u;
    descriptor_totals[3] += 18u;
    /* Gamma: seven pyramid levels, six generation slots, seven sets per slot. */
    descriptor_totals[0] += 7u * 6u * 4u;
    descriptor_totals[1] += 7u * 6u * 11u;
    descriptor_totals[2] += 7u * 6u * 26u;
    descriptor_totals[3] += 7u * 6u * 16u;
    /* Delta: three levels, six generation slots, fourteen sets per slot. */
    descriptor_totals[0] += 3u * 6u * 8u;
    descriptor_totals[1] += 3u * 6u * 22u;
    descriptor_totals[2] += 3u * 6u * 49u;
    descriptor_totals[3] += 3u * 6u * 23u;
    const VkDescriptorType descriptor_types[4] = {
        VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, VK_DESCRIPTOR_TYPE_SAMPLER,
        VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
    };
    VkDescriptorPoolSize pool_sizes[4];
    for (unsigned i = 0; i < ARRAY_SIZE(pool_sizes); ++i) {
        pool_sizes[i] = (VkDescriptorPoolSize) {
            .type = descriptor_types[i], .descriptorCount = descriptor_totals[i]
        };
    }
    VkDescriptorPoolCreateInfo dpci = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
        .maxSets = ARRAY_SIZE(k_lsfg_passes) + ARRAY_SIZE(g_ps.lsfg_mipmap_sets) - 1 + 42 + 7 + 294 + 252,
        .poolSizeCount = ARRAY_SIZE(pool_sizes),
        .pPoolSizes = pool_sizes
    };
    VkResult r = T.vkCreateDescriptorPool(g_hvk.dev, &dpci, NULL,
                                           &g_ps.lsfg_descriptor_pool);
    if (r != VK_SUCCESS) {
        hle_log("hakuX-lsfg", "descriptor pool creation failed: %d", r);
        goto fail;
    }
    VkDescriptorSetLayout set_layouts[ARRAY_SIZE(k_lsfg_passes)];
    for (unsigned i = 0; i < ARRAY_SIZE(set_layouts); ++i)
        set_layouts[i] = g_ps.lsfg_set_layouts[i];
    VkDescriptorSetAllocateInfo dsai = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
        .descriptorPool = g_ps.lsfg_descriptor_pool,
        .descriptorSetCount = ARRAY_SIZE(set_layouts),
        .pSetLayouts = set_layouts
    };
    r = T.vkAllocateDescriptorSets(g_hvk.dev, &dsai, g_ps.lsfg_descriptor_sets);
    if (r != VK_SUCCESS) {
        hle_log("hakuX-lsfg", "descriptor set allocation failed: %d", r);
        goto fail;
    }
    g_ps.lsfg_mipmap_sets[0] = g_ps.lsfg_descriptor_sets[0];
    VkDescriptorSetLayout extra_mipmap_layouts[ARRAY_SIZE(g_ps.lsfg_mipmap_sets) - 1];
    VkDescriptorSet extra_mipmap_sets[ARRAY_SIZE(g_ps.lsfg_mipmap_sets) - 1];
    for (unsigned i = 0; i < ARRAY_SIZE(extra_mipmap_layouts); ++i)
        extra_mipmap_layouts[i] = g_ps.lsfg_set_layouts[0];
    VkDescriptorSetAllocateInfo extra_dsai = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
        .descriptorPool = g_ps.lsfg_descriptor_pool,
        .descriptorSetCount = ARRAY_SIZE(extra_mipmap_layouts),
        .pSetLayouts = extra_mipmap_layouts
    };
    r = T.vkAllocateDescriptorSets(g_hvk.dev, &extra_dsai, extra_mipmap_sets);
    if (r != VK_SUCCESS) {
        hle_log("hakuX-lsfg", "six-slot mipmap descriptor allocation failed: %d", r);
        goto fail;
    }
    for (unsigned i = 1; i < ARRAY_SIZE(g_ps.lsfg_mipmap_sets); ++i)
        g_ps.lsfg_mipmap_sets[i] = extra_mipmap_sets[i - 1];
    VkDescriptorSetLayout alpha_layouts[42];
    VkDescriptorSet alpha_sets[42];
    for (unsigned level = 0; level < 7; ++level) {
        alpha_layouts[level * 6 + 0] = g_ps.lsfg_set_layouts[12];
        alpha_layouts[level * 6 + 1] = g_ps.lsfg_set_layouts[13];
        alpha_layouts[level * 6 + 2] = g_ps.lsfg_set_layouts[14];
        for (unsigned slot = 0; slot < 3; ++slot)
            alpha_layouts[level * 6 + 3 + slot] = g_ps.lsfg_set_layouts[15];
    }
    VkDescriptorSetAllocateInfo alpha_dsai = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
        .descriptorPool = g_ps.lsfg_descriptor_pool,
        .descriptorSetCount = ARRAY_SIZE(alpha_layouts),
        .pSetLayouts = alpha_layouts
    };
    r = T.vkAllocateDescriptorSets(g_hvk.dev, &alpha_dsai, alpha_sets);
    if (r != VK_SUCCESS) {
        hle_log("hakuX-lsfg", "Alpha descriptor set allocation failed: %d", r);
        goto fail;
    }
    for (unsigned level = 0; level < 7; ++level)
        for (unsigned set = 0; set < 6; ++set)
            g_ps.lsfg_alpha_sets[level][set] = alpha_sets[level * 6 + set];
    g_ps.lsfg_alpha_sets_allocated = true;
    VkDescriptorSetLayout beta_layouts[7] = {
        g_ps.lsfg_set_layouts[20], g_ps.lsfg_set_layouts[20], g_ps.lsfg_set_layouts[20],
        g_ps.lsfg_set_layouts[21], g_ps.lsfg_set_layouts[22],
        g_ps.lsfg_set_layouts[23], g_ps.lsfg_set_layouts[24]
    };
    VkDescriptorSetAllocateInfo beta_dsai = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
        .descriptorPool = g_ps.lsfg_descriptor_pool,
        .descriptorSetCount = ARRAY_SIZE(beta_layouts),
        .pSetLayouts = beta_layouts
    };
    r = T.vkAllocateDescriptorSets(g_hvk.dev, &beta_dsai, g_ps.lsfg_beta_sets);
    if (r != VK_SUCCESS) {
        hle_log("hakuX-lsfg", "Beta descriptor set allocation failed: %d", r);
        goto fail;
    }
    VkDescriptorSetLayout gamma_layouts[294];
    VkDescriptorSet gamma_sets[294];
    unsigned gamma_n = 0;
    for (unsigned level = 0; level < 7; ++level) {
        for (unsigned slot = 0; slot < 6; ++slot) {
            gamma_layouts[gamma_n++] = g_ps.lsfg_set_layouts[2];
            gamma_layouts[gamma_n++] = g_ps.lsfg_set_layouts[2];
            gamma_layouts[gamma_n++] = g_ps.lsfg_set_layouts[2];
            gamma_layouts[gamma_n++] = g_ps.lsfg_set_layouts[4];
            gamma_layouts[gamma_n++] = g_ps.lsfg_set_layouts[5];
            gamma_layouts[gamma_n++] = g_ps.lsfg_set_layouts[6];
            gamma_layouts[gamma_n++] = g_ps.lsfg_set_layouts[7];
        }
    }
    VkDescriptorSetAllocateInfo gamma_dsai = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
        .descriptorPool = g_ps.lsfg_descriptor_pool,
        .descriptorSetCount = ARRAY_SIZE(gamma_layouts),
        .pSetLayouts = gamma_layouts
    };
    r = T.vkAllocateDescriptorSets(g_hvk.dev, &gamma_dsai, gamma_sets);
    if (r != VK_SUCCESS) {
        hle_log("hakuX-lsfg", "Gamma descriptor set allocation failed: %d", r);
        goto fail;
    }
    gamma_n = 0;
    for (unsigned level = 0; level < 7; ++level)
        for (unsigned slot = 0; slot < 6; ++slot)
            for (unsigned set = 0; set < 7; ++set)
                g_ps.lsfg_gamma_sets[level][slot][set] = gamma_sets[gamma_n++];
    g_ps.lsfg_gamma_sets_allocated = true;
    VkDescriptorSetLayout delta_layouts[252];
    VkDescriptorSet delta_sets[252];
    unsigned delta_n = 0;
    for (unsigned instance = 0; instance < 3; ++instance)
        for (unsigned slot = 0; slot < 6; ++slot) {
            for (unsigned i = 0; i < 3; ++i) delta_layouts[delta_n++] = g_ps.lsfg_set_layouts[2];
            for (unsigned i = 4; i <= 7; ++i) delta_layouts[delta_n++] = g_ps.lsfg_set_layouts[i];
            for (unsigned i = 0; i < 3; ++i) delta_layouts[delta_n++] = g_ps.lsfg_set_layouts[3];
            for (unsigned i = 16; i <= 19; ++i) delta_layouts[delta_n++] = g_ps.lsfg_set_layouts[i];
        }
    VkDescriptorSetAllocateInfo delta_ai = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
        .descriptorPool = g_ps.lsfg_descriptor_pool,
        .descriptorSetCount = ARRAY_SIZE(delta_layouts),
        .pSetLayouts = delta_layouts
    };
    r = T.vkAllocateDescriptorSets(g_hvk.dev, &delta_ai, delta_sets);
    if (r != VK_SUCCESS) {
        hle_log("hakuX-lsfg", "Delta descriptor allocation failed: %d", r);
        goto fail;
    }
    delta_n = 0;
    for (unsigned instance = 0; instance < 3; ++instance)
        for (unsigned slot = 0; slot < 6; ++slot)
            for (unsigned set = 0; set < 14; ++set)
                g_ps.lsfg_delta_sets[instance][slot][set] = delta_sets[delta_n++];
    g_ps.lsfg_delta_sets_allocated = true;
    g_ps.lsfg_pipelines_ready = true;
    hle_log("hakuX-lsfg", "created %u LSFG compute pipelines and six history-indexed mipmap descriptor sets",
            (unsigned)ARRAY_SIZE(k_lsfg_passes));
    return true;

fail:
    lsfg_pipeline_destroy();
    g_ps.lsfg_pipelines_failed = true;
    return false;
}

static void idle(void);
static void barrier(VkCommandBuffer cb, VkImage img, VkImageLayout from, VkImageLayout to,
                    VkPipelineStageFlags2 src, VkAccessFlags2 src_access, VkPipelineStageFlags2 dst,
                    VkAccessFlags2 dst_access);

static void lsfg_history_drop(void)
{
    for (unsigned i = 0; i < ARRAY_SIZE(g_ps.lsfg_history); ++i) {
        if (g_ps.lsfg_history_view[i])
            T.vkDestroyImageView(g_hvk.dev, g_ps.lsfg_history_view[i], NULL);
        if (g_ps.lsfg_history[i])
            T.vkDestroyImage(g_hvk.dev, g_ps.lsfg_history[i], NULL);
        if (g_ps.lsfg_history_mem[i])
            T.vkFreeMemory(g_hvk.dev, g_ps.lsfg_history_mem[i], NULL);
        g_ps.lsfg_history_view[i] = VK_NULL_HANDLE;
        g_ps.lsfg_history[i] = VK_NULL_HANDLE;
        g_ps.lsfg_history_mem[i] = VK_NULL_HANDLE;
        g_ps.lsfg_history_layout[i] = VK_IMAGE_LAYOUT_UNDEFINED;
    }
    g_ps.lsfg_history_w = g_ps.lsfg_history_h = 0;
    g_ps.lsfg_history_last_serial = 0;
    g_ps.lsfg_history_count = 0;
    g_ps.lsfg_history_ready = false;
    g_ps.lsfg_mipmap_sets_ready = false;
}

static bool lsfg_history_make_image(uint32_t w, uint32_t h, unsigned index)
{
    const VkFormat format = VK_FORMAT_R8G8B8A8_UNORM;
    VkImageCreateInfo ici = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
        .imageType = VK_IMAGE_TYPE_2D,
        .format = format,
        .extent = { w, h, 1 },
        .mipLevels = 1,
        .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED
    };
    if (T.vkCreateImage(g_hvk.dev, &ici, NULL, &g_ps.lsfg_history[index]) != VK_SUCCESS)
        return false;

    VkMemoryRequirements mr;
    T.vkGetImageMemoryRequirements(g_hvk.dev, g_ps.lsfg_history[index], &mr);
    const int mt = hle_vk_memtype(mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0);
    if (mt < 0) return false;
    VkMemoryAllocateInfo mai = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize = mr.size,
        .memoryTypeIndex = (uint32_t)mt
    };
    if (T.vkAllocateMemory(g_hvk.dev, &mai, NULL, &g_ps.lsfg_history_mem[index]) != VK_SUCCESS ||
        T.vkBindImageMemory(g_hvk.dev, g_ps.lsfg_history[index],
                            g_ps.lsfg_history_mem[index], 0) != VK_SUCCESS)
        return false;

    VkImageViewCreateInfo vci = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
        .image = g_ps.lsfg_history[index],
        .viewType = VK_IMAGE_VIEW_TYPE_2D,
        .format = format,
        .subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 }
    };
    return T.vkCreateImageView(g_hvk.dev, &vci, NULL,
                               &g_ps.lsfg_history_view[index]) == VK_SUCCESS;
}

/* Six temporal images mirror LSFG's three history slots, each holding the
 * previous/current frame pair. They are only allocated here: capture, layout
 * transitions and dispatch are deliberately separate follow-up stages. */
static bool lsfg_history_ensure(uint32_t w, uint32_t h)
{
    if (g_ps.lsfg_history_ready && g_ps.lsfg_history_w == w &&
        g_ps.lsfg_history_h == h) return true;
    if (w < 2 || h < 2 || w > 8192 || h > 8192) return false;
    if (g_ps.lsfg_history_ready || g_ps.lsfg_history[0]) {
        idle();
        lsfg_history_drop();
    }

    VkFormatProperties fp;
    vkGetPhysicalDeviceFormatProperties(g_hvk.phys, VK_FORMAT_R8G8B8A8_UNORM, &fp);
    const VkFormatFeatureFlags required = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT |
                                           VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
    if ((fp.optimalTilingFeatures & required) != required) {
        hle_log("hakuX-lsfg", "RGBA8 does not support sampled/transfer-destination history images");
        g_ps.lsfg_history_failed = true;
        return false;
    }

    for (unsigned i = 0; i < ARRAY_SIZE(g_ps.lsfg_history); ++i) {
        if (!lsfg_history_make_image(w, h, i)) {
            hle_log("hakuX-lsfg", "history image %u allocation failed at %ux%u", i, w, h);
            lsfg_history_drop();
            g_ps.lsfg_history_failed = true;
            return false;
        }
    }
    g_ps.lsfg_history_w = w;
    g_ps.lsfg_history_h = h;
    g_ps.lsfg_history_ready = true;
    g_ps.lsfg_history_failed = false;
    hle_log("hakuX-lsfg", "allocated six RGBA8 temporal history images at %ux%u", w, h);
    return true;
}

/* Allocate the seven R8_UNORM flow pyramid targets used by LSFG's mipmap pass. */
static void lsfg_alpha_drop(void);
static void lsfg_beta_drop(void);
static void lsfg_gamma_drop(void);
static void lsfg_delta_drop(void);
static void lsfg_gamma_write_set(VkDescriptorSet set, VkBuffer buffer,
                                 VkSampler const *samplers, unsigned sampler_count,
                                 VkImageView const *sampled, unsigned sampled_count,
                                 VkImageView const *storage, unsigned storage_count,
                                 bool constants);

static void lsfg_flow_drop(void)
{
    lsfg_delta_drop();
    lsfg_gamma_drop();
    lsfg_beta_drop();
    lsfg_alpha_drop();
    for (unsigned i = 0; i < ARRAY_SIZE(g_ps.lsfg_flow); ++i) {
        if (g_ps.lsfg_flow_view[i])
            T.vkDestroyImageView(g_hvk.dev, g_ps.lsfg_flow_view[i], NULL);
        if (g_ps.lsfg_flow[i])
            T.vkDestroyImage(g_hvk.dev, g_ps.lsfg_flow[i], NULL);
        if (g_ps.lsfg_flow_mem[i])
            T.vkFreeMemory(g_hvk.dev, g_ps.lsfg_flow_mem[i], NULL);
        g_ps.lsfg_flow_view[i] = VK_NULL_HANDLE;
        g_ps.lsfg_flow[i] = VK_NULL_HANDLE;
        g_ps.lsfg_flow_mem[i] = VK_NULL_HANDLE;
    }
    g_ps.lsfg_flow_w = g_ps.lsfg_flow_h = 0;
    g_ps.lsfg_flow_scale_allocated = 0;
    g_ps.lsfg_flow_ready = false;
    g_ps.lsfg_mipmap_sets_ready = false;
    for (unsigned i = 0; i < ARRAY_SIZE(g_ps.lsfg_flow_layout); ++i)
        g_ps.lsfg_flow_layout[i] = VK_IMAGE_LAYOUT_UNDEFINED;
}

static bool lsfg_flow_make_image(uint32_t w, uint32_t h, unsigned index)
{
    VkImageCreateInfo ici = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
        .imageType = VK_IMAGE_TYPE_2D,
        .format = VK_FORMAT_R8_UNORM,
        .extent = { w, h, 1 },
        .mipLevels = 1,
        .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT |
                 VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED
    };
    if (T.vkCreateImage(g_hvk.dev, &ici, NULL, &g_ps.lsfg_flow[index]) != VK_SUCCESS)
        return false;

    VkMemoryRequirements mr;
    T.vkGetImageMemoryRequirements(g_hvk.dev, g_ps.lsfg_flow[index], &mr);
    const int mt = hle_vk_memtype(mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0);
    if (mt < 0) return false;
    VkMemoryAllocateInfo mai = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize = mr.size,
        .memoryTypeIndex = (uint32_t)mt
    };
    if (T.vkAllocateMemory(g_hvk.dev, &mai, NULL, &g_ps.lsfg_flow_mem[index]) != VK_SUCCESS ||
        T.vkBindImageMemory(g_hvk.dev, g_ps.lsfg_flow[index], g_ps.lsfg_flow_mem[index], 0) != VK_SUCCESS)
        return false;

    VkImageViewCreateInfo vci = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
        .image = g_ps.lsfg_flow[index],
        .viewType = VK_IMAGE_VIEW_TYPE_2D,
        .format = VK_FORMAT_R8_UNORM,
        .subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 }
    };
    return T.vkCreateImageView(g_hvk.dev, &vci, NULL, &g_ps.lsfg_flow_view[index]) == VK_SUCCESS;
}

static bool lsfg_flow_ensure(uint32_t w, uint32_t h, int scale_percent)
{
    if (scale_percent < 25) scale_percent = 25;
    if (scale_percent > 100) scale_percent = 100;
    if (g_ps.lsfg_flow_ready && g_ps.lsfg_flow_w == w &&
        g_ps.lsfg_flow_h == h && g_ps.lsfg_flow_scale_allocated == scale_percent)
        return true;
    if (w < 2 || h < 2 || w > 8192 || h > 8192) return false;
    if (g_ps.lsfg_flow[0]) {
        idle();
        lsfg_flow_drop();
    }
    g_ps.lsfg_flow_failed = false;

    VkFormatProperties fp;
    vkGetPhysicalDeviceFormatProperties(g_hvk.phys, VK_FORMAT_R8_UNORM, &fp);
    const VkFormatFeatureFlags required = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT |
                                           VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT;
    if ((fp.optimalTilingFeatures & required) != required) {
        hle_log("hakuX-lsfg", "R8_UNORM lacks sampled/storage support; flow pyramid disabled");
        g_ps.lsfg_flow_failed = true;
        return false;
    }

    const uint32_t flow_w = MAX(1u, (uint32_t)((uint64_t)w * (uint32_t)scale_percent / 100u));
    const uint32_t flow_h = MAX(1u, (uint32_t)((uint64_t)h * (uint32_t)scale_percent / 100u));
    for (unsigned i = 0; i < ARRAY_SIZE(g_ps.lsfg_flow); ++i) {
        const uint32_t level_w = MAX(1u, flow_w >> i);
        const uint32_t level_h = MAX(1u, flow_h >> i);
        if (!lsfg_flow_make_image(level_w, level_h, i)) {
            hle_log("hakuX-lsfg", "flow pyramid level %u allocation failed at %ux%u",
                    i, level_w, level_h);
            lsfg_flow_drop();
            g_ps.lsfg_flow_failed = true;
            return false;
        }
    }
    g_ps.lsfg_flow_w = flow_w;
    g_ps.lsfg_flow_h = flow_h;
    g_ps.lsfg_flow_scale_allocated = scale_percent;
    g_ps.lsfg_flow_ready = true;
    hle_log("hakuX-lsfg", "allocated seven R8_UNORM flow levels at %ux%u (Flow Scale %d%%)",
            flow_w, flow_h, scale_percent);
    return true;
}

/* The first LSFG pass consumes a 48-byte constants block, one linear
 * sampler, one captured history image and seven R8 flow storage targets. */
typedef struct LsfgConstants {
    uint32_t input_offset[2];
    uint32_t first_iter, first_iter_s;
    uint32_t advanced_color_kind, hdr_support;
    float resolution_inv_scale, timestamp, ui_threshold;
    uint32_t padding[3];
} LsfgConstants;

static bool lsfg_mipmap_resources_init(void)
{
    if (g_ps.lsfg_mipmap_descriptors_ready) {
        int scale = g_ps.lsfg_flow_scale < 25 ? 25 :
                    g_ps.lsfg_flow_scale > 100 ? 100 : g_ps.lsfg_flow_scale;
        if (g_ps.lsfg_constants_flow_scale != scale) {
            if (g_ps.lsfg_constants_flow_scale) idle();
            LsfgConstants constants = {
                .input_offset = { 0, 0 },
                .first_iter = 1,
                .first_iter_s = 1,
                .advanced_color_kind = 0,
                .hdr_support = 0,
                .resolution_inv_scale = 100.0f / (float)scale,
                .timestamp = 0.0f,
                .ui_threshold = 0.5f,
                .padding = { 0, 0, 0 }
            };
            memcpy(g_ps.lsfg_constants_map, &constants, sizeof(constants));
            g_ps.lsfg_constants_flow_scale = scale;
        }
        return true;
    }
    VkBufferCreateInfo bci = {
        .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
        .size = sizeof(LsfgConstants),
        .usage = VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE
    };
    if (T.vkCreateBuffer(g_hvk.dev, &bci, NULL, &g_ps.lsfg_constants_buffer) != VK_SUCCESS)
        return false;
    VkMemoryRequirements mr;
    T.vkGetBufferMemoryRequirements(g_hvk.dev, g_ps.lsfg_constants_buffer, &mr);
    const int mt = hle_vk_memtype(mr.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, 0);
    if (mt < 0) return false;
    VkMemoryAllocateInfo mai = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize = mr.size,
        .memoryTypeIndex = (uint32_t)mt
    };
    if (T.vkAllocateMemory(g_hvk.dev, &mai, NULL, &g_ps.lsfg_constants_memory) != VK_SUCCESS ||
        T.vkBindBufferMemory(g_hvk.dev, g_ps.lsfg_constants_buffer, g_ps.lsfg_constants_memory, 0) != VK_SUCCESS ||
        T.vkMapMemory(g_hvk.dev, g_ps.lsfg_constants_memory, 0, sizeof(LsfgConstants), 0,
                      (void **)&g_ps.lsfg_constants_map) != VK_SUCCESS)
        return false;

    VkSamplerCreateInfo sci = {
        .sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO,
        .magFilter = VK_FILTER_LINEAR,
        .minFilter = VK_FILTER_LINEAR,
        .mipmapMode = VK_SAMPLER_MIPMAP_MODE_LINEAR,
        .addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER,
        .addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER,
        .addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER,
        .maxAnisotropy = 1.0f,
        .borderColor = VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK,
        .minLod = 0.0f,
        .maxLod = 0.0f
    };
    if (T.vkCreateSampler(g_hvk.dev, &sci, NULL, &g_ps.lsfg_sampler) != VK_SUCCESS)
        return false;

    LsfgConstants constants = {
        .input_offset = { 0, 0 },
        .first_iter = 1,
        .first_iter_s = 1,
        .advanced_color_kind = 0,
        .hdr_support = 0,
        .resolution_inv_scale = 100.0f / (float)(g_ps.lsfg_flow_scale < 25 ? 25 :
                                    g_ps.lsfg_flow_scale > 100 ? 100 : g_ps.lsfg_flow_scale),
        .timestamp = 0.0f,
        .ui_threshold = 0.5f,
        .padding = { 0, 0, 0 }
    };
    memcpy(g_ps.lsfg_constants_map, &constants, sizeof(constants));
    g_ps.lsfg_constants_flow_scale = g_ps.lsfg_flow_scale < 25 ? 25 :
                                     g_ps.lsfg_flow_scale > 100 ? 100 : g_ps.lsfg_flow_scale;
    g_ps.lsfg_mipmap_descriptors_ready = true;
    hle_log("hakuX-lsfg", "mipmap constants buffer and linear clamp sampler ready");
    return true;
}

static bool lsfg_mipmap_descriptors_update(void)
{
    if (!g_ps.lsfg_pipelines_ready || !g_ps.lsfg_flow_ready ||
        !g_ps.lsfg_history_ready || !lsfg_mipmap_resources_init())
        return false;
    if (g_ps.lsfg_mipmap_sets_ready) return true;

    VkDescriptorBufferInfo buffer_info = {
        .buffer = g_ps.lsfg_constants_buffer,
        .offset = 0,
        .range = sizeof(LsfgConstants)
    };
    VkDescriptorImageInfo sampler_info = { .sampler = g_ps.lsfg_sampler };
    VkDescriptorImageInfo storage_infos[7];
    VkWriteDescriptorSet writes[10];
    for (unsigned slot = 0; slot < ARRAY_SIZE(g_ps.lsfg_mipmap_sets); ++slot) {
        VkDescriptorImageInfo sampled_info = {
            .imageView = g_ps.lsfg_history_view[slot],
            .imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
        };
        memset(storage_infos, 0, sizeof(storage_infos));
        memset(writes, 0, sizeof(writes));
        writes[0] = (VkWriteDescriptorSet) {
            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
            .dstSet = g_ps.lsfg_mipmap_sets[slot],
            .dstBinding = 0, .descriptorCount = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
            .pBufferInfo = &buffer_info
        };
        writes[1] = (VkWriteDescriptorSet) {
            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
            .dstSet = g_ps.lsfg_mipmap_sets[slot],
            .dstBinding = 1, .descriptorCount = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_SAMPLER,
            .pImageInfo = &sampler_info
        };
        writes[2] = (VkWriteDescriptorSet) {
            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
            .dstSet = g_ps.lsfg_mipmap_sets[slot],
            .dstBinding = 2, .descriptorCount = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE,
            .pImageInfo = &sampled_info
        };
        for (unsigned i = 0; i < ARRAY_SIZE(storage_infos); ++i) {
            storage_infos[i] = (VkDescriptorImageInfo) {
                .imageView = g_ps.lsfg_flow_view[i],
                .imageLayout = VK_IMAGE_LAYOUT_GENERAL
            };
            writes[3 + i] = (VkWriteDescriptorSet) {
                .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
                .dstSet = g_ps.lsfg_mipmap_sets[slot],
                .dstBinding = 3 + i, .descriptorCount = 1,
                .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                .pImageInfo = &storage_infos[i]
            };
        }
        T.vkUpdateDescriptorSets(g_hvk.dev, ARRAY_SIZE(writes), writes, 0, NULL);
    }
    g_ps.lsfg_mipmap_sets_ready = true;
    return true;
}

/* Run the first real LSFG compute pass once a distinct frame has been captured.
 * The six descriptor sets are stable for the six-slot history ring, avoiding
 * descriptor mutation while an earlier presenter command buffer is in flight. */
static bool lsfg_mipmap_dispatch(VkCommandBuffer cb, unsigned history_slot)
{
    if (!g_ps.lsfg_pipelines_ready || !g_ps.lsfg_mipmap_sets_ready ||
        !g_ps.lsfg_flow_ready || history_slot >= ARRAY_SIZE(g_ps.lsfg_mipmap_sets))
        return false;

    for (unsigned i = 0; i < ARRAY_SIZE(g_ps.lsfg_flow); ++i) {
        const VkImageLayout old = g_ps.lsfg_flow_layout[i];
        const VkAccessFlags2 src_access = old == VK_IMAGE_LAYOUT_UNDEFINED ? 0 :
            old == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL ? VK_ACCESS_2_SHADER_SAMPLED_READ_BIT :
                                                              VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT;
        barrier(cb, g_ps.lsfg_flow[i], old, VK_IMAGE_LAYOUT_GENERAL,
                old == VK_IMAGE_LAYOUT_UNDEFINED ? VK_PIPELINE_STAGE_2_NONE :
                    VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                src_access, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        g_ps.lsfg_flow_layout[i] = VK_IMAGE_LAYOUT_GENERAL;
    }

    T.vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, g_ps.lsfg_pipelines[0]);
    VkDescriptorSet set = g_ps.lsfg_mipmap_sets[history_slot];
    T.vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE,
                              g_ps.lsfg_pipeline_layouts[0], 0, 1, &set, 0, NULL);
    const uint32_t gx = (g_ps.lsfg_flow_w + 63u) >> 6;
    const uint32_t gy = (g_ps.lsfg_flow_h + 63u) >> 6;
    if (!gx || !gy) return false;
    T.vkCmdDispatch(cb, gx, gy, 1);
    return true;
}

/* Copy each newly completed display frame into the temporal ring. The
 * display slots are RGBA8 and already include the guest render/gamma work.
 * Capture runs in the presenter's command buffer, on the same queue, while
 * hle_disp_use_begin keeps the source slot alive. */
static bool lsfg_history_capture(VkCommandBuffer cb)
{
    if (!g_ps.have || !g_ps.lsfg_history_ready ||
        g_ps.lsfg_history_last_serial == g_ps.serial) return false;

    const unsigned index = (unsigned)(g_ps.serial % ARRAY_SIZE(g_ps.lsfg_history));
    const VkImageLayout old_layout = g_ps.lsfg_history_layout[index];
    barrier(cb, g_ps.f.img, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
            VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT);
    barrier(cb, g_ps.lsfg_history[index], old_layout, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            old_layout == VK_IMAGE_LAYOUT_UNDEFINED ? VK_PIPELINE_STAGE_2_NONE : VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
            old_layout == VK_IMAGE_LAYOUT_UNDEFINED ? 0 : VK_ACCESS_2_SHADER_SAMPLED_READ_BIT,
            VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT);
    VkImageCopy copy = {
        .srcSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 },
        .dstSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 },
        .extent = { g_ps.f.w, g_ps.f.h, 1 }
    };
    T.vkCmdCopyImage(cb, g_ps.f.img, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                     g_ps.lsfg_history[index], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);
    barrier(cb, g_ps.lsfg_history[index], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_WRITE_BIT,
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
    barrier(cb, g_ps.f.img, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_PIPELINE_STAGE_2_TRANSFER_BIT, VK_ACCESS_2_TRANSFER_READ_BIT,
            VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);

    g_ps.lsfg_history_layout[index] = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    g_ps.lsfg_history_last_serial = g_ps.serial;
    const bool first_pair = g_ps.lsfg_history_count == 1;
    if (g_ps.lsfg_history_count < ARRAY_SIZE(g_ps.lsfg_history))
        g_ps.lsfg_history_count++;
    if (first_pair)
        hle_log("hakuX-lsfg", "temporal capture active: two distinct RGBA8 frames available (%ux%u)",
                g_ps.f.w, g_ps.f.h);
    return true;
}

static bool lsfg_modules_init(void)
{
    if (g_ps.lsfg_modules_ready) return true;
    if (g_ps.lsfg_modules_failed || hle_lsfg_shader_count() != ARRAY_SIZE(k_lsfg_passes)) return false;
    if (!g_hvk.caps.lsfg_memory_model || !g_hvk.caps.lsfg_null_descriptor) {
        hle_log("hakuX-lsfg", "device lacks Vulkan memory model or nullDescriptor; LSFG disabled");
        g_ps.lsfg_modules_failed = true;
        return false;
    }

    for (unsigned i = 0; i < ARRAY_SIZE(k_lsfg_passes); ++i) {
        const uint32_t *words = NULL;
        size_t count = 0;
        const uint32_t id = k_lsfg_passes[i].id;
        if (!hle_lsfg_get_shader(id, &words, &count) || !words ||
            count < 5 || count > SIZE_MAX / sizeof(uint32_t)) {
            hle_log("hakuX-lsfg", "shader %u is missing or invalid; Vulkan modules disabled", id);
            goto fail;
        }
        VkShaderModuleCreateInfo ci = {
            .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
            .codeSize = count * sizeof(uint32_t),
            .pCode = words
        };
        const VkResult result = T.vkCreateShaderModule(g_hvk.dev, &ci, NULL, &g_ps.lsfg_modules[i]);
        if (result != VK_SUCCESS) {
            hle_log("hakuX-lsfg", "vkCreateShaderModule failed for resource %u: %d", id, result);
            goto fail;
        }
    }
    g_ps.lsfg_modules_ready = true;
    hle_log("hakuX-lsfg", "Vulkan accepted all %u LSFG shader modules", (unsigned)ARRAY_SIZE(k_lsfg_passes));
    return true;

fail:
    for (unsigned i = 0; i < ARRAY_SIZE(k_lsfg_passes); ++i) {
        if (g_ps.lsfg_modules[i]) {
            T.vkDestroyShaderModule(g_hvk.dev, g_ps.lsfg_modules[i], NULL);
            g_ps.lsfg_modules[i] = VK_NULL_HANDLE;
        }
    }
    g_ps.lsfg_modules_failed = true;
    return false;
}


static void lsfg_alpha_image_drop(LsfgAlphaImage *image)
{
    if (image->view) T.vkDestroyImageView(g_hvk.dev, image->view, NULL);
    if (image->image) T.vkDestroyImage(g_hvk.dev, image->image, NULL);
    if (image->memory) T.vkFreeMemory(g_hvk.dev, image->memory, NULL);
    memset(image, 0, sizeof(*image));
}

static bool lsfg_alpha_image_make(LsfgAlphaImage *out, uint32_t w, uint32_t h)
{
    VkImageCreateInfo ici = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
        .imageType = VK_IMAGE_TYPE_2D,
        .format = VK_FORMAT_R8G8B8A8_UNORM,
        .extent = { w, h, 1 },
        .mipLevels = 1,
        .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED
    };
    if (T.vkCreateImage(g_hvk.dev, &ici, NULL, &out->image) != VK_SUCCESS) return false;
    VkMemoryRequirements mr;
    T.vkGetImageMemoryRequirements(g_hvk.dev, out->image, &mr);
    const int mt = hle_vk_memtype(mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0);
    if (mt < 0) return false;
    VkMemoryAllocateInfo mai = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize = mr.size,
        .memoryTypeIndex = (uint32_t)mt
    };
    if (T.vkAllocateMemory(g_hvk.dev, &mai, NULL, &out->memory) != VK_SUCCESS ||
        T.vkBindImageMemory(g_hvk.dev, out->image, out->memory, 0) != VK_SUCCESS)
        return false;
    VkImageViewCreateInfo vci = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
        .image = out->image,
        .viewType = VK_IMAGE_VIEW_TYPE_2D,
        .format = VK_FORMAT_R8G8B8A8_UNORM,
        .subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 }
    };
    return T.vkCreateImageView(g_hvk.dev, &vci, NULL, &out->view) == VK_SUCCESS;
}

static void lsfg_beta_drop(void)
{
    for (unsigned i = 0; i < ARRAY_SIZE(g_ps.lsfg_beta_temp1); ++i) {
        lsfg_alpha_image_drop(&g_ps.lsfg_beta_temp1[i]);
        lsfg_alpha_image_drop(&g_ps.lsfg_beta_temp2[i]);
    }
    for (unsigned i = 0; i < ARRAY_SIZE(g_ps.lsfg_beta_outputs); ++i)
        lsfg_alpha_image_drop(&g_ps.lsfg_beta_outputs[i]);
    if (g_ps.lsfg_beta_sampler)
        T.vkDestroySampler(g_hvk.dev, g_ps.lsfg_beta_sampler, NULL);
    if (g_ps.lsfg_beta_constants_map)
        T.vkUnmapMemory(g_hvk.dev, g_ps.lsfg_beta_constants_memory);
    if (g_ps.lsfg_beta_constants_buffer)
        T.vkDestroyBuffer(g_hvk.dev, g_ps.lsfg_beta_constants_buffer, NULL);
    if (g_ps.lsfg_beta_constants_memory)
        T.vkFreeMemory(g_hvk.dev, g_ps.lsfg_beta_constants_memory, NULL);
    g_ps.lsfg_beta_sampler = VK_NULL_HANDLE;
    g_ps.lsfg_beta_constants_buffer = VK_NULL_HANDLE;
    g_ps.lsfg_beta_constants_memory = VK_NULL_HANDLE;
    g_ps.lsfg_beta_constants_map = NULL;
    g_ps.lsfg_beta_ready = false;
}

static bool lsfg_beta_image_make(LsfgAlphaImage *out, uint32_t w, uint32_t h,
                                 VkFormat format)
{
    VkImageCreateInfo ici = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
        .imageType = VK_IMAGE_TYPE_2D,
        .format = format,
        .extent = { MAX(1u, w), MAX(1u, h), 1 },
        .mipLevels = 1,
        .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT |
                 VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED
    };
    if (T.vkCreateImage(g_hvk.dev, &ici, NULL, &out->image) != VK_SUCCESS) return false;
    VkMemoryRequirements mr;
    T.vkGetImageMemoryRequirements(g_hvk.dev, out->image, &mr);
    const int mt = hle_vk_memtype(mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0);
    if (mt < 0) return false;
    VkMemoryAllocateInfo mai = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize = mr.size,
        .memoryTypeIndex = (uint32_t)mt
    };
    if (T.vkAllocateMemory(g_hvk.dev, &mai, NULL, &out->memory) != VK_SUCCESS ||
        T.vkBindImageMemory(g_hvk.dev, out->image, out->memory, 0) != VK_SUCCESS)
        return false;
    VkImageViewCreateInfo vci = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
        .image = out->image,
        .viewType = VK_IMAGE_VIEW_TYPE_2D,
        .format = format,
        .subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 }
    };
    return T.vkCreateImageView(g_hvk.dev, &vci, NULL, &out->view) == VK_SUCCESS;
}

static void lsfg_beta_write_set(VkDescriptorSet set, VkSampler sampler,
                                VkImageView const *sampled, unsigned sampled_count,
                                VkImageView const *storage, unsigned storage_count,
                                bool constants)
{
    VkDescriptorImageInfo infos[9];
    VkDescriptorBufferInfo buffer = {
        .buffer = g_ps.lsfg_beta_constants_buffer, .offset = 0, .range = sizeof(LsfgConstants)
    };
    VkWriteDescriptorSet writes[10];
    memset(infos, 0, sizeof(infos));
    memset(writes, 0, sizeof(writes));
    unsigned n = 0;
    if (constants) {
        writes[n] = (VkWriteDescriptorSet) {
            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = set,
            .dstBinding = n, .descriptorCount = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, .pBufferInfo = &buffer
        };
        ++n;
    }
    infos[n].sampler = sampler;
    writes[n] = (VkWriteDescriptorSet) {
        .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = set,
        .dstBinding = n, .descriptorCount = 1,
        .descriptorType = VK_DESCRIPTOR_TYPE_SAMPLER, .pImageInfo = &infos[n]
    };
    ++n;
    for (unsigned i = 0; i < sampled_count; ++i, ++n) {
        infos[n] = (VkDescriptorImageInfo) {
            .imageView = sampled[i], .imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
        };
        writes[n] = (VkWriteDescriptorSet) {
            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = set,
            .dstBinding = n, .descriptorCount = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, .pImageInfo = &infos[n]
        };
    }
    for (unsigned i = 0; i < storage_count; ++i, ++n) {
        infos[n] = (VkDescriptorImageInfo) {
            .imageView = storage[i], .imageLayout = VK_IMAGE_LAYOUT_GENERAL
        };
        writes[n] = (VkWriteDescriptorSet) {
            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = set,
            .dstBinding = n, .descriptorCount = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, .pImageInfo = &infos[n]
        };
    }
    T.vkUpdateDescriptorSets(g_hvk.dev, n, writes, 0, NULL);
}

static bool lsfg_beta_ensure(void)
{
    if (!g_ps.lsfg_alpha_ready || !g_ps.lsfg_pipelines_ready) return false;
    if (g_ps.lsfg_beta_ready) return true;
    const uint32_t w = MAX(1u, (g_ps.lsfg_flow_w + 3u) >> 2);
    const uint32_t h = MAX(1u, (g_ps.lsfg_flow_h + 3u) >> 2);
    bool ok = true;
    for (unsigned i = 0; ok && i < 2; ++i)
        ok = lsfg_beta_image_make(&g_ps.lsfg_beta_temp1[i], w, h, VK_FORMAT_R8G8B8A8_UNORM) &&
             lsfg_beta_image_make(&g_ps.lsfg_beta_temp2[i], w, h, VK_FORMAT_R8G8B8A8_UNORM);
    for (unsigned i = 0; ok && i < 6; ++i)
        ok = lsfg_beta_image_make(&g_ps.lsfg_beta_outputs[i], MAX(1u, w >> i),
                                  MAX(1u, h >> i), VK_FORMAT_R8_UNORM);
    if (!ok) {
        hle_log("hakuX-lsfg", "Beta intermediate/output image allocation failed");
        lsfg_beta_drop();
        return false;
    }

    VkBufferCreateInfo bci = {
        .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
        .size = sizeof(LsfgConstants),
        .usage = VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE
    };
    if (T.vkCreateBuffer(g_hvk.dev, &bci, NULL, &g_ps.lsfg_beta_constants_buffer) != VK_SUCCESS)
        goto fail;
    VkMemoryRequirements mr;
    T.vkGetBufferMemoryRequirements(g_hvk.dev, g_ps.lsfg_beta_constants_buffer, &mr);
    const int mt = hle_vk_memtype(mr.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, 0);
    if (mt < 0) goto fail;
    VkMemoryAllocateInfo mai = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize = mr.size, .memoryTypeIndex = (uint32_t)mt
    };
    if (T.vkAllocateMemory(g_hvk.dev, &mai, NULL, &g_ps.lsfg_beta_constants_memory) != VK_SUCCESS ||
        T.vkBindBufferMemory(g_hvk.dev, g_ps.lsfg_beta_constants_buffer,
                             g_ps.lsfg_beta_constants_memory, 0) != VK_SUCCESS ||
        T.vkMapMemory(g_hvk.dev, g_ps.lsfg_beta_constants_memory, 0, sizeof(LsfgConstants),
                      0, (void **)&g_ps.lsfg_beta_constants_map) != VK_SUCCESS)
        goto fail;
    const unsigned scale = (unsigned)(g_ps.lsfg_flow_scale < 25 ? 25 :
                              g_ps.lsfg_flow_scale > 100 ? 100 : g_ps.lsfg_flow_scale);
    const LsfgConstants constants = {
        .input_offset = { 0, 0 }, .first_iter = 0, .first_iter_s = 0,
        .advanced_color_kind = 0, .hdr_support = 0,
        .resolution_inv_scale = 100.0f / (float)scale,
        .timestamp = 0.5f, .ui_threshold = 0.5f, .padding = { 0, 0, 0 }
    };
    memcpy(g_ps.lsfg_beta_constants_map, &constants, sizeof(constants));

    VkSamplerCreateInfo sci = {
        .sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO,
        .magFilter = VK_FILTER_LINEAR, .minFilter = VK_FILTER_LINEAR,
        .mipmapMode = VK_SAMPLER_MIPMAP_MODE_LINEAR,
        .addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        .addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        .addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        .maxAnisotropy = 1.0f, .minLod = 0.0f, .maxLod = 0.0f
    };
    if (T.vkCreateSampler(g_hvk.dev, &sci, NULL, &g_ps.lsfg_beta_sampler) != VK_SUCCESS)
        goto fail;

    VkImageView sampled[6], storage[6];
    for (unsigned slot = 0; slot < 3; ++slot) {
        unsigned n = 0;
        for (unsigned pair = 0; pair < 2; ++pair)
            sampled[n++] = g_ps.lsfg_alpha[0].outputs[(slot + 1) % 3][pair].view;
        for (unsigned pair = 0; pair < 2; ++pair)
            sampled[n++] = g_ps.lsfg_alpha[0].outputs[(slot + 2) % 3][pair].view;
        for (unsigned pair = 0; pair < 2; ++pair)
            sampled[n++] = g_ps.lsfg_alpha[0].outputs[slot][pair].view;
        storage[0] = g_ps.lsfg_beta_temp1[0].view;
        storage[1] = g_ps.lsfg_beta_temp1[1].view;
        lsfg_beta_write_set(g_ps.lsfg_beta_sets[slot], g_ps.lsfg_sampler,
                            sampled, 6, storage, 2, false);
    }
    for (unsigned i = 0; i < 2; ++i) sampled[i] = g_ps.lsfg_beta_temp1[i].view;
    storage[0] = g_ps.lsfg_beta_temp2[0].view;
    storage[1] = g_ps.lsfg_beta_temp2[1].view;
    lsfg_beta_write_set(g_ps.lsfg_beta_sets[3], g_ps.lsfg_beta_sampler,
                        sampled, 2, storage, 2, false);
    for (unsigned i = 0; i < 2; ++i) sampled[i] = g_ps.lsfg_beta_temp2[i].view;
    storage[0] = g_ps.lsfg_beta_temp1[0].view;
    storage[1] = g_ps.lsfg_beta_temp1[1].view;
    lsfg_beta_write_set(g_ps.lsfg_beta_sets[4], g_ps.lsfg_beta_sampler,
                        sampled, 2, storage, 2, false);
    for (unsigned i = 0; i < 2; ++i) sampled[i] = g_ps.lsfg_beta_temp1[i].view;
    storage[0] = g_ps.lsfg_beta_temp2[0].view;
    storage[1] = g_ps.lsfg_beta_temp2[1].view;
    lsfg_beta_write_set(g_ps.lsfg_beta_sets[5], g_ps.lsfg_beta_sampler,
                        sampled, 2, storage, 2, false);
    for (unsigned i = 0; i < 2; ++i) sampled[i] = g_ps.lsfg_beta_temp2[i].view;
    for (unsigned i = 0; i < 6; ++i) storage[i] = g_ps.lsfg_beta_outputs[i].view;
    lsfg_beta_write_set(g_ps.lsfg_beta_sets[6], g_ps.lsfg_beta_sampler,
                        sampled, 2, storage, 6, true);
    g_ps.lsfg_beta_ready = true;
    hle_log("hakuX-lsfg", "Beta resources ready: five compute passes, three temporal sets, six flow outputs");
    return true;

fail:
    hle_log("hakuX-lsfg", "Beta constants/sampler initialization failed");
    lsfg_beta_drop();
    return false;
}

static void lsfg_alpha_transition(VkCommandBuffer cb, LsfgAlphaImage *image,
                                  VkImageLayout target, VkAccessFlags2 dst_access);

static void lsfg_beta_transition(VkCommandBuffer cb, LsfgAlphaImage *image,
                                 VkImageLayout target, VkAccessFlags2 dst_access)
{
    const VkImageLayout old = image->layout;
    const VkAccessFlags2 src_access = old == VK_IMAGE_LAYOUT_UNDEFINED ? 0 :
        old == VK_IMAGE_LAYOUT_GENERAL ? VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT :
                                         VK_ACCESS_2_SHADER_SAMPLED_READ_BIT;
    barrier(cb, image->image, old, target,
            old == VK_IMAGE_LAYOUT_UNDEFINED ? VK_PIPELINE_STAGE_2_NONE :
                                               VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
            src_access, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, dst_access);
    image->layout = target;
}

static bool lsfg_beta_dispatch(VkCommandBuffer cb, uint64_t frame_serial)
{
    if (!g_ps.lsfg_beta_ready || !g_ps.lsfg_alpha_ready) return false;
    const uint32_t w = g_ps.lsfg_beta_temp1[0].image ? MAX(1u, (g_ps.lsfg_flow_w + 3u) >> 2) : 0;
    const uint32_t h = g_ps.lsfg_beta_temp1[0].image ? MAX(1u, (g_ps.lsfg_flow_h + 3u) >> 2) : 0;
    const uint32_t gx = (w + 7u) >> 3, gy = (h + 7u) >> 3;
    if (!gx || !gy) return false;
    for (unsigned slot = 0; slot < 3; ++slot)
        for (unsigned pair = 0; pair < 2; ++pair)
            lsfg_alpha_transition(cb, &g_ps.lsfg_alpha[0].outputs[slot][pair],
                                  VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                                  VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
    for (unsigned i = 0; i < 2; ++i) {
        lsfg_beta_transition(cb, &g_ps.lsfg_beta_temp1[i], VK_IMAGE_LAYOUT_GENERAL,
                             VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        lsfg_beta_transition(cb, &g_ps.lsfg_beta_temp2[i], VK_IMAGE_LAYOUT_GENERAL,
                             VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
    }
    for (unsigned i = 0; i < 6; ++i)
        lsfg_beta_transition(cb, &g_ps.lsfg_beta_outputs[i], VK_IMAGE_LAYOUT_GENERAL,
                             VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
    T.vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, g_ps.lsfg_pipelines[20]);
    VkDescriptorSet set = g_ps.lsfg_beta_sets[frame_serial % 3u];
    T.vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE,
                              g_ps.lsfg_pipeline_layouts[20], 0, 1, &set, 0, NULL);
    T.vkCmdDispatch(cb, gx, gy, 1);

    for (unsigned i = 0; i < 2; ++i)
        lsfg_beta_transition(cb, &g_ps.lsfg_beta_temp1[i], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                             VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
    for (unsigned i = 0; i < 2; ++i)
        lsfg_beta_transition(cb, &g_ps.lsfg_beta_temp2[i], VK_IMAGE_LAYOUT_GENERAL,
                             VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
    T.vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, g_ps.lsfg_pipelines[21]);
    set = g_ps.lsfg_beta_sets[3];
    T.vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE,
                              g_ps.lsfg_pipeline_layouts[21], 0, 1, &set, 0, NULL);
    T.vkCmdDispatch(cb, gx, gy, 1);

    for (unsigned i = 0; i < 2; ++i)
        lsfg_beta_transition(cb, &g_ps.lsfg_beta_temp2[i], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                             VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
    for (unsigned i = 0; i < 2; ++i)
        lsfg_beta_transition(cb, &g_ps.lsfg_beta_temp1[i], VK_IMAGE_LAYOUT_GENERAL,
                             VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
    T.vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, g_ps.lsfg_pipelines[22]);
    set = g_ps.lsfg_beta_sets[4];
    T.vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE,
                              g_ps.lsfg_pipeline_layouts[22], 0, 1, &set, 0, NULL);
    T.vkCmdDispatch(cb, gx, gy, 1);

    for (unsigned i = 0; i < 2; ++i)
        lsfg_beta_transition(cb, &g_ps.lsfg_beta_temp1[i], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                             VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
    for (unsigned i = 0; i < 2; ++i)
        lsfg_beta_transition(cb, &g_ps.lsfg_beta_temp2[i], VK_IMAGE_LAYOUT_GENERAL,
                             VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
    T.vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, g_ps.lsfg_pipelines[23]);
    set = g_ps.lsfg_beta_sets[5];
    T.vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE,
                              g_ps.lsfg_pipeline_layouts[23], 0, 1, &set, 0, NULL);
    T.vkCmdDispatch(cb, gx, gy, 1);

    for (unsigned i = 0; i < 2; ++i)
        lsfg_beta_transition(cb, &g_ps.lsfg_beta_temp2[i], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                             VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
    T.vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, g_ps.lsfg_pipelines[24]);
    set = g_ps.lsfg_beta_sets[6];
    T.vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE,
                              g_ps.lsfg_pipeline_layouts[24], 0, 1, &set, 0, NULL);
    T.vkCmdDispatch(cb, (w + 31u) >> 5, (h + 31u) >> 5, 1);
    return true;
}


static void lsfg_delta_drop(void);


static void lsfg_delta_drop(void)
{
    for (unsigned instance = 0; instance < 3; ++instance) {
        LsfgDeltaLevel *d = &g_ps.lsfg_delta[instance];
        for (unsigned i = 0; i < 3; ++i) lsfg_alpha_image_drop(&d->temp1[i]);
        for (unsigned i = 0; i < 2; ++i) lsfg_alpha_image_drop(&d->temp2[i]);
        lsfg_alpha_image_drop(&d->output1);
        lsfg_alpha_image_drop(&d->output2);
        for (unsigned slot = 0; slot < 6; ++slot) {
            if (g_ps.lsfg_delta_constants_map[instance][slot])
                T.vkUnmapMemory(g_hvk.dev, g_ps.lsfg_delta_constants_memory[instance][slot]);
            if (g_ps.lsfg_delta_constants_buffer[instance][slot])
                T.vkDestroyBuffer(g_hvk.dev, g_ps.lsfg_delta_constants_buffer[instance][slot], NULL);
            if (g_ps.lsfg_delta_constants_memory[instance][slot])
                T.vkFreeMemory(g_hvk.dev, g_ps.lsfg_delta_constants_memory[instance][slot], NULL);
            g_ps.lsfg_delta_constants_map[instance][slot] = NULL;
            g_ps.lsfg_delta_constants_buffer[instance][slot] = VK_NULL_HANDLE;
            g_ps.lsfg_delta_constants_memory[instance][slot] = VK_NULL_HANDLE;
        }
    }
    g_ps.lsfg_delta_flow_w = g_ps.lsfg_delta_flow_h = 0;
    g_ps.lsfg_delta_ready = false;
}
static bool lsfg_delta_make_constants(unsigned instance, unsigned slot, int scale_percent)
{
    VkBuffer *buffer = &g_ps.lsfg_delta_constants_buffer[instance][slot];
    VkDeviceMemory *memory = &g_ps.lsfg_delta_constants_memory[instance][slot];
    uint8_t **mapped = &g_ps.lsfg_delta_constants_map[instance][slot];
    VkBufferCreateInfo bci = { .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
        .size = sizeof(LsfgConstants), .usage = VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE };
    if (T.vkCreateBuffer(g_hvk.dev, &bci, NULL, buffer) != VK_SUCCESS) return false;
    VkMemoryRequirements mr;
    T.vkGetBufferMemoryRequirements(g_hvk.dev, *buffer, &mr);
    const int mt = hle_vk_memtype(mr.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, 0);
    if (mt < 0) return false;
    VkMemoryAllocateInfo mai = { .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize = mr.size, .memoryTypeIndex = (uint32_t)mt };
    if (T.vkAllocateMemory(g_hvk.dev, &mai, NULL, memory) != VK_SUCCESS ||
        T.vkBindBufferMemory(g_hvk.dev, *buffer, *memory, 0) != VK_SUCCESS ||
        T.vkMapMemory(g_hvk.dev, *memory, 0, sizeof(LsfgConstants), 0, (void **)mapped) != VK_SUCCESS)
        return false;
    static const float timestamps[6] = { 0.5f, 1.0f/3.0f, 2.0f/3.0f, 0.25f, 0.5f, 0.75f };
    const LsfgConstants c = { .input_offset = { 0, 0 }, .first_iter = 0,
        .first_iter_s = instance == 0 ? 1u : 0u, .advanced_color_kind = 0, .hdr_support = 0,
        .resolution_inv_scale = 100.0f / (float)scale_percent, .timestamp = timestamps[slot],
        .ui_threshold = 0.5f, .padding = { 0, 0, 0 } };
    memcpy(*mapped, &c, sizeof(c));
    return true;
}
static bool lsfg_delta_ensure(void)
{
    if (!g_ps.lsfg_gamma_ready || !g_ps.lsfg_delta_sets_allocated ||
        !g_ps.lsfg_pipelines_ready || !g_ps.lsfg_beta_ready) return false;
    if (g_ps.lsfg_delta_ready && g_ps.lsfg_delta_flow_w == g_ps.lsfg_flow_w &&
        g_ps.lsfg_delta_flow_h == g_ps.lsfg_flow_h) return true;
    if (g_ps.lsfg_delta_ready) { idle(); lsfg_delta_drop(); }
    const int scale = g_ps.lsfg_flow_scale < 25 ? 25 :
                      g_ps.lsfg_flow_scale > 100 ? 100 : g_ps.lsfg_flow_scale;
    for (unsigned instance = 0; instance < 3; ++instance) {
        const unsigned level = 2u - instance;
        const uint32_t w = MAX(1u, (MAX(1u, g_ps.lsfg_flow_w >> level) + 3u) >> 2);
        const uint32_t h = MAX(1u, (MAX(1u, g_ps.lsfg_flow_h >> level) + 3u) >> 2);
        LsfgDeltaLevel *d = &g_ps.lsfg_delta[instance];
        for (unsigned i = 0; i < 3; ++i)
            if (!lsfg_beta_image_make(&d->temp1[i], w, h, VK_FORMAT_R8G8B8A8_UNORM)) goto fail;
        for (unsigned i = 0; i < 2; ++i)
            if (!lsfg_beta_image_make(&d->temp2[i], w, h, VK_FORMAT_R8G8B8A8_UNORM)) goto fail;
        if (!lsfg_beta_image_make(&d->output1, w, h, VK_FORMAT_R16G16B16A16_SFLOAT) ||
            !lsfg_beta_image_make(&d->output2, w, h, VK_FORMAT_R16G16B16A16_SFLOAT)) goto fail;
        for (unsigned slot = 0; slot < 6; ++slot)
            if (!lsfg_delta_make_constants(instance, slot, scale)) goto fail;
    }
    for (unsigned instance = 0; instance < 3; ++instance) {
        const unsigned level = 2u - instance;
        LsfgDeltaLevel *d = &g_ps.lsfg_delta[instance];
        LsfgAlphaLevel *a = &g_ps.lsfg_alpha[level];
        VkImageView pg = instance == 0 ? VK_NULL_HANDLE : g_ps.lsfg_gamma[instance + 2].output.view;
        VkImageView p1 = instance == 0 ? VK_NULL_HANDLE : g_ps.lsfg_delta[instance - 1].output1.view;
        VkImageView p2 = instance == 0 ? VK_NULL_HANDLE : g_ps.lsfg_delta[instance - 1].output2.view;
        VkImageView flow = g_ps.lsfg_beta_outputs[level].view;
        for (unsigned slot = 0; slot < 6; ++slot) {
            VkBuffer buffer = g_ps.lsfg_delta_constants_buffer[instance][slot];
            VkSampler border_edge[2] = { g_ps.lsfg_gamma_border_sampler, g_ps.lsfg_beta_sampler };
            VkSampler regular = g_ps.lsfg_sampler;
            for (unsigned history = 0; history < 3; ++history) {
                VkImageView first[5] = { a->outputs[(history + 2) % 3][0].view,
                    a->outputs[(history + 2) % 3][1].view, a->outputs[history][0].view,
                    a->outputs[history][1].view, pg };
                VkImageView first_out[3] = { d->temp1[0].view, d->temp1[1].view, d->temp1[2].view };
                lsfg_gamma_write_set(g_ps.lsfg_delta_sets[instance][slot][history],
                    buffer, border_edge, 2, first, 5, first_out, 3, true);
                VkImageView sixth[6] = { a->outputs[(history + 2) % 3][0].view,
                    a->outputs[(history + 2) % 3][1].view, a->outputs[history][0].view,
                    a->outputs[history][1].view, pg, p1 };
                VkImageView sixth_out[1] = { d->temp2[0].view };
                lsfg_gamma_write_set(g_ps.lsfg_delta_sets[instance][slot][7 + history],
                    buffer, border_edge, 2, sixth, 6, sixth_out, 1, true);
            }
            VkImageView sampled[6] = { d->temp1[0].view, d->temp1[1].view, d->temp1[2].view };
            VkImageView storage[3] = { d->temp2[0].view, d->temp2[1].view };
            lsfg_gamma_write_set(g_ps.lsfg_delta_sets[instance][slot][3], VK_NULL_HANDLE,
                &regular, 1, sampled, 3, storage, 2, false);
            sampled[0] = d->temp2[0].view; sampled[1] = d->temp2[1].view;
            storage[0] = d->temp1[0].view; storage[1] = d->temp1[1].view;
            lsfg_gamma_write_set(g_ps.lsfg_delta_sets[instance][slot][4], VK_NULL_HANDLE,
                &regular, 1, sampled, 2, storage, 2, false);
            sampled[0] = d->temp1[0].view; sampled[1] = d->temp1[1].view;
            storage[0] = d->temp2[0].view; storage[1] = d->temp2[1].view;
            lsfg_gamma_write_set(g_ps.lsfg_delta_sets[instance][slot][5], VK_NULL_HANDLE,
                &regular, 1, sampled, 2, storage, 2, false);
            VkSampler final_samplers[2] = { g_ps.lsfg_sampler, g_ps.lsfg_beta_sampler };
            sampled[0] = d->temp2[0].view; sampled[1] = d->temp2[1].view;
            sampled[2] = pg; sampled[3] = flow; storage[0] = d->output1.view;
            lsfg_gamma_write_set(g_ps.lsfg_delta_sets[instance][slot][6], buffer,
                final_samplers, 2, sampled, 4, storage, 1, true);
            sampled[0] = d->temp2[0].view; storage[0] = d->temp1[0].view;
            lsfg_gamma_write_set(g_ps.lsfg_delta_sets[instance][slot][10], VK_NULL_HANDLE,
                &regular, 1, sampled, 1, storage, 1, false);
            sampled[0] = d->temp1[0].view; storage[0] = d->temp2[0].view;
            lsfg_gamma_write_set(g_ps.lsfg_delta_sets[instance][slot][11], VK_NULL_HANDLE,
                &regular, 1, sampled, 1, storage, 1, false);
            sampled[0] = d->temp2[0].view; storage[0] = d->temp1[0].view;
            lsfg_gamma_write_set(g_ps.lsfg_delta_sets[instance][slot][12], VK_NULL_HANDLE,
                &regular, 1, sampled, 1, storage, 1, false);
            sampled[0] = d->temp1[0].view; sampled[1] = p2; storage[0] = d->output2.view;
            lsfg_gamma_write_set(g_ps.lsfg_delta_sets[instance][slot][13], buffer,
                final_samplers, 2, sampled, 2, storage, 1, true);
        }
    }
    g_ps.lsfg_delta_flow_w = g_ps.lsfg_flow_w;
    g_ps.lsfg_delta_flow_h = g_ps.lsfg_flow_h;
    g_ps.lsfg_delta_ready = true;
    hle_log("hakuX-lsfg", "Delta resources ready: three temporal levels, ten compute passes");
    return true;
fail:
    hle_log("hakuX-lsfg", "Delta resource allocation failed");
    lsfg_delta_drop();
    return false;
}
static bool lsfg_delta_dispatch(VkCommandBuffer cb, uint64_t serial, unsigned slot)
{
    if (!g_ps.lsfg_delta_ready || slot >= 6) return false;
    const unsigned history = (unsigned)(serial % 3u);
    for (unsigned instance = 0; instance < 3; ++instance) {
        const unsigned level = 2u - instance;
        LsfgDeltaLevel *d = &g_ps.lsfg_delta[instance];
        LsfgAlphaLevel *a = &g_ps.lsfg_alpha[level];
        LsfgAlphaImage *pg = instance == 0 ? NULL : &g_ps.lsfg_gamma[instance + 2].output;
        LsfgAlphaImage *p1 = instance == 0 ? NULL : &g_ps.lsfg_delta[instance - 1].output1;
        LsfgAlphaImage *p2 = instance == 0 ? NULL : &g_ps.lsfg_delta[instance - 1].output2;
        LsfgAlphaImage *flow = &g_ps.lsfg_beta_outputs[level];
        uint32_t w = MAX(1u, (MAX(1u, g_ps.lsfg_flow_w >> level) + 3u) >> 2);
        uint32_t h = MAX(1u, (MAX(1u, g_ps.lsfg_flow_h >> level) + 3u) >> 2);
        uint32_t gx = (w + 7u) >> 3, gy = (h + 7u) >> 3;
        if (!gx || !gy) return false;
        for (unsigned p = 0; p < 2; ++p) {
            lsfg_alpha_transition(cb, &a->outputs[(history + 2) % 3][p], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
            lsfg_alpha_transition(cb, &a->outputs[history][p], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        }
        if (pg) lsfg_alpha_transition(cb, pg, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        if (p1) lsfg_alpha_transition(cb, p1, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        if (p2) lsfg_alpha_transition(cb, p2, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        lsfg_alpha_transition(cb, flow, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        for (unsigned i = 0; i < 3; ++i) lsfg_alpha_transition(cb, &d->temp1[i], VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        for (unsigned i = 0; i < 2; ++i) lsfg_alpha_transition(cb, &d->temp2[i], VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        lsfg_alpha_transition(cb, &d->output1, VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        lsfg_alpha_transition(cb, &d->output2, VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);

        VkDescriptorSet set = g_ps.lsfg_delta_sets[instance][slot][history];
        T.vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, g_ps.lsfg_pipelines[2]);
        T.vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, g_ps.lsfg_pipeline_layouts[2], 0, 1, &set, 0, NULL);
        T.vkCmdDispatch(cb, gx, gy, 1);
        for (unsigned i = 0; i < 3; ++i) lsfg_alpha_transition(cb, &d->temp1[i], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        for (unsigned i = 0; i < 2; ++i) lsfg_alpha_transition(cb, &d->temp2[i], VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        set = g_ps.lsfg_delta_sets[instance][slot][3];
        T.vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, g_ps.lsfg_pipelines[4]);
        T.vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, g_ps.lsfg_pipeline_layouts[4], 0, 1, &set, 0, NULL);
        T.vkCmdDispatch(cb, gx, gy, 1);
        for (unsigned i = 0; i < 2; ++i) lsfg_alpha_transition(cb, &d->temp2[i], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        for (unsigned i = 0; i < 2; ++i) lsfg_alpha_transition(cb, &d->temp1[i], VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        set = g_ps.lsfg_delta_sets[instance][slot][4];
        T.vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, g_ps.lsfg_pipelines[5]);
        T.vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, g_ps.lsfg_pipeline_layouts[5], 0, 1, &set, 0, NULL);
        T.vkCmdDispatch(cb, gx, gy, 1);
        for (unsigned i = 0; i < 2; ++i) lsfg_alpha_transition(cb, &d->temp1[i], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        for (unsigned i = 0; i < 2; ++i) lsfg_alpha_transition(cb, &d->temp2[i], VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        set = g_ps.lsfg_delta_sets[instance][slot][5];
        T.vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, g_ps.lsfg_pipelines[6]);
        T.vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, g_ps.lsfg_pipeline_layouts[6], 0, 1, &set, 0, NULL);
        T.vkCmdDispatch(cb, gx, gy, 1);
        for (unsigned i = 0; i < 2; ++i) lsfg_alpha_transition(cb, &d->temp2[i], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        if (pg) lsfg_alpha_transition(cb, pg, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        lsfg_alpha_transition(cb, flow, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        lsfg_alpha_transition(cb, &d->output1, VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        set = g_ps.lsfg_delta_sets[instance][slot][6];
        T.vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, g_ps.lsfg_pipelines[7]);
        T.vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, g_ps.lsfg_pipeline_layouts[7], 0, 1, &set, 0, NULL);
        T.vkCmdDispatch(cb, gx, gy, 1);

        for (unsigned p = 0; p < 2; ++p) {
            lsfg_alpha_transition(cb, &a->outputs[(history + 2) % 3][p], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
            lsfg_alpha_transition(cb, &a->outputs[history][p], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        }
        if (pg) lsfg_alpha_transition(cb, pg, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        if (p1) lsfg_alpha_transition(cb, p1, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        lsfg_alpha_transition(cb, &d->temp2[0], VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        set = g_ps.lsfg_delta_sets[instance][slot][7 + history];
        T.vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, g_ps.lsfg_pipelines[3]);
        T.vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, g_ps.lsfg_pipeline_layouts[3], 0, 1, &set, 0, NULL);
        T.vkCmdDispatch(cb, gx, gy, 1);
        lsfg_alpha_transition(cb, &d->temp2[0], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        lsfg_alpha_transition(cb, &d->temp1[0], VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        set = g_ps.lsfg_delta_sets[instance][slot][10];
        T.vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, g_ps.lsfg_pipelines[16]);
        T.vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, g_ps.lsfg_pipeline_layouts[16], 0, 1, &set, 0, NULL);
        T.vkCmdDispatch(cb, gx, gy, 1);
        lsfg_alpha_transition(cb, &d->temp1[0], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        lsfg_alpha_transition(cb, &d->temp2[0], VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        set = g_ps.lsfg_delta_sets[instance][slot][11];
        T.vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, g_ps.lsfg_pipelines[17]);
        T.vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, g_ps.lsfg_pipeline_layouts[17], 0, 1, &set, 0, NULL);
        T.vkCmdDispatch(cb, gx, gy, 1);
        lsfg_alpha_transition(cb, &d->temp2[0], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        lsfg_alpha_transition(cb, &d->temp1[0], VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        set = g_ps.lsfg_delta_sets[instance][slot][12];
        T.vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, g_ps.lsfg_pipelines[18]);
        T.vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, g_ps.lsfg_pipeline_layouts[18], 0, 1, &set, 0, NULL);
        T.vkCmdDispatch(cb, gx, gy, 1);
        lsfg_alpha_transition(cb, &d->temp1[0], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        if (p2) lsfg_alpha_transition(cb, p2, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        lsfg_alpha_transition(cb, &d->output2, VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        set = g_ps.lsfg_delta_sets[instance][slot][13];
        T.vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, g_ps.lsfg_pipelines[19]);
        T.vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, g_ps.lsfg_pipeline_layouts[19], 0, 1, &set, 0, NULL);
        T.vkCmdDispatch(cb, gx, gy, 1);
    }
    return true;
}

static void lsfg_gamma_drop(void)
{
    for (unsigned level = 0; level < ARRAY_SIZE(g_ps.lsfg_gamma); ++level) {
        LsfgGammaLevel *g = &g_ps.lsfg_gamma[level];
        for (unsigned i = 0; i < ARRAY_SIZE(g->temp1); ++i)
            lsfg_alpha_image_drop(&g->temp1[i]);
        for (unsigned i = 0; i < ARRAY_SIZE(g->temp2); ++i)
            lsfg_alpha_image_drop(&g->temp2[i]);
        lsfg_alpha_image_drop(&g->output);
    }
    if (g_ps.lsfg_gamma_border_sampler)
        T.vkDestroySampler(g_hvk.dev, g_ps.lsfg_gamma_border_sampler, NULL);
    g_ps.lsfg_gamma_border_sampler = VK_NULL_HANDLE;
    for (unsigned slot = 0; slot < 6; ++slot) {
        for (unsigned first = 0; first < 2; ++first) {
            if (g_ps.lsfg_gamma_constants_map[slot][first])
                T.vkUnmapMemory(g_hvk.dev, g_ps.lsfg_gamma_constants_memory[slot][first]);
            if (g_ps.lsfg_gamma_constants_buffer[slot][first])
                T.vkDestroyBuffer(g_hvk.dev, g_ps.lsfg_gamma_constants_buffer[slot][first], NULL);
            if (g_ps.lsfg_gamma_constants_memory[slot][first])
                T.vkFreeMemory(g_hvk.dev, g_ps.lsfg_gamma_constants_memory[slot][first], NULL);
            g_ps.lsfg_gamma_constants_map[slot][first] = NULL;
            g_ps.lsfg_gamma_constants_buffer[slot][first] = VK_NULL_HANDLE;
            g_ps.lsfg_gamma_constants_memory[slot][first] = VK_NULL_HANDLE;
        }
    }
    g_ps.lsfg_gamma_flow_w = g_ps.lsfg_gamma_flow_h = 0;
    g_ps.lsfg_gamma_ready = false;
}

static void lsfg_gamma_write_set(VkDescriptorSet set, VkBuffer buffer,
                                 VkSampler const *samplers, unsigned sampler_count,
                                 VkImageView const *sampled, unsigned sampled_count,
                                 VkImageView const *storage, unsigned storage_count,
                                 bool constants)
{
    VkDescriptorImageInfo infos[11];
    VkDescriptorBufferInfo buffer_info = {
        .buffer = buffer, .offset = 0, .range = sizeof(LsfgConstants)
    };
    VkWriteDescriptorSet writes[11];
    memset(infos, 0, sizeof(infos));
    memset(writes, 0, sizeof(writes));
    unsigned n = 0;
    if (constants) {
        writes[n] = (VkWriteDescriptorSet) {
            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = set,
            .dstBinding = n, .descriptorCount = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, .pBufferInfo = &buffer_info
        };
        ++n;
    }
    for (unsigned i = 0; i < sampler_count; ++i, ++n) {
        infos[n].sampler = samplers[i];
        writes[n] = (VkWriteDescriptorSet) {
            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = set,
            .dstBinding = n, .descriptorCount = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_SAMPLER, .pImageInfo = &infos[n]
        };
    }
    for (unsigned i = 0; i < sampled_count; ++i, ++n) {
        infos[n] = (VkDescriptorImageInfo) {
            .imageView = sampled[i],
            .imageLayout = sampled[i] ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : VK_IMAGE_LAYOUT_UNDEFINED
        };
        writes[n] = (VkWriteDescriptorSet) {
            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = set,
            .dstBinding = n, .descriptorCount = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, .pImageInfo = &infos[n]
        };
    }
    for (unsigned i = 0; i < storage_count; ++i, ++n) {
        infos[n] = (VkDescriptorImageInfo) {
            .imageView = storage[i], .imageLayout = VK_IMAGE_LAYOUT_GENERAL
        };
        writes[n] = (VkWriteDescriptorSet) {
            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = set,
            .dstBinding = n, .descriptorCount = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, .pImageInfo = &infos[n]
        };
    }
    T.vkUpdateDescriptorSets(g_hvk.dev, n, writes, 0, NULL);
}

static bool lsfg_gamma_make_constants(unsigned slot, bool first_iter, int scale_percent)
{
    VkBuffer *buffer = &g_ps.lsfg_gamma_constants_buffer[slot][first_iter ? 1 : 0];
    VkDeviceMemory *memory = &g_ps.lsfg_gamma_constants_memory[slot][first_iter ? 1 : 0];
    uint8_t **mapped = &g_ps.lsfg_gamma_constants_map[slot][first_iter ? 1 : 0];
    VkBufferCreateInfo bci = {
        .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
        .size = sizeof(LsfgConstants),
        .usage = VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE
    };
    if (T.vkCreateBuffer(g_hvk.dev, &bci, NULL, buffer) != VK_SUCCESS) return false;
    VkMemoryRequirements mr;
    T.vkGetBufferMemoryRequirements(g_hvk.dev, *buffer, &mr);
    const int mt = hle_vk_memtype(mr.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, 0);
    if (mt < 0) return false;
    VkMemoryAllocateInfo mai = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize = mr.size, .memoryTypeIndex = (uint32_t)mt
    };
    if (T.vkAllocateMemory(g_hvk.dev, &mai, NULL, memory) != VK_SUCCESS ||
        T.vkBindBufferMemory(g_hvk.dev, *buffer, *memory, 0) != VK_SUCCESS ||
        T.vkMapMemory(g_hvk.dev, *memory, 0, sizeof(LsfgConstants), 0, (void **)mapped) != VK_SUCCESS)
        return false;
    static const float timestamps[6] = { 0.5f, 1.0f/3.0f, 2.0f/3.0f, 0.25f, 0.5f, 0.75f };
    const LsfgConstants constants = {
        .input_offset = { 0, 0 },
        .first_iter = first_iter ? 1u : 0u,
        .first_iter_s = 0,
        .advanced_color_kind = 0,
        .hdr_support = 0,
        .resolution_inv_scale = 100.0f / (float)scale_percent,
        .timestamp = timestamps[slot],
        .ui_threshold = 0.5f,
        .padding = { 0, 0, 0 }
    };
    memcpy(*mapped, &constants, sizeof(constants));
    return true;
}

static bool lsfg_gamma_ensure(void)
{
    if (!g_ps.lsfg_beta_ready || !g_ps.lsfg_alpha_ready ||
        !g_ps.lsfg_gamma_sets_allocated || !g_ps.lsfg_pipelines_ready) return false;
    if (g_ps.lsfg_gamma_ready && g_ps.lsfg_gamma_flow_w == g_ps.lsfg_flow_w &&
        g_ps.lsfg_gamma_flow_h == g_ps.lsfg_flow_h) return true;
    if (g_ps.lsfg_gamma_ready) {
        idle();
        lsfg_gamma_drop();
    }

    VkSamplerCreateInfo border_sci = {
        .sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO,
        .magFilter = VK_FILTER_LINEAR, .minFilter = VK_FILTER_LINEAR,
        .mipmapMode = VK_SAMPLER_MIPMAP_MODE_LINEAR,
        .addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER,
        .addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER,
        .addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER,
        .maxAnisotropy = 1.0f,
        .borderColor = VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE,
        .minLod = 0.0f, .maxLod = 0.0f
    };
    if (T.vkCreateSampler(g_hvk.dev, &border_sci, NULL,
                          &g_ps.lsfg_gamma_border_sampler) != VK_SUCCESS) goto fail;

    VkFormatProperties fp;
    vkGetPhysicalDeviceFormatProperties(g_hvk.phys, VK_FORMAT_R16G16B16A16_SFLOAT, &fp);
    const VkFormatFeatureFlags required = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT |
                                           VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT;
    if ((fp.optimalTilingFeatures & required) != required) {
        hle_log("hakuX-lsfg", "RGBA16F lacks sampled/storage support; Gamma disabled");
        goto fail;
    }

    for (unsigned i = 0; i < ARRAY_SIZE(g_ps.lsfg_gamma); ++i) {
        const unsigned level = 6u - i;
        const uint32_t level_w = MAX(1u, g_ps.lsfg_flow_w >> level);
        const uint32_t level_h = MAX(1u, g_ps.lsfg_flow_h >> level);
        const uint32_t half_w = MAX(1u, (level_w + 1u) >> 1);
        const uint32_t half_h = MAX(1u, (level_h + 1u) >> 1);
        const uint32_t w = MAX(1u, (half_w + 1u) >> 1);
        const uint32_t h = MAX(1u, (half_h + 1u) >> 1);
        LsfgGammaLevel *g = &g_ps.lsfg_gamma[i];
        for (unsigned n = 0; n < 3; ++n)
            if (!lsfg_beta_image_make(&g->temp1[n], w, h, VK_FORMAT_R8G8B8A8_UNORM)) goto fail;
        for (unsigned n = 0; n < 2; ++n)
            if (!lsfg_beta_image_make(&g->temp2[n], w, h, VK_FORMAT_R8G8B8A8_UNORM)) goto fail;
        if (!lsfg_beta_image_make(&g->output, w, h, VK_FORMAT_R16G16B16A16_SFLOAT)) goto fail;
    }

    const int scale = g_ps.lsfg_flow_scale < 25 ? 25 :
                      g_ps.lsfg_flow_scale > 100 ? 100 : g_ps.lsfg_flow_scale;
    for (unsigned slot = 0; slot < 6; ++slot) {
        if (!lsfg_gamma_make_constants(slot, false, scale) ||
            !lsfg_gamma_make_constants(slot, true, scale)) goto fail;
    }

    for (unsigned i = 0; i < ARRAY_SIZE(g_ps.lsfg_gamma); ++i) {
        const unsigned level = 6u - i;
        LsfgGammaLevel *g = &g_ps.lsfg_gamma[i];
        LsfgAlphaLevel *alpha = &g_ps.lsfg_alpha[level];
        const VkImageView previous = i ? g_ps.lsfg_gamma[i - 1].output.view : VK_NULL_HANDLE;
        const VkImageView flow = g_ps.lsfg_beta_outputs[level < 6 ? level : 5].view;
        for (unsigned slot = 0; slot < 6; ++slot) {
            const VkBuffer buffer = g_ps.lsfg_gamma_constants_buffer[slot][i == 0 ? 1 : 0];
            for (unsigned history = 0; history < 3; ++history) {
                VkImageView sampled[5] = {
                    alpha->outputs[(history + 2) % 3][0].view,
                    alpha->outputs[(history + 2) % 3][1].view,
                    alpha->outputs[history][0].view,
                    alpha->outputs[history][1].view,
                    previous
                };
                VkImageView storage[3] = {
                    g->temp1[0].view, g->temp1[1].view, g->temp1[2].view
                };
                VkSampler samplers[2] = { g_ps.lsfg_gamma_border_sampler, g_ps.lsfg_beta_sampler };
                lsfg_gamma_write_set(g_ps.lsfg_gamma_sets[i][slot][history], buffer,
                                     samplers, 2, sampled, 5, storage, 3, true);
            }
            VkImageView sampled1[3] = {
                g->temp1[0].view, g->temp1[1].view, g->temp1[2].view
            };
            VkImageView storage2[2] = { g->temp2[0].view, g->temp2[1].view };
            lsfg_gamma_write_set(g_ps.lsfg_gamma_sets[i][slot][3], VK_NULL_HANDLE,
                                 &g_ps.lsfg_sampler, 1, sampled1, 3, storage2, 2, false);
            VkImageView sampled2[2] = { g->temp2[0].view, g->temp2[1].view };
            VkImageView storage12[2] = { g->temp1[0].view, g->temp1[1].view };
            lsfg_gamma_write_set(g_ps.lsfg_gamma_sets[i][slot][4], VK_NULL_HANDLE,
                                 &g_ps.lsfg_sampler, 1, sampled2, 2, storage12, 2, false);
            VkImageView sampled12[2] = { g->temp1[0].view, g->temp1[1].view };
            lsfg_gamma_write_set(g_ps.lsfg_gamma_sets[i][slot][5], VK_NULL_HANDLE,
                                 &g_ps.lsfg_sampler, 1, sampled12, 2, storage2, 2, false);
            VkImageView sampled_final[4] = {
                g->temp2[0].view, g->temp2[1].view, previous, flow
            };
            VkImageView output[1] = { g->output.view };
            VkSampler final_samplers[2] = { g_ps.lsfg_sampler, g_ps.lsfg_beta_sampler };
            lsfg_gamma_write_set(g_ps.lsfg_gamma_sets[i][slot][6], buffer,
                                 final_samplers, 2, sampled_final, 4, output, 1, true);
        }
    }
    g_ps.lsfg_gamma_flow_w = g_ps.lsfg_flow_w;
    g_ps.lsfg_gamma_flow_h = g_ps.lsfg_flow_h;
    g_ps.lsfg_gamma_ready = true;
    hle_log("hakuX-lsfg", "Gamma resources ready: seven motion levels and six generation slots");
    return true;

fail:
    hle_log("hakuX-lsfg", "Gamma intermediate/output image or constants allocation failed");
    lsfg_gamma_drop();
    return false;
}

static bool lsfg_gamma_dispatch(VkCommandBuffer cb, uint64_t frame_serial, unsigned generation_slot)
{
    if (!g_ps.lsfg_gamma_ready || generation_slot >= 6) return false;
    const unsigned history = (unsigned)(frame_serial % 3u);
    const unsigned previous_history = (unsigned)((frame_serial + 2u) % 3u);
    for (unsigned i = 0; i < ARRAY_SIZE(g_ps.lsfg_gamma); ++i) {
        const unsigned level = 6u - i;
        LsfgGammaLevel *g = &g_ps.lsfg_gamma[i];
        LsfgAlphaLevel *alpha = &g_ps.lsfg_alpha[level];
        for (unsigned slot = 0; slot < 3; ++slot)
            for (unsigned pair = 0; pair < 2; ++pair)
                lsfg_alpha_transition(cb, &alpha->outputs[slot][pair],
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        if (i) lsfg_alpha_transition(cb, &g_ps.lsfg_gamma[i - 1].output,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        LsfgAlphaImage *flow = &g_ps.lsfg_beta_outputs[level < 6 ? level : 5];
        lsfg_alpha_transition(cb, flow, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                              VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        for (unsigned n = 0; n < 3; ++n)
            lsfg_alpha_transition(cb, &g->temp1[n], VK_IMAGE_LAYOUT_GENERAL,
                                  VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        for (unsigned n = 0; n < 2; ++n)
            lsfg_alpha_transition(cb, &g->temp2[n], VK_IMAGE_LAYOUT_GENERAL,
                                  VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        lsfg_alpha_transition(cb, &g->output, VK_IMAGE_LAYOUT_GENERAL,
                              VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);

        VkDescriptorSet set = g_ps.lsfg_gamma_sets[i][generation_slot][history];
        T.vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, g_ps.lsfg_pipelines[2]);
        T.vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE,
            g_ps.lsfg_pipeline_layouts[2], 0, 1, &set, 0, NULL);
        const uint32_t level_w = MAX(1u, g_ps.lsfg_flow_w >> level);
        const uint32_t level_h = MAX(1u, g_ps.lsfg_flow_h >> level);
        const uint32_t w = MAX(1u, (level_w + 3u) >> 2);
        const uint32_t h = MAX(1u, (level_h + 3u) >> 2);
        const uint32_t gx = (w + 7u) >> 3, gy = (h + 7u) >> 3;
        if (!gx || !gy) return false;
        T.vkCmdDispatch(cb, gx, gy, 1);

        for (unsigned n = 0; n < 3; ++n)
            lsfg_alpha_transition(cb, &g->temp1[n], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                                  VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        for (unsigned n = 0; n < 2; ++n)
            lsfg_alpha_transition(cb, &g->temp2[n], VK_IMAGE_LAYOUT_GENERAL,
                                  VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        set = g_ps.lsfg_gamma_sets[i][generation_slot][3];
        T.vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, g_ps.lsfg_pipelines[4]);
        T.vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE,
            g_ps.lsfg_pipeline_layouts[4], 0, 1, &set, 0, NULL);
        T.vkCmdDispatch(cb, gx, gy, 1);

        for (unsigned n = 0; n < 2; ++n)
            lsfg_alpha_transition(cb, &g->temp2[n], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                                  VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        for (unsigned n = 0; n < 2; ++n)
            lsfg_alpha_transition(cb, &g->temp1[n], VK_IMAGE_LAYOUT_GENERAL,
                                  VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        set = g_ps.lsfg_gamma_sets[i][generation_slot][4];
        T.vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, g_ps.lsfg_pipelines[5]);
        T.vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE,
            g_ps.lsfg_pipeline_layouts[5], 0, 1, &set, 0, NULL);
        T.vkCmdDispatch(cb, gx, gy, 1);

        for (unsigned n = 0; n < 2; ++n)
            lsfg_alpha_transition(cb, &g->temp1[n], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                                  VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        for (unsigned n = 0; n < 2; ++n)
            lsfg_alpha_transition(cb, &g->temp2[n], VK_IMAGE_LAYOUT_GENERAL,
                                  VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        set = g_ps.lsfg_gamma_sets[i][generation_slot][5];
        T.vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, g_ps.lsfg_pipelines[6]);
        T.vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE,
            g_ps.lsfg_pipeline_layouts[6], 0, 1, &set, 0, NULL);
        T.vkCmdDispatch(cb, gx, gy, 1);

        for (unsigned n = 0; n < 2; ++n)
            lsfg_alpha_transition(cb, &g->temp2[n], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                                  VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        if (i) lsfg_alpha_transition(cb, &g_ps.lsfg_gamma[i - 1].output,
            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        set = g_ps.lsfg_gamma_sets[i][generation_slot][6];
        T.vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, g_ps.lsfg_pipelines[7]);
        T.vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE,
            g_ps.lsfg_pipeline_layouts[7], 0, 1, &set, 0, NULL);
        T.vkCmdDispatch(cb, gx, gy, 1);
    }
    (void)previous_history;
    return true;
}


static void lsfg_alpha_drop(void)
{
    for (unsigned level = 0; level < ARRAY_SIZE(g_ps.lsfg_alpha); ++level) {
        LsfgAlphaLevel *a = &g_ps.lsfg_alpha[level];
        lsfg_alpha_image_drop(&a->temp1);
        lsfg_alpha_image_drop(&a->temp2);
        for (unsigned i = 0; i < 2; ++i) {
            lsfg_alpha_image_drop(&a->temp3[i]);
            for (unsigned slot = 0; slot < 3; ++slot)
                lsfg_alpha_image_drop(&a->outputs[slot][i]);
        }
    }
    g_ps.lsfg_alpha_w = g_ps.lsfg_alpha_h = 0;
    g_ps.lsfg_alpha_ready = false;
}

static void lsfg_alpha_write_set(VkDescriptorSet set, VkImageView const *sampled,
                                 unsigned sampled_count, VkImageView const *storage,
                                 unsigned storage_count)
{
    VkDescriptorImageInfo infos[6];
    VkWriteDescriptorSet writes[6];
    memset(infos, 0, sizeof(infos));
    memset(writes, 0, sizeof(writes));
    infos[0].sampler = g_ps.lsfg_sampler;
    writes[0] = (VkWriteDescriptorSet) {
        .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = set,
        .dstBinding = 0, .descriptorCount = 1,
        .descriptorType = VK_DESCRIPTOR_TYPE_SAMPLER, .pImageInfo = &infos[0]
    };
    unsigned n = 1;
    for (unsigned i = 0; i < sampled_count; ++i, ++n) {
        infos[n] = (VkDescriptorImageInfo) {
            .imageView = sampled[i], .imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
        };
        writes[n] = (VkWriteDescriptorSet) {
            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = set,
            .dstBinding = n, .descriptorCount = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, .pImageInfo = &infos[n]
        };
    }
    for (unsigned i = 0; i < storage_count; ++i, ++n) {
        infos[n] = (VkDescriptorImageInfo) {
            .imageView = storage[i], .imageLayout = VK_IMAGE_LAYOUT_GENERAL
        };
        writes[n] = (VkWriteDescriptorSet) {
            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = set,
            .dstBinding = n, .descriptorCount = 1,
            .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, .pImageInfo = &infos[n]
        };
    }
    T.vkUpdateDescriptorSets(g_hvk.dev, n, writes, 0, NULL);
}

static bool lsfg_alpha_ensure(void)
{
    if (!g_ps.lsfg_alpha_sets_allocated || !g_ps.lsfg_flow_ready ||
        !g_ps.lsfg_mipmap_descriptors_ready) return false;
    if (g_ps.lsfg_alpha_ready && g_ps.lsfg_alpha_w == g_ps.lsfg_flow_w &&
        g_ps.lsfg_alpha_h == g_ps.lsfg_flow_h) return true;
    if (g_ps.lsfg_alpha_ready) {
        idle();
        lsfg_alpha_drop();
    }
    VkFormatProperties fp;
    vkGetPhysicalDeviceFormatProperties(g_hvk.phys, VK_FORMAT_R8G8B8A8_UNORM, &fp);
    const VkFormatFeatureFlags required = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT |
                                           VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT;
    if ((fp.optimalTilingFeatures & required) != required) {
        hle_log("hakuX-lsfg", "RGBA8 lacks sampled/storage support; Alpha disabled");
        return false;
    }
    for (unsigned level = 0; level < ARRAY_SIZE(g_ps.lsfg_alpha); ++level) {
        const uint32_t w = MAX(1u, g_ps.lsfg_flow_w >> level);
        const uint32_t h = MAX(1u, g_ps.lsfg_flow_h >> level);
        const uint32_t half_w = MAX(1u, (w + 1u) >> 1);
        const uint32_t half_h = MAX(1u, (h + 1u) >> 1);
        const uint32_t quarter_w = MAX(1u, (half_w + 1u) >> 1);
        const uint32_t quarter_h = MAX(1u, (half_h + 1u) >> 1);
        LsfgAlphaLevel *a = &g_ps.lsfg_alpha[level];
        bool ok = lsfg_alpha_image_make(&a->temp1, half_w, half_h) &&
                  lsfg_alpha_image_make(&a->temp2, half_w, half_h);
        for (unsigned i = 0; ok && i < 2; ++i) {
            ok = lsfg_alpha_image_make(&a->temp3[i], quarter_w, quarter_h);
            for (unsigned slot = 0; ok && slot < 3; ++slot)
                ok = lsfg_alpha_image_make(&a->outputs[slot][i], quarter_w, quarter_h);
        }
        if (!ok) {
            hle_log("hakuX-lsfg", "Alpha image allocation failed at level %u (%ux%u)",
                    level, quarter_w, quarter_h);
            lsfg_alpha_drop();
            return false;
        }
    }
    for (unsigned level = 0; level < ARRAY_SIZE(g_ps.lsfg_alpha); ++level) {
        LsfgAlphaLevel *a = &g_ps.lsfg_alpha[level];
        VkImageView sampled[2], storage[2];
        sampled[0] = g_ps.lsfg_flow_view[level];
        storage[0] = a->temp1.view;
        lsfg_alpha_write_set(g_ps.lsfg_alpha_sets[level][0], sampled, 1, storage, 1);
        sampled[0] = a->temp1.view;
        storage[0] = a->temp2.view;
        lsfg_alpha_write_set(g_ps.lsfg_alpha_sets[level][1], sampled, 1, storage, 1);
        sampled[0] = a->temp2.view;
        storage[0] = a->temp3[0].view;
        storage[1] = a->temp3[1].view;
        lsfg_alpha_write_set(g_ps.lsfg_alpha_sets[level][2], sampled, 1, storage, 2);
        sampled[0] = a->temp3[0].view;
        sampled[1] = a->temp3[1].view;
        for (unsigned slot = 0; slot < 3; ++slot) {
            storage[0] = a->outputs[slot][0].view;
            storage[1] = a->outputs[slot][1].view;
            lsfg_alpha_write_set(g_ps.lsfg_alpha_sets[level][3 + slot], sampled, 2, storage, 2);
        }
    }
    g_ps.lsfg_alpha_w = g_ps.lsfg_flow_w;
    g_ps.lsfg_alpha_h = g_ps.lsfg_flow_h;
    g_ps.lsfg_alpha_ready = true;
    hle_log("hakuX-lsfg", "allocated and bound Alpha resources for seven mip levels");
    return true;
}

static void lsfg_alpha_transition(VkCommandBuffer cb, LsfgAlphaImage *image,
                                  VkImageLayout target, VkAccessFlags2 dst_access)
{
    const VkImageLayout old = image->layout;
    const VkAccessFlags2 src_access = old == VK_IMAGE_LAYOUT_UNDEFINED ? 0 :
        old == VK_IMAGE_LAYOUT_GENERAL ? VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT :
                                         VK_ACCESS_2_SHADER_SAMPLED_READ_BIT;
    barrier(cb, image->image, old, target,
            old == VK_IMAGE_LAYOUT_UNDEFINED ? VK_PIPELINE_STAGE_2_NONE :
                                               VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
            src_access, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, dst_access);
    image->layout = target;
}

static bool lsfg_alpha_dispatch(VkCommandBuffer cb, uint64_t frame_serial)
{
    if (!g_ps.lsfg_alpha_ready || !g_ps.lsfg_pipelines_ready) return false;
    const unsigned slot = (unsigned)(frame_serial % 3u);
    /* Alpha stages 0..2 produce the two quarter-resolution temporal inputs. */
    for (unsigned level = 0; level < 7; ++level) {
        LsfgAlphaLevel *a = &g_ps.lsfg_alpha[level];
        barrier(cb, g_ps.lsfg_flow[level], VK_IMAGE_LAYOUT_GENERAL,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        g_ps.lsfg_flow_layout[level] = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        lsfg_alpha_transition(cb, &a->temp1, VK_IMAGE_LAYOUT_GENERAL,
                              VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        T.vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, g_ps.lsfg_pipelines[12]);
        VkDescriptorSet set = g_ps.lsfg_alpha_sets[level][0];
        T.vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE,
                                  g_ps.lsfg_pipeline_layouts[12], 0, 1, &set, 0, NULL);
        T.vkCmdDispatch(cb, (a->temp1.view ? (MAX(1u, ((MAX(1u, g_ps.lsfg_flow_w >> level) + 1u) >> 1)) + 7u) >> 3 : 0),
                        (MAX(1u, ((MAX(1u, g_ps.lsfg_flow_h >> level) + 1u) >> 1)) + 7u) >> 3, 1);
    }
    for (unsigned level = 0; level < 7; ++level) {
        LsfgAlphaLevel *a = &g_ps.lsfg_alpha[level];
        lsfg_alpha_transition(cb, &a->temp1, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                              VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        lsfg_alpha_transition(cb, &a->temp2, VK_IMAGE_LAYOUT_GENERAL,
                              VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        T.vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, g_ps.lsfg_pipelines[13]);
        VkDescriptorSet set = g_ps.lsfg_alpha_sets[level][1];
        T.vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE,
                                  g_ps.lsfg_pipeline_layouts[13], 0, 1, &set, 0, NULL);
        const uint32_t w = MAX(1u, (MAX(1u, g_ps.lsfg_flow_w >> level) + 1u) >> 1);
        const uint32_t h = MAX(1u, (MAX(1u, g_ps.lsfg_flow_h >> level) + 1u) >> 1);
        T.vkCmdDispatch(cb, (w + 7u) >> 3, (h + 7u) >> 3, 1);
    }
    for (unsigned level = 0; level < 7; ++level) {
        LsfgAlphaLevel *a = &g_ps.lsfg_alpha[level];
        lsfg_alpha_transition(cb, &a->temp2, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                              VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        for (unsigned i = 0; i < 2; ++i)
            lsfg_alpha_transition(cb, &a->temp3[i], VK_IMAGE_LAYOUT_GENERAL,
                                  VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        T.vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, g_ps.lsfg_pipelines[14]);
        VkDescriptorSet set = g_ps.lsfg_alpha_sets[level][2];
        T.vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE,
                                  g_ps.lsfg_pipeline_layouts[14], 0, 1, &set, 0, NULL);
        const uint32_t w = MAX(1u, (MAX(1u, g_ps.lsfg_flow_w >> level) + 3u) >> 2);
        const uint32_t h = MAX(1u, (MAX(1u, g_ps.lsfg_flow_h >> level) + 3u) >> 2);
        T.vkCmdDispatch(cb, (w + 7u) >> 3, (h + 7u) >> 3, 1);
    }
    for (unsigned level = 0; level < 7; ++level) {
        LsfgAlphaLevel *a = &g_ps.lsfg_alpha[level];
        for (unsigned i = 0; i < 2; ++i)
            lsfg_alpha_transition(cb, &a->temp3[i], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                                  VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
        for (unsigned i = 0; i < 2; ++i)
            lsfg_alpha_transition(cb, &a->outputs[slot][i], VK_IMAGE_LAYOUT_GENERAL,
                                  VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
        T.vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, g_ps.lsfg_pipelines[15]);
        VkDescriptorSet set = g_ps.lsfg_alpha_sets[level][3 + slot];
        T.vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE,
                                  g_ps.lsfg_pipeline_layouts[15], 0, 1, &set, 0, NULL);
        const uint32_t w = MAX(1u, (MAX(1u, g_ps.lsfg_flow_w >> level) + 3u) >> 2);
        const uint32_t h = MAX(1u, (MAX(1u, g_ps.lsfg_flow_h >> level) + 3u) >> 2);
        T.vkCmdDispatch(cb, (w + 7u) >> 3, (h + 7u) >> 3, 1);
    }
    return true;
}

static int g_nis_status[14];
static void nis_status(int index, int value)
{
    __atomic_store_n(&g_nis_status[index], value, __ATOMIC_RELAXED);
}
void hle_present_nis_status(int status[14])
{
    for (unsigned i = 0; i < 14; i++) status[i] = __atomic_load_n(&g_nis_status[i], __ATOMIC_RELAXED);
}

/* ---- shaders ---- */

/* rect: x0, y0, x1, y1 in the window's clip space; rot: that space turned
 * into the image's (the panel's orientation: pre-rotated), x' = rot.x x +
 * rot.y y, y' = rot.z x + rot.w y */
static const char k_vs[] =
    "#version 450\n"
    "layout(push_constant) uniform P { vec4 rect; vec4 extra; vec4 rot; } pc;\n"
    "layout(location = 0) out vec2 v_uv;\n"
    "void main() {\n"
    "  vec2 c = vec2(gl_VertexIndex & 1, gl_VertexIndex >> 1);\n"
    "  v_uv = c;\n"
    "  vec2 p = mix(pc.rect.xy, pc.rect.zw, c);\n"
    "  gl_Position = vec4(pc.rot.x * p.x + pc.rot.y * p.y, pc.rot.z * p.x + pc.rot.w * p.y, 0.0, 1.0);\n"
    "}\n";
/* every fragment shader's start: its inputs and the gamma ramp (768 bytes,
 * a channel's 256 after another's, as gamma.c's) */
static const char k_fs_head[] =
    "#version 450\n"
    "layout(push_constant) uniform P { vec4 rect; vec4 extra; vec4 rot; } pc;\n"
    "layout(set = 0, binding = 0) uniform sampler2D u_tex;\n"
    "layout(set = 0, binding = 1, std140) uniform G { uvec4 w[48]; } g;\n"
    "layout(location = 0) in vec2 v_uv;\n"
    "layout(location = 0) out vec4 o;\n"
    "uint lut(uint i) {\n"
    "  uint word = g.w[i >> 4][(i >> 2) & 3u];\n"
    "  return (word >> ((i & 3u) * 8u)) & 0xFFu;\n"
    "}\n"
    "vec4 ramp(vec3 c) {\n"
    "  uvec3 v = uvec3(clamp(c, 0.0, 1.0) * 255.0 + 0.5);\n"
    "  return vec4(float(lut(v.r)), float(lut(256u + v.g)), float(lut(512u + v.b)), 255.0) / 255.0;\n"
    "}\n";
static const char k_fs_bilinear[] =
    "void main() {\n"
    "  o = ramp(texture(u_tex, v_uv).rgb);\n"
    "}\n";
/* Down: each window pixel covers extra.xy frame pixels (at most 6 a side
 * counted), each weighted by how much of it it covers: a box filter (on an
 * axis scaled up, 1 pixel wide: the same as bilinear). */
static const char k_fs_area[] =
    "void main() {\n"
    "  vec2 size = vec2(textureSize(u_tex, 0));\n"
    "  vec2 r = clamp(pc.extra.xy, vec2(1.0), vec2(6.0));\n"
    "  vec2 lo = v_uv * size - 0.5 * r, hi = lo + r;\n"
    "  ivec2 i0 = ivec2(floor(lo)), i1 = ivec2(ceil(hi)) - 1, top = ivec2(size) - 1;\n"
    "  vec3 sum = vec3(0.0);\n"
    "  float wsum = 0.0;\n"
    "  for (int y = i0.y; y <= i1.y; y++) {\n"
    "    float wy = min(hi.y, float(y + 1)) - max(lo.y, float(y));\n"
    "    for (int x = i0.x; x <= i1.x; x++) {\n"
    "      float w = (min(hi.x, float(x + 1)) - max(lo.x, float(x))) * wy;\n"
    "      sum += w * texelFetch(u_tex, clamp(ivec2(x, y), ivec2(0), top), 0).rgb;\n"
    "      wsum += w;\n"
    "    }\n"
    "  }\n"
    "  o = ramp(sum / wsum);\n"
    "}\n";
/* FXAA 3.11 Quality as PCSX2 tunes it (edges only: no subpixel blur), into
 * a frame-sized image: no ramp yet. */
static const char k_fs_fxaa[] =
    "const float EDGE_THRESHOLD = 0.063, EDGE_THRESHOLD_MIN = 0.0, SUBPIX = 0.0;\n"
    "const float STEP[12] = float[12](1.5, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 2.0, 4.0, 8.0, 8.0);\n"
    "float luma(vec3 c) { return dot(c, vec3(0.2126729, 0.7151522, 0.0721750)); }\n"
    "float luma_at(vec2 p) { return luma(textureLod(u_tex, p, 0.0).rgb); }\n"
    "#define LUMA_OFF(x, y) luma(textureLodOffset(u_tex, posM, 0.0, ivec2(x, y)).rgb)\n"
    "void main() {\n"
    "  vec2 rcp = 1.0 / vec2(textureSize(u_tex, 0));\n"
    "  vec2 posM = v_uv;\n"
    "  vec3 rgbM = textureLod(u_tex, posM, 0.0).rgb;\n"
    "  float lumaM = luma(rgbM);\n"
    "  float lumaS = LUMA_OFF(0, 1), lumaE = LUMA_OFF(1, 0), lumaN = LUMA_OFF(0, -1), lumaW = LUMA_OFF(-1, 0);\n"
    "  float rangeMax = max(max(lumaN, lumaW), max(lumaE, max(lumaS, lumaM)));\n"
    "  float rangeMin = min(min(lumaN, lumaW), min(lumaE, min(lumaS, lumaM)));\n"
    "  float range = rangeMax - rangeMin;\n"
    "  if (range < max(EDGE_THRESHOLD_MIN, rangeMax * EDGE_THRESHOLD)) {\n"
    "    o = vec4(rgbM, 1.0);\n"
    "    return;\n"
    "  }\n"
    "  float lumaNW = LUMA_OFF(-1, -1), lumaSE = LUMA_OFF(1, 1), lumaNE = LUMA_OFF(1, -1), lumaSW = LUMA_OFF(-1, 1);\n"
    "  float lumaNS = lumaN + lumaS, lumaWE = lumaW + lumaE;\n"
    "  float edgeHorz = abs(-2.0 * lumaW + lumaNW + lumaSW) + 2.0 * abs(-2.0 * lumaM + lumaNS) +\n"
    "                   abs(-2.0 * lumaE + lumaNE + lumaSE);\n"
    "  float edgeVert = abs(-2.0 * lumaS + lumaSW + lumaSE) + 2.0 * abs(-2.0 * lumaM + lumaWE) +\n"
    "                   abs(-2.0 * lumaN + lumaNW + lumaNE);\n"
    "  bool horzSpan = edgeHorz >= edgeVert;\n"
    "  float subpixA = 2.0 * (lumaNS + lumaWE) + (lumaNW + lumaSW + lumaNE + lumaSE);\n"
    "  float lengthSign = horzSpan ? rcp.y : rcp.x;\n"
    "  if (!horzSpan) {\n"
    "    lumaN = lumaW;\n"
    "    lumaS = lumaE;\n"
    "  }\n"
    "  float subpixB = subpixA * (1.0 / 12.0) - lumaM;\n"
    "  float gradientN = lumaN - lumaM, gradientS = lumaS - lumaM;\n"
    "  float lumaNN = lumaN + lumaM, lumaSS = lumaS + lumaM;\n"
    "  bool pairN = abs(gradientN) >= abs(gradientS);\n"
    "  float gradient = max(abs(gradientN), abs(gradientS));\n"
    "  if (pairN) lengthSign = -lengthSign;\n"
    "  float subpixC = clamp(abs(subpixB) / range, 0.0, 1.0);\n"
    "  vec2 offNP = horzSpan ? vec2(rcp.x, 0.0) : vec2(0.0, rcp.y);\n"
    "  vec2 posB = posM;\n"
    "  if (horzSpan) posB.y += lengthSign * 0.5; else posB.x += lengthSign * 0.5;\n"
    "  vec2 posN = posB - offNP, posP = posB + offNP;\n"
    "  float subpixF = (-2.0 * subpixC + 3.0) * subpixC * subpixC;\n"
    "  if (!pairN) lumaNN = lumaSS;\n"
    "  float gradientScaled = gradient * 0.25;\n"
    "  bool lumaMLTZero = lumaM - lumaNN * 0.5 < 0.0;\n"
    "  float lumaEndN = luma_at(posN) - lumaNN * 0.5, lumaEndP = luma_at(posP) - lumaNN * 0.5;\n"
    "  bool doneN = abs(lumaEndN) >= gradientScaled, doneP = abs(lumaEndP) >= gradientScaled;\n"
    "  if (!doneN) posN -= offNP * STEP[0];\n"
    "  if (!doneP) posP += offNP * STEP[0];\n"
    "  for (int i = 1; i < 12 && !(doneN && doneP); i++) {\n"
    "    if (!doneN) lumaEndN = luma_at(posN) - lumaNN * 0.5;\n"
    "    if (!doneP) lumaEndP = luma_at(posP) - lumaNN * 0.5;\n"
    "    doneN = abs(lumaEndN) >= gradientScaled;\n"
    "    doneP = abs(lumaEndP) >= gradientScaled;\n"
    "    if (!doneN) posN -= offNP * STEP[i];\n"
    "    if (!doneP) posP += offNP * STEP[i];\n"
    "  }\n"
    "  float dstN = horzSpan ? posM.x - posN.x : posM.y - posN.y;\n"
    "  float dstP = horzSpan ? posP.x - posM.x : posP.y - posM.y;\n"
    "  bool goodSpan = dstN < dstP ? (lumaEndN < 0.0) != lumaMLTZero : (lumaEndP < 0.0) != lumaMLTZero;\n"
    "  float pixelOffset = goodSpan ? 0.5 - min(dstN, dstP) / (dstN + dstP) : 0.0;\n"
    "  float offset = max(pixelOffset, subpixF * subpixF * SUBPIX);\n"
    "  if (horzSpan) posM.y += offset * lengthSign; else posM.x += offset * lengthSign;\n"
    "  o = vec4(textureLod(u_tex, posM, 0.0).rgb, 1.0);\n"
    "}\n";
/* Snapdragon Game Super Resolution 1: Qualcomm's sgsr1_shader_mobile.frag
 * (RGBA mode: green as luma), with our input, output and ramp; ViewportInfo
 * is extra (1/w, 1/h, w, h of the frame). */
static const char k_fs_sgsr[] =
    "//====================================================================================\n"
    "//         Copyright (c) 2025, Qualcomm Innovation Center, Inc. All rights reserved.\n"
    "//                         SPDX-License-Identifier: BSD-3-Clause\n"
    "//====================================================================================\n"
    "#define OperationMode 1\n"
    "#define EdgeThreshold 8.0/255.0\n"
    "#define EdgeSharpness 2.0\n"
    "float fastLanczos2(float x) {\n"
    "  float wA = x - 4.0;\n"
    "  float wB = x * wA - wA;\n"
    "  wA *= wA;\n"
    "  return wB * wA;\n"
    "}\n"
    "vec2 weightY(float dx, float dy, float c, float std) {\n"
    "  float x = ((dx * dx) + (dy * dy)) * 0.55 + clamp(abs(c) * std, 0.0, 1.0);\n"
    "  float w = fastLanczos2(x);\n"
    "  return vec2(w, w * c);\n"
    "}\n"
    "void main() {\n"
    "  const int mode = OperationMode;\n"
    "  float edgeThreshold = EdgeThreshold;\n"
    "  float edgeSharpness = EdgeSharpness;\n"
    "  vec4 ViewportInfo = pc.extra;\n"
    "  vec4 color;\n"
    "  color.xyz = textureLod(u_tex, v_uv, 0.0).xyz;\n"
    "  vec2 imgCoord = ((v_uv * ViewportInfo.zw) + vec2(-0.5, 0.5));\n"
    "  vec2 imgCoordPixel = floor(imgCoord);\n"
    "  vec2 coord = (imgCoordPixel * ViewportInfo.xy);\n"
    "  vec2 pl = (imgCoord + (-imgCoordPixel));\n"
    "  vec4 left = textureGather(u_tex, coord, mode);\n"
    "  float edgeVote = abs(left.z - left.y) + abs(color[mode] - left.y) + abs(color[mode] - left.z);\n"
    "  if (edgeVote > edgeThreshold) {\n"
    "    coord.x += ViewportInfo.x;\n"
    "    vec4 right = textureGather(u_tex, coord + vec2(ViewportInfo.x, 0.0), mode);\n"
    "    vec4 upDown;\n"
    "    upDown.xy = textureGather(u_tex, coord + vec2(0.0, -ViewportInfo.y), mode).wz;\n"
    "    upDown.zw = textureGather(u_tex, coord + vec2(0.0, ViewportInfo.y), mode).yx;\n"
    "    float mean = (left.y + left.z + right.x + right.w) * 0.25;\n"
    "    left = left - vec4(mean);\n"
    "    right = right - vec4(mean);\n"
    "    upDown = upDown - vec4(mean);\n"
    "    color.w = color[mode] - mean;\n"
    "    float sum = (((((abs(left.x) + abs(left.y)) + abs(left.z)) + abs(left.w)) +\n"
    "                  (((abs(right.x) + abs(right.y)) + abs(right.z)) + abs(right.w))) +\n"
    "                 (((abs(upDown.x) + abs(upDown.y)) + abs(upDown.z)) + abs(upDown.w)));\n"
    "    float std = 2.181818 / sum;\n"
    "    vec2 aWY = weightY(pl.x, pl.y + 1.0, upDown.x, std);\n"
    "    aWY += weightY(pl.x - 1.0, pl.y + 1.0, upDown.y, std);\n"
    "    aWY += weightY(pl.x - 1.0, pl.y - 2.0, upDown.z, std);\n"
    "    aWY += weightY(pl.x, pl.y - 2.0, upDown.w, std);\n"
    "    aWY += weightY(pl.x + 1.0, pl.y - 1.0, left.x, std);\n"
    "    aWY += weightY(pl.x, pl.y - 1.0, left.y, std);\n"
    "    aWY += weightY(pl.x, pl.y, left.z, std);\n"
    "    aWY += weightY(pl.x + 1.0, pl.y, left.w, std);\n"
    "    aWY += weightY(pl.x - 1.0, pl.y - 1.0, right.x, std);\n"
    "    aWY += weightY(pl.x - 2.0, pl.y - 1.0, right.y, std);\n"
    "    aWY += weightY(pl.x - 2.0, pl.y, right.z, std);\n"
    "    aWY += weightY(pl.x - 1.0, pl.y, right.w, std);\n"
    "    float finalY = aWY.y / aWY.x;\n"
    "    float maxY = max(max(left.y, left.z), max(right.x, right.w));\n"
    "    float minY = min(min(left.y, left.z), min(right.x, right.w));\n"
    "    finalY = clamp(edgeSharpness * finalY, minY, maxY);\n"
    "    float deltaY = finalY - color.w;\n"
    "    deltaY = clamp(deltaY, -23.0 / 255.0, 23.0 / 255.0);\n" /* smooth high contrast input */
    "    color.x = clamp((color.x + deltaY), 0.0, 1.0);\n"
    "    color.y = clamp((color.y + deltaY), 0.0, 1.0);\n"
    "    color.z = clamp((color.z + deltaY), 0.0, 1.0);\n"
    "  }\n"
    "  o = ramp(color.xyz);\n"
    "}\n";

static const char k_fs_easu[] =
    "// FidelityFX Super Resolution 1.0, EASU. Copyright (c) 2021 Advanced Micro\n"
    "// Devices, Inc. (MIT license). extra = 1/w, 1/h, w, h of the input frame.\n"
    "void tap(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len, float lob, float clp, vec3 c) {\n"
    "  vec2 v = vec2(off.x * dir.x + off.y * dir.y, off.x * (-dir.y) + off.y * dir.x);\n"
    "  v *= len;\n"
    "  float d2 = min(v.x * v.x + v.y * v.y, clp);\n"
    "  float wB = 2.0 / 5.0 * d2 - 1.0;\n"
    "  float wA = lob * d2 - 1.0;\n"
    "  wB *= wB;\n"
    "  wA *= wA;\n"
    "  wB = 25.0 / 16.0 * wB - (25.0 / 16.0 - 1.0);\n"
    "  float w = wB * wA;\n"
    "  aC += c * w;\n"
    "  aW += w;\n"
    "}\n"
    "void setd(inout vec2 dir, inout float len, vec2 pp, bool biS, bool biT, bool biU, bool biV,\n"
    "          float lA, float lB, float lC, float lD, float lE) {\n"
    "  float w = 0.0;\n"
    "  if (biS) w = (1.0 - pp.x) * (1.0 - pp.y);\n"
    "  if (biT) w = pp.x * (1.0 - pp.y);\n"
    "  if (biU) w = (1.0 - pp.x) * pp.y;\n"
    "  if (biV) w = pp.x * pp.y;\n"
    "  float lenX = max(abs(lD - lC), abs(lC - lB));\n"
    "  float dirX = lD - lB;\n"
    "  dir.x += dirX * w;\n"
    "  lenX = clamp(abs(dirX) / max(lenX, 1.0 / 65536.0), 0.0, 1.0);\n"
    "  lenX *= lenX;\n"
    "  len += lenX * w;\n"
    "  float lenY = max(abs(lE - lC), abs(lC - lA));\n"
    "  float dirY = lE - lA;\n"
    "  dir.y += dirY * w;\n"
    "  lenY = clamp(abs(dirY) / max(lenY, 1.0 / 65536.0), 0.0, 1.0);\n"
    "  lenY *= lenY;\n"
    "  len += lenY * w;\n"
    "}\n"
    "vec3 cc(vec4 r, vec4 g, vec4 b, int i) { return vec3(r[i], g[i], b[i]); }\n"
    "void main() {\n"
    "  vec4 vi = pc.extra;\n"
    "  vec2 pp = v_uv * vi.zw - 0.5;\n"
    "  vec2 fp = floor(pp);\n"
    "  pp -= fp;\n"
    "  vec2 p0 = (fp + vec2(1.0, -1.0)) * vi.xy;\n"
    "  vec2 p1 = p0 + vec2(-1.0, 2.0) * vi.xy;\n"
    "  vec2 p2 = p0 + vec2(1.0, 2.0) * vi.xy;\n"
    "  vec2 p3 = p0 + vec2(0.0, 4.0) * vi.xy;\n"
    "  vec4 bczzR = textureGather(u_tex, p0, 0), bczzG = textureGather(u_tex, p0, 1), bczzB = textureGather(u_tex, p0, 2);\n"
    "  vec4 ijfeR = textureGather(u_tex, p1, 0), ijfeG = textureGather(u_tex, p1, 1), ijfeB = textureGather(u_tex, p1, 2);\n"
    "  vec4 klhgR = textureGather(u_tex, p2, 0), klhgG = textureGather(u_tex, p2, 1), klhgB = textureGather(u_tex, p2, 2);\n"
    "  vec4 zzonR = textureGather(u_tex, p3, 0), zzonG = textureGather(u_tex, p3, 1), zzonB = textureGather(u_tex, p3, 2);\n"
    "  vec4 bczzL = bczzB * 0.5 + (bczzR * 0.5 + bczzG);\n"
    "  vec4 ijfeL = ijfeB * 0.5 + (ijfeR * 0.5 + ijfeG);\n"
    "  vec4 klhgL = klhgB * 0.5 + (klhgR * 0.5 + klhgG);\n"
    "  vec4 zzonL = zzonB * 0.5 + (zzonR * 0.5 + zzonG);\n"
    "  float bL = bczzL.x, cL = bczzL.y;\n"
    "  float iL = ijfeL.x, jL = ijfeL.y, fL = ijfeL.z, eL = ijfeL.w;\n"
    "  float kL = klhgL.x, lL = klhgL.y, hL = klhgL.z, gL = klhgL.w;\n"
    "  float oL = zzonL.z, nL = zzonL.w;\n"
    "  vec2 dir = vec2(0.0);\n"
    "  float len = 0.0;\n"
    "  setd(dir, len, pp, true, false, false, false, bL, eL, fL, gL, jL);\n"
    "  setd(dir, len, pp, false, true, false, false, cL, fL, gL, hL, kL);\n"
    "  setd(dir, len, pp, false, false, true, false, fL, iL, jL, kL, nL);\n"
    "  setd(dir, len, pp, false, false, false, true, gL, jL, kL, lL, oL);\n"
    "  vec2 dir2 = dir * dir;\n"
    "  float dirR = dir2.x + dir2.y;\n"
    "  bool zro = dirR < 1.0 / 32768.0;\n"
    "  dirR = inversesqrt(max(dirR, 1.0 / 32768.0));\n"
    "  dirR = zro ? 1.0 : dirR;\n"
    "  dir.x = zro ? 1.0 : dir.x;\n"
    "  dir *= dirR;\n"
    "  len = len * 0.5;\n"
    "  len *= len;\n"
    "  float stretch = (dir.x * dir.x + dir.y * dir.y) / max(abs(dir.x), abs(dir.y));\n"
    "  vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);\n"
    "  float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;\n"
    "  float clp = 1.0 / lob;\n"
    "  vec3 fC = cc(ijfeR, ijfeG, ijfeB, 2), gC = cc(klhgR, klhgG, klhgB, 3);\n"
    "  vec3 jC = cc(ijfeR, ijfeG, ijfeB, 1), kC = cc(klhgR, klhgG, klhgB, 0);\n"
    "  vec3 min4 = min(min(fC, gC), min(jC, kC));\n"
    "  vec3 max4 = max(max(fC, gC), max(jC, kC));\n"
    "  vec3 aC = vec3(0.0);\n"
    "  float aW = 0.0;\n"
    "  tap(aC, aW, vec2(0.0, -1.0) - pp, dir, len2, lob, clp, cc(bczzR, bczzG, bczzB, 0));\n"
    "  tap(aC, aW, vec2(1.0, -1.0) - pp, dir, len2, lob, clp, cc(bczzR, bczzG, bczzB, 1));\n"
    "  tap(aC, aW, vec2(-1.0, 1.0) - pp, dir, len2, lob, clp, cc(ijfeR, ijfeG, ijfeB, 0));\n"
    "  tap(aC, aW, vec2(0.0, 1.0) - pp, dir, len2, lob, clp, jC);\n"
    "  tap(aC, aW, vec2(0.0, 0.0) - pp, dir, len2, lob, clp, fC);\n"
    "  tap(aC, aW, vec2(-1.0, 0.0) - pp, dir, len2, lob, clp, cc(ijfeR, ijfeG, ijfeB, 3));\n"
    "  tap(aC, aW, vec2(1.0, 1.0) - pp, dir, len2, lob, clp, kC);\n"
    "  tap(aC, aW, vec2(2.0, 1.0) - pp, dir, len2, lob, clp, cc(klhgR, klhgG, klhgB, 1));\n"
    "  tap(aC, aW, vec2(2.0, 0.0) - pp, dir, len2, lob, clp, cc(klhgR, klhgG, klhgB, 2));\n"
    "  tap(aC, aW, vec2(1.0, 0.0) - pp, dir, len2, lob, clp, gC);\n"
    "  tap(aC, aW, vec2(1.0, 2.0) - pp, dir, len2, lob, clp, cc(zzonR, zzonG, zzonB, 2));\n"
    "  tap(aC, aW, vec2(0.0, 2.0) - pp, dir, len2, lob, clp, cc(zzonR, zzonG, zzonB, 3));\n"
    "  o = vec4(min(max4, max(min4, aC / aW)), 1.0);\n"
    "}\n";
static const char k_fs_rcas[] =
    "// FidelityFX Super Resolution 1.0, RCAS (with denoise). Copyright (c) 2021\n"
    "// Advanced Micro Devices, Inc. (MIT license). extra.x = exp2(-sharpness).\n"
    "void main() {\n"
    "  ivec2 sz = textureSize(u_tex, 0);\n"
    "  ivec2 ip = ivec2(v_uv * vec2(sz));\n"
    "  ivec2 hi = sz - 1;\n"
    "  vec3 b = texelFetch(u_tex, clamp(ip + ivec2(0, -1), ivec2(0), hi), 0).rgb;\n"
    "  vec3 d = texelFetch(u_tex, clamp(ip + ivec2(-1, 0), ivec2(0), hi), 0).rgb;\n"
    "  vec3 e = texelFetch(u_tex, clamp(ip, ivec2(0), hi), 0).rgb;\n"
    "  vec3 f = texelFetch(u_tex, clamp(ip + ivec2(1, 0), ivec2(0), hi), 0).rgb;\n"
    "  vec3 h = texelFetch(u_tex, clamp(ip + ivec2(0, 1), ivec2(0), hi), 0).rgb;\n"
    "  float bL = b.b * 0.5 + (b.r * 0.5 + b.g);\n"
    "  float dL = d.b * 0.5 + (d.r * 0.5 + d.g);\n"
    "  float eL = e.b * 0.5 + (e.r * 0.5 + e.g);\n"
    "  float fL = f.b * 0.5 + (f.r * 0.5 + f.g);\n"
    "  float hL = h.b * 0.5 + (h.r * 0.5 + h.g);\n"
    "  float nz = 0.25 * bL + 0.25 * dL + 0.25 * fL + 0.25 * hL - eL;\n"
    "  float rng = max(max(bL, dL), max(eL, max(fL, hL))) - min(min(bL, dL), min(eL, min(fL, hL)));\n"
    "  nz = clamp(abs(nz) / max(rng, 1.0 / 65536.0), 0.0, 1.0);\n"
    "  nz = -0.5 * nz + 1.0;\n"
    "  vec3 mn4 = min(min(b, d), min(f, h));\n"
    "  vec3 mx4 = max(max(b, d), max(f, h));\n"
    "  vec3 hitMin = mn4 / (4.0 * max(mx4, vec3(1.0 / 1024.0)));\n"
    "  vec3 hitMax = (1.0 - mx4) / min(4.0 * mn4 - 4.0, -1.0 / 256.0);\n"
    "  vec3 lobeRGB = max(-hitMin, hitMax);\n"
    "  float lobe = max(-0.1875, min(max(lobeRGB.r, max(lobeRGB.g, lobeRGB.b)), 0.0)) * pc.extra.x;\n"
    "  lobe *= nz;\n"
    "  float rcpL = 1.0 / (4.0 * lobe + 1.0);\n"
    "  vec3 pix = (lobe * (b + d + h + f) + e) * rcpL;\n"
    "  o = ramp(pix);\n"
    "}\n";

static VkShaderModule module(HleShaderStage stage, const char *head, const char *body)
{
    g_autofree char *src = g_strdup_printf("%s%s", head, body);
    size_t n = 0;
    g_autofree uint32_t *spv = hle_glsl_compile(stage, src, &n);
    VkShaderModule m = VK_NULL_HANDLE;
    VkShaderModuleCreateInfo ci = { .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
                                    .codeSize = n * 4, .pCode = spv };
    if (spv && T.vkCreateShaderModule(g_hvk.dev, &ci, NULL, &m) != VK_SUCCESS) {
        m = VK_NULL_HANDLE;
    }
    return m;
}

/* The SDK shader includes NIS_Scaler.h. Its source is embedded by
 * native/cmake/hle.cmake, then supplied to glslang's include callback. */
static const char *nis_include(void *ctx, const char *name, size_t *length)
{
    (void)ctx;
    if (strcmp(name, "NIS_Scaler.h") != 0) {
        return NULL;
    }
    *length = sizeof(k_hle_nis_scaler) - 1;
    return k_hle_nis_scaler;
}

static VkShaderModule nis_compute_module(void)
{
    size_t n = 0;
    g_autofree uint32_t *spv = hle_glsl_compile_with_includes(
        HLE_SHADER_COMPUTE, k_hle_nis_main, nis_include, NULL, &n);
    if (!spv || !n) {
        return VK_NULL_HANDLE;
    }
    VkShaderModuleCreateInfo ci = {
        .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
        .codeSize = n * sizeof(uint32_t),
        .pCode = spv
    };
    VkShaderModule shader = VK_NULL_HANDLE;
    if (T.vkCreateShaderModule(g_hvk.dev, &ci, NULL, &shader) != VK_SUCCESS) {
        return VK_NULL_HANDLE;
    }
    return shader;
}

/* Build the compute pipeline independently of the active presentation
 * path. Until the NIS output image and descriptors are ready, it is never
 * dispatched; a failure here leaves all existing filters untouched. */
static bool nis_config_buffer_init(void)
{
    const VkDeviceSize align = MAX((VkDeviceSize)g_hvk.props.limits.minUniformBufferOffsetAlignment, (VkDeviceSize)1);
    g_ps.nis_config_stride = (sizeof(float) * 64 + align - 1) / align * align;
    VkBufferCreateInfo bci = {
        .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
        .size = g_ps.nis_config_stride * PS_FRAMES,
        .usage = VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE
    };
    if (T.vkCreateBuffer(g_hvk.dev, &bci, NULL, &g_ps.nis_config) != VK_SUCCESS) return false;
    VkMemoryRequirements mr;
    T.vkGetBufferMemoryRequirements(g_hvk.dev, g_ps.nis_config, &mr);
    int mt = hle_vk_memtype(mr.memoryTypeBits,
                            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, 0);
    VkMemoryAllocateInfo mai = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize = mr.size, .memoryTypeIndex = mt < 0 ? 0u : (uint32_t)mt
    };
    if (mt < 0 || T.vkAllocateMemory(g_hvk.dev, &mai, NULL, &g_ps.nis_config_mem) != VK_SUCCESS ||
        T.vkBindBufferMemory(g_hvk.dev, g_ps.nis_config, g_ps.nis_config_mem, 0) != VK_SUCCESS ||
        T.vkMapMemory(g_hvk.dev, g_ps.nis_config_mem, 0, VK_WHOLE_SIZE, 0,
                      (void **)&g_ps.nis_config_map) != VK_SUCCESS) return false;
    return true;
}
static void nis_image_drop(VkImage *image, VkDeviceMemory *memory, VkImageView *view)
{
    if (*view) T.vkDestroyImageView(g_hvk.dev, *view, NULL);
    if (*image) T.vkDestroyImage(g_hvk.dev, *image, NULL);
    if (*memory) T.vkFreeMemory(g_hvk.dev, *memory, NULL);
    *image = VK_NULL_HANDLE; *memory = VK_NULL_HANDLE; *view = VK_NULL_HANDLE;
}

static bool nis_image_make(uint32_t w, uint32_t h, VkFormat format, VkImageUsageFlags usage,
                           VkImage *image, VkDeviceMemory *memory, VkImageView *view)
{
    VkImageCreateInfo ici = { .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
        .imageType = VK_IMAGE_TYPE_2D, .format = format, .extent = { w, h, 1 },
        .mipLevels = 1, .arrayLayers = 1, .samples = VK_SAMPLE_COUNT_1_BIT,
        .tiling = VK_IMAGE_TILING_OPTIMAL, .usage = usage,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE, .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED };
    if (T.vkCreateImage(g_hvk.dev, &ici, NULL, image) != VK_SUCCESS) return false;
    VkMemoryRequirements mr; T.vkGetImageMemoryRequirements(g_hvk.dev, *image, &mr);
    int mt = hle_vk_memtype(mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0);
    VkMemoryAllocateInfo mai = { .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize = mr.size, .memoryTypeIndex = mt < 0 ? 0u : (uint32_t)mt };
    VkImageViewCreateInfo vci = { .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
        .image = *image, .viewType = VK_IMAGE_VIEW_TYPE_2D, .format = format,
        .subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 } };
    if (mt < 0 || T.vkAllocateMemory(g_hvk.dev, &mai, NULL, memory) != VK_SUCCESS ||
        T.vkBindImageMemory(g_hvk.dev, *image, *memory, 0) != VK_SUCCESS ||
        T.vkCreateImageView(g_hvk.dev, &vci, NULL, view) != VK_SUCCESS) {
        nis_image_drop(image, memory, view); return false;
    }
    return true;
}

static bool nis_upload_coefficients(void)
{
    const VkDeviceSize bytes = HLE_NIS_COEFFICIENT_FLOATS * sizeof(float);
    VkFormatProperties fp; vkGetPhysicalDeviceFormatProperties(g_hvk.phys, VK_FORMAT_R32G32B32A32_SFLOAT, &fp);
    const VkFormatFeatureFlags need = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
    if ((fp.optimalTilingFeatures & need) != need) return false;
    VkBuffer staging = VK_NULL_HANDLE; VkDeviceMemory staging_mem = VK_NULL_HANDLE;
    VkCommandPool pool = VK_NULL_HANDLE; VkCommandBuffer cb = VK_NULL_HANDLE; void *mapped = NULL;
    bool ok = false;
    VkBufferCreateInfo bci = { .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, .size = bytes * 2,
        .usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT, .sharingMode = VK_SHARING_MODE_EXCLUSIVE };
    if (T.vkCreateBuffer(g_hvk.dev, &bci, NULL, &staging) != VK_SUCCESS) goto cleanup;
    VkMemoryRequirements mr; T.vkGetBufferMemoryRequirements(g_hvk.dev, staging, &mr);
    int mt = hle_vk_memtype(mr.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, 0);
    VkMemoryAllocateInfo mai = { .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize = mr.size, .memoryTypeIndex = mt < 0 ? 0u : (uint32_t)mt };
    if (mt < 0 || T.vkAllocateMemory(g_hvk.dev, &mai, NULL, &staging_mem) != VK_SUCCESS ||
        T.vkBindBufferMemory(g_hvk.dev, staging, staging_mem, 0) != VK_SUCCESS ||
        T.vkMapMemory(g_hvk.dev, staging_mem, 0, VK_WHOLE_SIZE, 0, &mapped) != VK_SUCCESS) goto cleanup;
    hle_nis_copy_coefficients((float *)mapped, HLE_NIS_COEFFICIENT_FLOATS,
        (float *)((uint8_t *)mapped + bytes), HLE_NIS_COEFFICIENT_FLOATS);
    if (!nis_image_make(2, HLE_NIS_COEFFICIENT_PHASES, VK_FORMAT_R32G32B32A32_SFLOAT,
            VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
            &g_ps.nis_coef_scale, &g_ps.nis_coef_scale_mem, &g_ps.nis_coef_scale_view) ||
        !nis_image_make(2, HLE_NIS_COEFFICIENT_PHASES, VK_FORMAT_R32G32B32A32_SFLOAT,
            VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
            &g_ps.nis_coef_usm, &g_ps.nis_coef_usm_mem, &g_ps.nis_coef_usm_view)) goto cleanup;
    {
        VkCommandPoolCreateInfo pci = { .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
            .flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT, .queueFamilyIndex = g_hvk.qfam };
        if (T.vkCreateCommandPool(g_hvk.dev, &pci, NULL, &pool) != VK_SUCCESS) goto cleanup;
        VkCommandBufferAllocateInfo cai = { .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
            .commandPool = pool, .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY, .commandBufferCount = 1 };
        if (T.vkAllocateCommandBuffers(g_hvk.dev, &cai, &cb) != VK_SUCCESS) goto cleanup;
        VkCommandBufferBeginInfo begin = { .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
            .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT };
        if (T.vkBeginCommandBuffer(cb, &begin) != VK_SUCCESS) goto cleanup;
        VkImage imgs[2] = { g_ps.nis_coef_scale, g_ps.nis_coef_usm };
        VkImageMemoryBarrier2 bars[2] = {0};
        for (unsigned i = 0; i < 2; i++) bars[i] = (VkImageMemoryBarrier2){
            .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2, .dstStageMask = VK_PIPELINE_STAGE_2_TRANSFER_BIT,
            .dstAccessMask = VK_ACCESS_2_TRANSFER_WRITE_BIT, .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED,
            .newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED, .image = imgs[i],
            .subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 } };
        VkDependencyInfo dep = { .sType = VK_STRUCTURE_TYPE_DEPENDENCY_INFO,
            .imageMemoryBarrierCount = 2, .pImageMemoryBarriers = bars };
        T.vkCmdPipelineBarrier2(cb, &dep);
        VkBufferImageCopy copies[2] = {
            { .bufferOffset = 0, .imageSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 }, .imageExtent = { 2, HLE_NIS_COEFFICIENT_PHASES, 1 } },
            { .bufferOffset = bytes, .imageSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 }, .imageExtent = { 2, HLE_NIS_COEFFICIENT_PHASES, 1 } } };
        T.vkCmdCopyBufferToImage(cb, staging, imgs[0], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copies[0]);
        T.vkCmdCopyBufferToImage(cb, staging, imgs[1], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copies[1]);
        for (unsigned i = 0; i < 2; i++) bars[i] = (VkImageMemoryBarrier2){
            .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2, .srcStageMask = VK_PIPELINE_STAGE_2_TRANSFER_BIT,
            .srcAccessMask = VK_ACCESS_2_TRANSFER_WRITE_BIT, .dstStageMask = VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
            .dstAccessMask = VK_ACCESS_2_SHADER_SAMPLED_READ_BIT, .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            .newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
            .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED, .image = imgs[i],
            .subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 } };
        T.vkCmdPipelineBarrier2(cb, &dep);
        if (T.vkEndCommandBuffer(cb) != VK_SUCCESS) goto cleanup;
        VkCommandBufferSubmitInfo cbi = { .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_SUBMIT_INFO, .commandBuffer = cb };
        VkSubmitInfo2 si = { .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO_2, .commandBufferInfoCount = 1, .pCommandBufferInfos = &cbi };
        hle_vk_queue_lock(); VkResult qr = T.vkQueueSubmit2(g_hvk.queue, 1, &si, VK_NULL_HANDLE);
        if (qr == VK_SUCCESS) qr = T.vkQueueWaitIdle(g_hvk.queue); hle_vk_queue_unlock();
        ok = qr == VK_SUCCESS;
    }
cleanup:
    if (mapped) T.vkUnmapMemory(g_hvk.dev, staging_mem);
    if (pool) T.vkDestroyCommandPool(g_hvk.dev, pool, NULL);
    if (staging) T.vkDestroyBuffer(g_hvk.dev, staging, NULL);
    if (staging_mem) T.vkFreeMemory(g_hvk.dev, staging_mem, NULL);
    if (!ok) {
        nis_image_drop(&g_ps.nis_coef_scale, &g_ps.nis_coef_scale_mem, &g_ps.nis_coef_scale_view);
        nis_image_drop(&g_ps.nis_coef_usm, &g_ps.nis_coef_usm_mem, &g_ps.nis_coef_usm_view);
    }
    return ok;
}
static bool nis_compute_pipeline_init(void)
{
    if (!g_ps.nis_cs) {
        return false;
    }

    const VkDescriptorSetLayoutBinding bindings[6] = {
        { 0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, NULL },
        { 1, VK_DESCRIPTOR_TYPE_SAMPLER, 1, VK_SHADER_STAGE_COMPUTE_BIT, NULL },
        { 2, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, NULL },
        { 3, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, NULL },
        { 4, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, NULL },
        { 5, VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, 1, VK_SHADER_STAGE_COMPUTE_BIT, NULL },
    };
    VkDescriptorSetLayoutCreateInfo dci = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
        .bindingCount = 6,
        .pBindings = bindings
    };
    if (T.vkCreateDescriptorSetLayout(g_hvk.dev, &dci, NULL, &g_ps.nis_dsl) != VK_SUCCESS) {
        return false;
    }

    VkPipelineLayoutCreateInfo lci = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
        .setLayoutCount = 1,
        .pSetLayouts = &g_ps.nis_dsl
    };
    if (T.vkCreatePipelineLayout(g_hvk.dev, &lci, NULL, &g_ps.nis_pl) != VK_SUCCESS) {
        return false;
    }

    VkComputePipelineCreateInfo pci = {
        .sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO,
        .stage = {
            .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
            .stage = VK_SHADER_STAGE_COMPUTE_BIT,
            .module = g_ps.nis_cs,
            .pName = "main"
        },
        .layout = g_ps.nis_pl
    };
    if (T.vkCreateComputePipelines(g_hvk.dev, VK_NULL_HANDLE, 1, &pci,
                                   NULL, &g_ps.nis_pipe) != VK_SUCCESS) return false;
    VkDescriptorSetLayout layouts[PS_FRAMES] = { g_ps.nis_dsl, g_ps.nis_dsl, g_ps.nis_dsl };
    VkDescriptorSet sets[PS_FRAMES];
    VkDescriptorSetAllocateInfo ai = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
        .descriptorPool = g_ps.dp, .descriptorSetCount = PS_FRAMES, .pSetLayouts = layouts
    };
    if (T.vkAllocateDescriptorSets(g_hvk.dev, &ai, sets) != VK_SUCCESS || !nis_config_buffer_init()) return false;
    for (unsigned i = 0; i < PS_FRAMES; i++) g_ps.fr[i].nis_set = sets[i];
    return nis_upload_coefficients();
}

static VkPipeline pipeline(VkShaderModule fs, VkFormat fmt, const char *name)
{
    if (!g_ps.vs || !fs) {
        hle_log("hakuX-hle-back", "present: %s pipeline skipped (vertex shader %s, fragment shader %s)",
                name, g_ps.vs ? "ready" : "missing", fs ? "ready" : "missing");
        return VK_NULL_HANDLE;
    }
    VkPipelineShaderStageCreateInfo st[2] = {
        { .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
          .stage = VK_SHADER_STAGE_VERTEX_BIT, .module = g_ps.vs, .pName = "main" },
        { .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
          .stage = VK_SHADER_STAGE_FRAGMENT_BIT, .module = fs, .pName = "main" } };
    VkPipelineVertexInputStateCreateInfo vi = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO };
    VkPipelineInputAssemblyStateCreateInfo ia = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO,
        .topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP };
    VkPipelineViewportStateCreateInfo vp = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO,
        .viewportCount = 1, .scissorCount = 1 };
    VkPipelineRasterizationStateCreateInfo rs = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO,
        .polygonMode = VK_POLYGON_MODE_FILL, .cullMode = VK_CULL_MODE_NONE, .lineWidth = 1.0f };
    VkPipelineMultisampleStateCreateInfo ms = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO,
        .rasterizationSamples = VK_SAMPLE_COUNT_1_BIT };
    VkPipelineColorBlendAttachmentState ba = { .colorWriteMask = 0xF };
    VkPipelineColorBlendStateCreateInfo cb = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO,
        .attachmentCount = 1, .pAttachments = &ba };
    VkDynamicState dyn[2] = { VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR };
    VkPipelineDynamicStateCreateInfo ds = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO,
        .dynamicStateCount = 2, .pDynamicStates = dyn };
    VkPipelineRenderingCreateInfo rci = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO,
        .colorAttachmentCount = 1, .pColorAttachmentFormats = &fmt };
    VkGraphicsPipelineCreateInfo gci = {
        .sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO, .pNext = &rci,
        .stageCount = 2, .pStages = st, .pVertexInputState = &vi, .pInputAssemblyState = &ia,
        .pViewportState = &vp, .pRasterizationState = &rs, .pMultisampleState = &ms,
        .pColorBlendState = &cb, .pDynamicState = &ds, .layout = g_ps.pl };
    VkPipeline p = VK_NULL_HANDLE;
    const VkResult result = T.vkCreateGraphicsPipelines(g_hvk.dev, VK_NULL_HANDLE, 1, &gci, NULL, &p);
    if (result != VK_SUCCESS) {
        hle_log("hakuX-hle-back", "present: %s graphics pipeline creation failed (format=%d, VkResult=%d)",
                name, fmt, result);
        p = VK_NULL_HANDLE;
    }
    return p;
}

/* ---- setup ---- */

static bool init(void)
{
    volkLoadDeviceTable(&g_ps.t, g_hvk.dev);
    hle_compat_install(&g_ps.t);        /* below 1.3: as the back end's table */
    VkSemaphoreTypeCreateInfo tci = { .sType = VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO,
                                      .semaphoreType = VK_SEMAPHORE_TYPE_TIMELINE };
    VkSemaphoreCreateInfo tsi = { .sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO, .pNext = &tci };
    VkSamplerCreateInfo sci = {
        .sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO,
        .magFilter = VK_FILTER_LINEAR, .minFilter = VK_FILTER_LINEAR,
        .addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        .addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        .addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, .maxLod = 0.0f };
    VkDescriptorSetLayoutBinding b[2] = {
        { 0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_FRAGMENT_BIT, NULL },
        { 1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, VK_SHADER_STAGE_FRAGMENT_BIT, NULL } };
    VkDescriptorSetLayoutCreateInfo dci = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO, .bindingCount = 2, .pBindings = b };
    if (T.vkCreateSemaphore(g_hvk.dev, &tsi, NULL, &g_ps.tl) != VK_SUCCESS ||
        T.vkCreateSampler(g_hvk.dev, &sci, NULL, &g_ps.smp) != VK_SUCCESS ||
        T.vkCreateDescriptorSetLayout(g_hvk.dev, &dci, NULL, &g_ps.dsl) != VK_SUCCESS) {
        return false;
    }
    VkPushConstantRange pcr = { VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, 48 };
    VkPipelineLayoutCreateInfo lci = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO, .setLayoutCount = 1,
        .pSetLayouts = &g_ps.dsl, .pushConstantRangeCount = 1, .pPushConstantRanges = &pcr };
    VkDescriptorPoolSize ps[5] = {
        { VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, PS_FRAMES * 3 },
        { VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, PS_FRAMES * 4 },
        { VK_DESCRIPTOR_TYPE_SAMPLER, PS_FRAMES },
        { VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, PS_FRAMES * 3 },
        { VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, PS_FRAMES }
    };
    VkDescriptorPoolCreateInfo pci = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO, .maxSets = PS_FRAMES * 4,
        .poolSizeCount = ARRAY_SIZE(ps), .pPoolSizes = ps };
    VkBufferCreateInfo bci = { .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, .size = PS_FRAMES * PS_RAMP,
                               .usage = VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT };
    if (T.vkCreatePipelineLayout(g_hvk.dev, &lci, NULL, &g_ps.pl) != VK_SUCCESS ||
        T.vkCreateDescriptorPool(g_hvk.dev, &pci, NULL, &g_ps.dp) != VK_SUCCESS ||
        T.vkCreateBuffer(g_hvk.dev, &bci, NULL, &g_ps.ubo) != VK_SUCCESS) {
        return false;
    }
    VkMemoryRequirements mr;
    T.vkGetBufferMemoryRequirements(g_hvk.dev, g_ps.ubo, &mr);
    int mt = hle_vk_memtype(mr.memoryTypeBits,
                            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, 0);
    VkMemoryAllocateInfo mai = { .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
                                 .allocationSize = mr.size, .memoryTypeIndex = (uint32_t)mt };
    if (mt < 0 || T.vkAllocateMemory(g_hvk.dev, &mai, NULL, &g_ps.ubo_mem) != VK_SUCCESS ||
        T.vkBindBufferMemory(g_hvk.dev, g_ps.ubo, g_ps.ubo_mem, 0) != VK_SUCCESS ||
        T.vkMapMemory(g_hvk.dev, g_ps.ubo_mem, 0, VK_WHOLE_SIZE, 0, (void **)&g_ps.ubo_map) != VK_SUCCESS) {
        return false;
    }
    for (unsigned i = 0; i < 256; i++) {
        g_ps.ramp[0][i] = g_ps.ramp[1][i] = g_ps.ramp[2][i] = (uint8_t)i;
    }
    for (unsigned i = 0; i < PS_FRAMES; i++) {
        PsFrame *f = &g_ps.fr[i];
        VkCommandPoolCreateInfo cpi = { .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
                                        .flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT,
                                        .queueFamilyIndex = g_hvk.qfam };
        VkSemaphoreCreateInfo si = { .sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO };
        VkDescriptorSetLayout l[3] = { g_ps.dsl, g_ps.dsl, g_ps.dsl };
        VkDescriptorSetAllocateInfo dai = { .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
                                            .descriptorPool = g_ps.dp, .descriptorSetCount = 3,
                                            .pSetLayouts = l };
        if (T.vkCreateCommandPool(g_hvk.dev, &cpi, NULL, &f->pool) != VK_SUCCESS ||
            T.vkCreateSemaphore(g_hvk.dev, &si, NULL, &f->acquired) != VK_SUCCESS ||
            T.vkAllocateDescriptorSets(g_hvk.dev, &dai, f->set) != VK_SUCCESS) {
            return false;
        }
        VkCommandBufferAllocateInfo ai = { .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
                                           .commandPool = f->pool, .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
                                           .commandBufferCount = 1 };
        if (T.vkAllocateCommandBuffers(g_hvk.dev, &ai, &f->cb) != VK_SUCCESS) {
            return false;
        }
        f->ramp_stale = true;
    }
    g_ps.nis_cs = nis_compute_module();
    const bool nis_ready = nis_compute_pipeline_init();
    g_ps.nis_resources_ready = nis_ready;
    nis_status(0, nis_ready ? 1 : 0);
    g_ps.vs = module(HLE_SHADER_VERTEX, k_vs, "");
    static const char *const body[P_COUNT] = { k_fs_bilinear, k_fs_area, k_fs_sgsr, k_fs_rcas, k_fs_fxaa,
                                                k_fs_easu };
    for (unsigned i = 0; i < P_COUNT; i++) {
        g_ps.fs[i] = module(HLE_SHADER_FRAGMENT, k_fs_head, body[i]);
    }
    g_ps.pipe[P_FXAA] = pipeline(g_ps.fs[P_FXAA], VK_FORMAT_R8G8B8A8_UNORM, "FXAA");
    g_ps.pipe[P_EASU] = pipeline(g_ps.fs[P_EASU], VK_FORMAT_R8G8B8A8_UNORM, "FSR EASU");
    hle_log("hakuX-hle-back", "present: shader modules (RCAS=%d EASU=%d)",
            g_ps.fs[P_RCAS] != VK_NULL_HANDLE, g_ps.fs[P_EASU] != VK_NULL_HANDLE);
    hle_log("hakuX-hle-back", "present: NIS compute pipeline %s", nis_ready ? "ready (coefficients and compute resources uploaded)" : "unavailable (existing filters retained)");
    hle_log("hakuX-hle-back", "present: ready (shaders: bilinear %d, averaging %d, SGSR %d, FXAA %d, EASU %d)",
            g_ps.fs[P_BILINEAR] != VK_NULL_HANDLE, g_ps.fs[P_AREA] != VK_NULL_HANDLE,
            g_ps.fs[P_SGSR] != VK_NULL_HANDLE, g_ps.pipe[P_FXAA] != VK_NULL_HANDLE,
            g_ps.pipe[P_EASU] != VK_NULL_HANDLE);
    return g_ps.fs[P_BILINEAR] != VK_NULL_HANDLE;
}

/* Every submit of the presenter's done (the timeline), and with the queue
 * idle its presents' waits too: what a swapchain or image being replaced
 * may still be used by. */
static void idle(void)
{
    if (hle_vk_lost()) {
        return;                         /* nothing runs on it any more */
    }
    VkResult r = hle_tl_sem_wait(g_ps.tl, g_ps.submitted, 2000000000ull);
    if (r != VK_TIMEOUT) {
        hle_vk_check(r, "vkWaitSemaphores (present)");
    }
    hle_vk_queue_lock();
    r = T.vkQueueWaitIdle(g_hvk.queue);
    hle_vk_queue_unlock();
    hle_vk_check(r, "vkQueueWaitIdle (present)");
}

static void drop_images(void)
{
    for (uint32_t i = 0; i < g_ps.nimg; i++) {
        T.vkDestroyImageView(g_hvk.dev, g_ps.view[i], NULL);
        T.vkDestroySemaphore(g_hvk.dev, g_ps.drawn[i], NULL);
    }
    g_free(g_ps.img);
    g_free(g_ps.view);
    g_free(g_ps.drawn);
    g_ps.img = NULL;
    g_ps.view = NULL;
    g_ps.drawn = NULL;
    g_ps.nimg = 0;
}

/* The window gone, or another: its swapchain and surface go. */
static void drop_window(void)
{
    g_ps.shown = false;
    if (g_ps.sc || g_ps.surf) {
        idle();
    }
    drop_images();
    if (g_ps.sc) {
        T.vkDestroySwapchainKHR(g_hvk.dev, g_ps.sc, NULL);
        g_ps.sc = VK_NULL_HANDLE;
    }
    if (g_ps.surf) {
        vkDestroySurfaceKHR(g_hvk.inst, g_ps.surf, NULL);
        g_ps.surf = VK_NULL_HANDLE;
    }
    /* Keep the Android window alive for the entire lifetime of its Vulkan
     * surface. SDL exposes a borrowed ANativeWindow pointer; the surface can
     * outlive the frame in which that pointer was obtained. */
    if (g_ps.win) {
        ANativeWindow_release(g_ps.win);
        g_ps.win = NULL;
    }
}

/* Uncapped presentation is independent from the Xbox's VBlank clock.
 * Prefer MAILBOX (no tearing, latest frame wins), then IMMEDIATE (no queue
 * wait, may tear). FIFO remains the guaranteed fallback. */
static VkPresentModeKHR choose_present_mode(void)
{
    if (!getenv("HAKUX_HLE_UNCAP")) return VK_PRESENT_MODE_FIFO_KHR;
    VkPresentModeKHR modes[32];
    uint32_t count = (uint32_t)(sizeof(modes) / sizeof(modes[0]));
    if (vkGetPhysicalDeviceSurfacePresentModesKHR(g_hvk.phys, g_ps.surf, &count, modes) != VK_SUCCESS)
        return VK_PRESENT_MODE_FIFO_KHR;
    for (uint32_t i = 0; i < count; i++) {
        if (modes[i] == VK_PRESENT_MODE_MAILBOX_KHR) {
            hle_log("hakuX-hle-back", "present: uncapped MAILBOX; guest VBlank remains fixed");
            return VK_PRESENT_MODE_MAILBOX_KHR;
        }
    }
    for (uint32_t i = 0; i < count; i++) {
        if (modes[i] == VK_PRESENT_MODE_IMMEDIATE_KHR) {
            hle_log("hakuX-hle-back", "present: uncapped IMMEDIATE; tearing may occur, guest VBlank remains fixed");
            return VK_PRESENT_MODE_IMMEDIATE_KHR;
        }
    }
    hle_log("hakuX-hle-back", "present: surface supports FIFO only; output remains refresh-limited");
    return VK_PRESENT_MODE_FIFO_KHR;
}

static bool make_swapchain(uint32_t w, uint32_t h)
{
    VkSurfaceCapabilitiesKHR caps;
    VkBool32 ok = VK_FALSE;
    vkGetPhysicalDeviceSurfaceSupportKHR(g_hvk.phys, g_hvk.qfam, g_ps.surf, &ok);
    if (!ok || vkGetPhysicalDeviceSurfaceCapabilitiesKHR(g_hvk.phys, g_ps.surf, &caps) != VK_SUCCESS) {
        hle_log("hakuX-hle-back", "present: the window's surface can't be presented to");
        return false;
    }
    VkSurfaceFormatKHR fmts[32];
    uint32_t nf = 32;
    vkGetPhysicalDeviceSurfaceFormatsKHR(g_hvk.phys, g_ps.surf, &nf, fmts);
    /* plain 8-bit UNORM: the frame's values go out as they are (an sRGB
     * format would encode them again) */
    VkSurfaceFormatKHR fmt = nf ? fmts[0] : (VkSurfaceFormatKHR){ VK_FORMAT_R8G8B8A8_UNORM, 0 };
    for (int pass = 0; pass < 2; pass++) {
        const VkFormat want = pass ? VK_FORMAT_B8G8R8A8_UNORM : VK_FORMAT_R8G8B8A8_UNORM;
        for (uint32_t i = 0; i < nf; i++) {
            if (fmts[i].format == want && fmts[i].colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                fmt = fmts[i];
                pass = 2;
                break;
            }
        }
    }
    /* Android may make more images than asked for (10 for 6 on a Dimensity
     * 8300), so the arrays are sized from what the swapchain reports. */
    uint32_t n = MAX(caps.minImageCount, 3u);
    if (caps.maxImageCount) {
        n = MIN(n, caps.maxImageCount);
    }
    /* Pre-rotated: the images in the panel's own orientation (its transform
     * now), the picture turned into them as it's drawn. An image in the
     * window's orientation the compositor turns itself, with the GPU at every
     * refresh (Odin 3 and RP5 panels are portrait): that composition took GPU
     * time from the game, and the Odin 3 fell to its 60 Hz mode with it.
     * Qualcomm's EGL had pre-rotated GL's buffers. */
    static const VkSurfaceTransformFlagBitsKHR k_turns[4] = {
        VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR, VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR,
        VK_SURFACE_TRANSFORM_ROTATE_180_BIT_KHR, VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR };
    static const float k_rot[4][4] = { { 1, 0, 0, 1 }, { 0, -1, 1, 0 }, { -1, 0, 0, -1 }, { 0, 1, -1, 0 } };
    unsigned turn = 0;
    for (unsigned i = 0; i < 4; i++) {
        if (caps.currentTransform == k_turns[i] && (caps.supportedTransforms & k_turns[i])) {
            turn = i;
        }
    }
    const VkSurfaceTransformFlagBitsKHR tf = k_turns[turn];
    g_ps.follows = tf == caps.currentTransform;
    memcpy(g_ps.rot, k_rot[turn], sizeof(g_ps.rot));
    g_ps.wsize = (VkExtent2D){ w, h };
    if (turn & 1) {
        const uint32_t t = w;   /* a quarter turn: the panel's width is the window's height */
        w = h;
        h = t;
    }
    w = MIN(MAX(w, caps.minImageExtent.width), caps.maxImageExtent.width);
    h = MIN(MAX(h, caps.minImageExtent.height), caps.maxImageExtent.height);
    if (hle_quirk(HLE_QUIRK_SWAP_W32) && (w & ~31u) >= MAX(caps.minImageExtent.width, 32u)) {
        w &= ~31u;                      /* older PowerVR drivers (quirks.h) */
    }
    VkCompositeAlphaFlagBitsKHR ca = (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                                         ? VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR
                                         : VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    VkSwapchainKHR old = g_ps.sc;
    if (old) {
        idle();
    }
    VkSwapchainCreateInfoKHR ci = {
        .sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR, .surface = g_ps.surf,
        .minImageCount = n, .imageFormat = fmt.format, .imageColorSpace = fmt.colorSpace,
        .imageExtent = { w, h }, .imageArrayLayers = 1,
        .imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
        .imageSharingMode = VK_SHARING_MODE_EXCLUSIVE, .preTransform = tf, .compositeAlpha = ca,
        .presentMode = choose_present_mode(), .clipped = VK_TRUE, .oldSwapchain = old };
    VkSwapchainKHR sc = VK_NULL_HANDLE;
    VkResult r = T.vkCreateSwapchainKHR(g_hvk.dev, &ci, NULL, &sc);
    drop_images();
    if (old) {
        T.vkDestroySwapchainKHR(g_hvk.dev, old, NULL);
    }
    g_ps.sc = sc;
    if (r != VK_SUCCESS) {
        g_ps.sc = VK_NULL_HANDLE;
        hle_log("hakuX-hle-back", "present: vkCreateSwapchainKHR %ux%u: %d", w, h, r);
        return false;
    }
    uint32_t ni = 0;
    VkResult ri = T.vkGetSwapchainImagesKHR(g_hvk.dev, sc, &ni, NULL);
    VkImage *imgs = ri == VK_SUCCESS && ni ? g_new0(VkImage, ni) : NULL;
    if (imgs) {
        ri = T.vkGetSwapchainImagesKHR(g_hvk.dev, sc, &ni, imgs);
    }
    if (!imgs || ri != VK_SUCCESS) {
        hle_log("hakuX-hle-back", "present: the swapchain's %u images: %d", ni, ri);
        g_free(imgs);
        T.vkDestroySwapchainKHR(g_hvk.dev, sc, NULL);
        g_ps.sc = VK_NULL_HANDLE;
        return false;
    }
    g_ps.img = imgs;
    g_ps.view = g_new0(VkImageView, ni);
    g_ps.drawn = g_new0(VkSemaphore, ni);
    for (uint32_t i = 0; i < ni; i++) {
        VkImageViewCreateInfo vci = {
            .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO, .image = g_ps.img[i],
            .viewType = VK_IMAGE_VIEW_TYPE_2D, .format = fmt.format,
            .subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 } };
        T.vkCreateImageView(g_hvk.dev, &vci, NULL, &g_ps.view[i]);
        const VkSemaphoreCreateInfo sci = { .sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO };
        T.vkCreateSemaphore(g_hvk.dev, &sci, NULL, &g_ps.drawn[i]);
    }
    g_ps.nimg = ni;
    g_ps.ext = (VkExtent2D){ w, h };
    g_ps.stale = false;
    if (fmt.format != g_ps.pipe_fmt) {
        static const char *const names[P_FXAA] = { "bilinear", "area", "SGSR", "FSR RCAS" };
        for (unsigned i = 0; i < P_FXAA; i++) {
            if (g_ps.pipe[i]) {
                T.vkDestroyPipeline(g_hvk.dev, g_ps.pipe[i], NULL);
                g_ps.pipe[i] = VK_NULL_HANDLE;
            }
            g_ps.pipe[i] = pipeline(g_ps.fs[i], fmt.format, names[i]);
        }
        g_ps.pipe_fmt = fmt.format;
        hle_log("hakuX-hle-back",
                "present: output pipelines format=%d (bilinear=%d area=%d SGSR=%d RCAS=%d)",
                fmt.format, g_ps.pipe[P_BILINEAR] != VK_NULL_HANDLE,
                g_ps.pipe[P_AREA] != VK_NULL_HANDLE, g_ps.pipe[P_SGSR] != VK_NULL_HANDLE,
                g_ps.pipe[P_RCAS] != VK_NULL_HANDLE);
    }
    g_ps.fmt = fmt.format;
    hle_log("hakuX-hle-back", "present: swapchain %ux%u (the window %ux%u, turned %u degrees), %u images, "
            "format %d", w, h, g_ps.wsize.width, g_ps.wsize.height, turn * 90, ni, fmt.format);
    return g_ps.pipe[P_BILINEAR] != VK_NULL_HANDLE;
}

/* ---- each refresh ---- */

static bool wait_value(uint64_t v)
{
    const VkResult r = hle_tl_sem_wait(g_ps.tl, v, 1000000000ull);
    if (r == VK_ERROR_DEVICE_LOST) {
        hle_vk_lose(r, "vkWaitSemaphores (present)");
    }
    return r == VK_SUCCESS;
}

/* the presenter's calls and waits (statistics) */
static int64_t g_submit_ns, g_present_ns, g_wait_ns, g_acquire_ns;

static void lose_window(void)
{
    g_ps.lost_ns = hle_now_ns();
    drop_window();
}

bool hle_present_unchanged(struct ANativeWindow *win, uint64_t key)
{
    if (!g_ps.shown || !win || win != g_ps.win || g_ps.stale || !g_ps.sc || key != g_ps.shown_key ||
        (uint32_t)ANativeWindow_getWidth(win) != g_ps.wsize.width ||
        (uint32_t)ANativeWindow_getHeight(win) != g_ps.wsize.height || hle_disp_gamma_seq() != g_ps.ramp_gen) {
        return false;
    }
    uint32_t gen, swap_id;
    const bool front = hle_disp_front(&gen, &swap_id);
    if (front != g_ps.have) {
        return false;
    }
    return !front || (gen == g_ps.f.gen && swap_id == g_ps.f.swap_id);
}

bool hle_present_begin(struct ANativeWindow *win, uint32_t *w, uint32_t *h)
{
    if (g_ps.failed || !hle_vk_ready() || hle_vk_lost()) {
        return false;
    }
    if (!g_ps.inited) {
        g_ps.inited = true;
        if (!g_hvk.caps.swapchain || !init()) {
            g_ps.failed = true;
            hle_log("hakuX-hle-back", "present: %s: nothing shown",
                    g_hvk.caps.swapchain ? "setup failed" : "no Vulkan swapchain on this device");
            return false;
        }
    }
    /* a lost surface's window may come back at the same address: a while
     * later, whatever window there is gets a new one */
    if (win != g_ps.win && (!win || hle_now_ns() - g_ps.lost_ns > 250000000ll)) {
        drop_window();
        if (win) {
            PFN_vkCreateAndroidSurfaceKHR create_surface = vkCreateAndroidSurfaceKHR;
            if (!create_surface && g_hvk.inst) {
                create_surface = (PFN_vkCreateAndroidSurfaceKHR)
                    vkGetInstanceProcAddr(g_hvk.inst, "vkCreateAndroidSurfaceKHR");
            }
            if (!create_surface) {
                g_ps.lost_ns = hle_now_ns();
                g_ps.failed = true;
                hle_log("hakuX-hle-back", "present: VK_KHR_android_surface entry point is null; refusing null call");
                return false;
            }
            /* The SDL window property is borrowed. Acquire our own reference
             * before creating a Vulkan surface that may survive this frame. */
            ANativeWindow_acquire(win);
            VkAndroidSurfaceCreateInfoKHR sci = { .sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR,
                                                  .window = win };
            const VkResult surface_result = create_surface(g_hvk.inst, &sci, NULL, &g_ps.surf);
            if (surface_result != VK_SUCCESS) {
                g_ps.surf = VK_NULL_HANDLE;
                ANativeWindow_release(win);
                g_ps.lost_ns = hle_now_ns();
                hle_log("hakuX-hle-back", "present: vkCreateAndroidSurfaceKHR failed: %d", surface_result);
            } else {
                g_ps.win = win;
            }
        }
    }
    if (!g_ps.surf) {
        return false;
    }
    const uint32_t ww = (uint32_t)ANativeWindow_getWidth(win), wh = (uint32_t)ANativeWindow_getHeight(win);
    if ((!g_ps.sc || g_ps.stale || ww != g_ps.wsize.width || wh != g_ps.wsize.height) && !make_swapchain(ww, wh)) {
        return false;
    }
    PsFrame *f = &g_ps.fr[g_ps.cur];
    const int64_t tw0 = hle_now_ns();
    if (!wait_value(f->value)) {
        return false;
    }
    const int64_t ta0 = hle_now_ns();
    g_wait_ns += ta0 - tw0;
    VkResult r = T.vkAcquireNextImageKHR(g_hvk.dev, g_ps.sc, 100000000ull, f->acquired, VK_NULL_HANDLE,
                                         &g_ps.idx);
    g_acquire_ns += hle_now_ns() - ta0;
    if (r == VK_ERROR_OUT_OF_DATE_KHR) {
        g_ps.stale = true;
        return false;
    }
    if (r == VK_ERROR_SURFACE_LOST_KHR) {
        lose_window();
        return false;
    }
    if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) {
        if (r != VK_TIMEOUT && r != VK_NOT_READY) {
            hle_vk_check(r, "vkAcquireNextImageKHR");
        }
        return false;                   /* timed out: the next refresh tries again */
    }
    if (g_ps.idx >= g_ps.nimg) {
        hle_log("hakuX-hle-back", "present: acquired image %u of %u", g_ps.idx, g_ps.nimg);
        g_ps.stale = true;
        return false;
    }
    if (r == VK_SUBOPTIMAL_KHR && g_ps.follows) {
        g_ps.stale = true;              /* the panel turned: this image, then another swapchain */
    }
    *w = g_ps.wsize.width;
    *h = g_ps.wsize.height;
    return true;
}

bool hle_present_frame(uint32_t *w, uint32_t *h)
{
    hle_disp_use_begin();               /* the slots' images stay until the submit */
    g_ps.using = true;
    if (g_ps.have && g_ps.f.gen != hle_disp_gen()) {
        g_ps.have = false;              /* the display was made again: its image is gone */
    }
    HleDispFrame f;
    if (hle_disp_acquire(&f) && (!g_ps.have || f.slot != g_ps.f.slot)) {
        /* a new frame: the one shown goes back (its reads are submitted, and
         * the render thread's next write of it comes after them) */
        if (g_ps.have) {
            hle_disp_release(g_ps.f.slot);
        }
        g_ps.f = f;
        g_ps.have = true;
        g_ps.serial++;
    }
    if (g_ps.have) {
        *w = g_ps.f.w;
        *h = g_ps.f.h;
    }
    return g_ps.have;
}

/* The FXAA image at the frame's size. */
static bool fxaa_target(uint32_t w, uint32_t h)
{
    if (g_ps.fx_img && g_ps.fx_w == w && g_ps.fx_h == h) {
        return true;
    }
    if (g_ps.fx_img) {
        wait_value(g_ps.submitted);
        T.vkDestroyImageView(g_hvk.dev, g_ps.fx_view, NULL);
        T.vkDestroyImage(g_hvk.dev, g_ps.fx_img, NULL);
        T.vkFreeMemory(g_hvk.dev, g_ps.fx_mem, NULL);
        g_ps.fx_img = VK_NULL_HANDLE;
    }
    VkImageCreateInfo ici = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, .imageType = VK_IMAGE_TYPE_2D,
        .format = VK_FORMAT_R8G8B8A8_UNORM, .extent = { w, h, 1 }, .mipLevels = 1, .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT, .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED };
    if (T.vkCreateImage(g_hvk.dev, &ici, NULL, &g_ps.fx_img) != VK_SUCCESS) {
        g_ps.fx_img = VK_NULL_HANDLE;
        return false;
    }
    VkMemoryRequirements mr;
    T.vkGetImageMemoryRequirements(g_hvk.dev, g_ps.fx_img, &mr);
    int mt = hle_vk_memtype(mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0);
    VkMemoryAllocateInfo mai = { .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
                                 .allocationSize = mr.size, .memoryTypeIndex = (uint32_t)mt };
    VkImageViewCreateInfo vci = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO, .image = g_ps.fx_img,
        .viewType = VK_IMAGE_VIEW_TYPE_2D, .format = VK_FORMAT_R8G8B8A8_UNORM,
        .subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 } };
    if (mt < 0 || T.vkAllocateMemory(g_hvk.dev, &mai, NULL, &g_ps.fx_mem) != VK_SUCCESS ||
        T.vkBindImageMemory(g_hvk.dev, g_ps.fx_img, g_ps.fx_mem, 0) != VK_SUCCESS ||
        T.vkCreateImageView(g_hvk.dev, &vci, NULL, &g_ps.fx_view) != VK_SUCCESS) {
        hle_log("hakuX-hle-back", "present: no %ux%u FXAA image: FXAA off", w, h);
        T.vkDestroyImage(g_hvk.dev, g_ps.fx_img, NULL);
        g_ps.fx_img = VK_NULL_HANDLE;
        g_ps.pipe[P_FXAA] = VK_NULL_HANDLE;
        return false;
    }
    g_ps.fx_w = w;
    g_ps.fx_h = h;
    g_ps.fx_serial = 0;
    return true;
}

/* FSR's EASU target: the frame scaled up to its place in the window. */
static bool fsr_target(uint32_t w, uint32_t h)
{
    if (g_ps.fs_img && g_ps.fs_w == w && g_ps.fs_h == h) {
        return true;
    }
    if (g_ps.fs_img) {
        wait_value(g_ps.submitted);
        if (g_ps.fs_view) T.vkDestroyImageView(g_hvk.dev, g_ps.fs_view, NULL);
        T.vkDestroyImage(g_hvk.dev, g_ps.fs_img, NULL);
        if (g_ps.fs_mem) T.vkFreeMemory(g_hvk.dev, g_ps.fs_mem, NULL);
        g_ps.fs_img = VK_NULL_HANDLE;
        g_ps.fs_view = VK_NULL_HANDLE;
        g_ps.fs_mem = VK_NULL_HANDLE;
    }
    VkImageCreateInfo ici = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, .imageType = VK_IMAGE_TYPE_2D,
        .format = VK_FORMAT_R8G8B8A8_UNORM, .extent = { w, h, 1 }, .mipLevels = 1, .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT, .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED };
    if (T.vkCreateImage(g_hvk.dev, &ici, NULL, &g_ps.fs_img) != VK_SUCCESS) {
        g_ps.fs_img = VK_NULL_HANDLE;
        return false;
    }
    VkMemoryRequirements mr;
    T.vkGetImageMemoryRequirements(g_hvk.dev, g_ps.fs_img, &mr);
    int mt = hle_vk_memtype(mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0);
    VkMemoryAllocateInfo mai = { .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
                                 .allocationSize = mr.size, .memoryTypeIndex = (uint32_t)mt };
    VkImageViewCreateInfo vci = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO, .image = g_ps.fs_img,
        .viewType = VK_IMAGE_VIEW_TYPE_2D, .format = VK_FORMAT_R8G8B8A8_UNORM,
        .subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 } };
    if (mt < 0 || T.vkAllocateMemory(g_hvk.dev, &mai, NULL, &g_ps.fs_mem) != VK_SUCCESS ||
        T.vkBindImageMemory(g_hvk.dev, g_ps.fs_img, g_ps.fs_mem, 0) != VK_SUCCESS ||
        T.vkCreateImageView(g_hvk.dev, &vci, NULL, &g_ps.fs_view) != VK_SUCCESS) {
        hle_log("hakuX-hle-back", "present: no %ux%u FSR image: FSR off", w, h);
        if (g_ps.fs_view) T.vkDestroyImageView(g_hvk.dev, g_ps.fs_view, NULL);
        if (g_ps.fs_mem) T.vkFreeMemory(g_hvk.dev, g_ps.fs_mem, NULL);
        T.vkDestroyImage(g_hvk.dev, g_ps.fs_img, NULL);
        g_ps.fs_img = VK_NULL_HANDLE;
        g_ps.fs_view = VK_NULL_HANDLE;
        g_ps.fs_mem = VK_NULL_HANDLE;
        /* Keep the EASU pipeline: allocation may succeed on a later frame
         * (for example after the window extent changes or memory pressure
         * clears). A transient target failure must not permanently disable FSR. */
        return false;
    }
    g_ps.fs_w = w;
    g_ps.fs_h = h;
    g_ps.fs_serial = 0;
    return true;
}

static void barrier(VkCommandBuffer cb, VkImage img, VkImageLayout from, VkImageLayout to,
                    VkPipelineStageFlags2 src, VkAccessFlags2 src_access, VkPipelineStageFlags2 dst,
                    VkAccessFlags2 dst_access)
{
    VkImageMemoryBarrier2 b = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER_2, .srcStageMask = src,
        .srcAccessMask = src_access, .dstStageMask = dst, .dstAccessMask = dst_access,
        .oldLayout = from, .newLayout = to,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED, .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .image = img, .subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 } };
    VkDependencyInfo di = { .sType = VK_STRUCTURE_TYPE_DEPENDENCY_INFO, .imageMemoryBarrierCount = 1,
                            .pImageMemoryBarriers = &b };
    T.vkCmdPipelineBarrier2(cb, &di);
}

static bool nis_output_target(uint32_t w, uint32_t h)
{
    if (g_ps.nis_out && g_ps.nis_out_w == w && g_ps.nis_out_h == h) return true;
    if (g_ps.nis_out) {
        idle();
        nis_image_drop(&g_ps.nis_out, &g_ps.nis_out_mem, &g_ps.nis_out_view);
    }
    VkFormatProperties fp; vkGetPhysicalDeviceFormatProperties(g_hvk.phys, VK_FORMAT_R8G8B8A8_UNORM, &fp);
    const VkFormatFeatureFlags need = VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
    if ((fp.optimalTilingFeatures & need) != need) return false;
    if (!nis_image_make(w, h, VK_FORMAT_R8G8B8A8_UNORM,
            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
            &g_ps.nis_out, &g_ps.nis_out_mem, &g_ps.nis_out_view)) return false;
    g_ps.nis_out_w = w; g_ps.nis_out_h = h; g_ps.nis_out_layout = VK_IMAGE_LAYOUT_UNDEFINED;
    return true;
}

static bool nis_dispatch(VkCommandBuffer cb, PsFrame *frame, VkImageView input,
                         uint32_t input_w, uint32_t input_h, uint32_t output_w, uint32_t output_h,
                         float sharpness)
{
    /* Report the exact reason when NIS cannot run; do not silently fall back. */
    static int last_failure = -1;
    static uint32_t last_w, last_h, last_ow, last_oh;
    static int last_sharpness = -1;
    int failure = 0;
    if (!g_ps.nis_resources_ready || !g_ps.nis_pipe || !frame->nis_set ||
        !g_ps.nis_config_map || !g_ps.nis_coef_scale_view || !g_ps.nis_coef_usm_view) {
        failure = 1; /* compute pipeline/descriptors/coefficients not ready */
    } else if (!input || input_w < 2 || input_h < 2 ||
               output_w < 2 || output_h < 2 || output_w > 8192 || output_h > 8192) {
        failure = 2; /* invalid source or destination dimensions */
    } else if (!nis_output_target(output_w, output_h)) {
        failure = 3; /* unsupported storage format or output allocation failed */
    }
    if (failure) {
        nis_status(2, 0);
        nis_status(3, failure);
        if (failure != last_failure) {
            hle_log("hakuX-hle-back", "NIS dispatch skipped: reason=%d ready=%d pipe=%d set=%d config=%d coef=%d/%d input=%ux%u output=%ux%u",
                    failure, g_ps.nis_resources_ready, g_ps.nis_pipe != VK_NULL_HANDLE,
                    frame->nis_set != VK_NULL_HANDLE, g_ps.nis_config_map != NULL,
                    g_ps.nis_coef_scale_view != VK_NULL_HANDLE, g_ps.nis_coef_usm_view != VK_NULL_HANDLE,
                    input_w, input_h, output_w, output_h);
            last_failure = failure;
        }
        return false;
    }
    uint8_t *config = g_ps.nis_config_map + (size_t)(frame - g_ps.fr) * g_ps.nis_config_stride;
    if (!hle_nis_update_scaler_config(config, sizeof(float) * 64, sharpness,
                                      input_w, input_h, output_w, output_h)) {
        nis_status(2, 0);
        nis_status(3, 4);
        if (last_failure != 4) hle_log("hakuX-hle-back", "NIS dispatch skipped: configuration rejected (%ux%u -> %ux%u)",
                                       input_w, input_h, output_w, output_h);
        last_failure = 4;
        return false;
    }
    last_failure = 0;
    nis_status(3, 0);
    nis_status(2, 1);
    VkDescriptorBufferInfo buffer = { g_ps.nis_config,
        (VkDeviceSize)(frame - g_ps.fr) * g_ps.nis_config_stride, sizeof(float) * 64 };
    VkDescriptorImageInfo sampler = { .sampler = g_ps.smp };
    VkDescriptorImageInfo images[4] = {
        { .imageView = input, .imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL },
        { .imageView = g_ps.nis_out_view, .imageLayout = VK_IMAGE_LAYOUT_GENERAL },
        { .imageView = g_ps.nis_coef_scale_view, .imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL },
        { .imageView = g_ps.nis_coef_usm_view, .imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL }
    };
    VkWriteDescriptorSet writes[6] = {
        { .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = frame->nis_set, .dstBinding = 0,
          .descriptorCount = 1, .descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, .pBufferInfo = &buffer },
        { .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = frame->nis_set, .dstBinding = 1,
          .descriptorCount = 1, .descriptorType = VK_DESCRIPTOR_TYPE_SAMPLER, .pImageInfo = &sampler },
        { .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = frame->nis_set, .dstBinding = 2,
          .descriptorCount = 1, .descriptorType = VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, .pImageInfo = &images[0] },
        { .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = frame->nis_set, .dstBinding = 3,
          .descriptorCount = 1, .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, .pImageInfo = &images[1] },
        { .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = frame->nis_set, .dstBinding = 4,
          .descriptorCount = 1, .descriptorType = VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, .pImageInfo = &images[2] },
        { .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = frame->nis_set, .dstBinding = 5,
          .descriptorCount = 1, .descriptorType = VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, .pImageInfo = &images[3] }
    };
    T.vkUpdateDescriptorSets(g_hvk.dev, ARRAY_SIZE(writes), writes, 0, NULL);
    const VkPipelineStageFlags2 old_stage = g_ps.nis_out_layout == VK_IMAGE_LAYOUT_UNDEFINED ?
        VK_PIPELINE_STAGE_2_NONE : VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT;
    const VkAccessFlags2 old_access = g_ps.nis_out_layout == VK_IMAGE_LAYOUT_UNDEFINED ? 0 : VK_ACCESS_2_SHADER_SAMPLED_READ_BIT;
    barrier(cb, g_ps.nis_out, g_ps.nis_out_layout, VK_IMAGE_LAYOUT_GENERAL, old_stage, old_access,
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
    T.vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, g_ps.nis_pipe);
    T.vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, g_ps.nis_pl, 0, 1, &frame->nis_set, 0, NULL);
    uint32_t gx = 0, gy = 0;
    hle_nis_get_dispatch_size(output_w, output_h, &gx, &gy);
    if (!gx || !gy) return false;
    T.vkCmdDispatch(cb, gx, gy, 1);
    barrier(cb, g_ps.nis_out, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
            VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
    g_ps.nis_out_layout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    const int sharp_pct = (int)(sharpness * 100.0f + 0.5f);
    if (input_w != last_w || input_h != last_h || output_w != last_ow ||
        output_h != last_oh || sharp_pct != last_sharpness) {
        hle_log("hakuX-hle-back", "NIS dispatch EXECUTED: %ux%u -> %ux%u, sharpness=%d%%, groups=%ux%u",
                input_w, input_h, output_w, output_h, sharp_pct, gx, gy);
        last_w = input_w; last_h = input_h; last_ow = output_w; last_oh = output_h;
        last_sharpness = sharp_pct;
    }
    return true;
}
/* A quad of pipeline p over rect (clip space) into the rendering open. */
static void quad(VkCommandBuffer cb, PsFrame *f, unsigned set, VkPipeline p, VkImageView src,
                 const float pc[12], uint32_t w, uint32_t h)
{
    VkDescriptorImageInfo ii = { g_ps.smp, src, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL };
    VkDescriptorBufferInfo bi = { g_ps.ubo, (VkDeviceSize)(f - g_ps.fr) * PS_RAMP, 768 };
    VkWriteDescriptorSet wr[2] = {
        { .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = f->set[set], .dstBinding = 0,
          .descriptorCount = 1, .descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, .pImageInfo = &ii },
        { .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = f->set[set], .dstBinding = 1,
          .descriptorCount = 1, .descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, .pBufferInfo = &bi } };
    T.vkUpdateDescriptorSets(g_hvk.dev, 2, wr, 0, NULL);
    VkViewport vp = { 0, 0, (float)w, (float)h, 0.0f, 1.0f };
    VkRect2D sc = { { 0, 0 }, { w, h } };
    T.vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, p);
    T.vkCmdSetViewport(cb, 0, 1, &vp);
    T.vkCmdSetScissor(cb, 0, 1, &sc);
    T.vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, g_ps.pl, 0, 1, &f->set[set], 0, NULL);
    T.vkCmdPushConstants(cb, g_ps.pl, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, 48, pc);
    T.vkCmdDraw(cb, 4, 1, 0, 0);
}

static void rendering(VkCommandBuffer cb, VkImageView v, uint32_t w, uint32_t h, bool clear)
{
    VkRenderingAttachmentInfo ca = {
        .sType = VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO, .imageView = v,
        .imageLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
        .loadOp = clear ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_DONT_CARE,
        .storeOp = VK_ATTACHMENT_STORE_OP_STORE, .clearValue = { .color = { { 0, 0, 0, 1 } } } };
    VkRenderingInfo ri = { .sType = VK_STRUCTURE_TYPE_RENDERING_INFO, .renderArea = { { 0, 0 }, { w, h } },
                           .layerCount = 1, .colorAttachmentCount = 1, .pColorAttachments = &ca };
    T.vkCmdBeginRendering(cb, &ri);
}

/* How the frame meets the window, logged when that changes. */
static void log_path(unsigned p, bool fxaa, bool nis, int upscale, bool use_fsr,
                      bool fsr_easu, bool fsr_rcas, uint32_t vw, uint32_t vh)
{
    static unsigned s_p = ~0u;
    static bool s_fxaa, s_nis, s_use_fsr, s_fsr_easu, s_fsr_rcas;
    static int s_upscale = -1;
    static uint32_t s_w, s_h, s_vw, s_vh;
    if (p == s_p && fxaa == s_fxaa && nis == s_nis && upscale == s_upscale &&
        use_fsr == s_use_fsr && fsr_easu == s_fsr_easu && fsr_rcas == s_fsr_rcas &&
        g_ps.f.w == s_w && g_ps.f.h == s_h && vw == s_vw && vh == s_vh) {
        return;
    }
    s_p = p;
    s_fxaa = fxaa;
    s_nis = nis;
    s_upscale = upscale;
    s_use_fsr = use_fsr;
    s_fsr_easu = fsr_easu;
    s_fsr_rcas = fsr_rcas;
    s_w = g_ps.f.w;
    s_h = g_ps.f.h;
    s_vw = vw;
    s_vh = vh;

    const char *path = nis
        ? (upscale == HLE_UPSCALE_FSR ? "NVIDIA Image Scaling (FSR request bypassed)" : "NVIDIA Image Scaling")
        : fsr_easu ? (fsr_rcas ? "FSR 1.0 (EASU + RCAS)" : "FSR 1.0 (EASU)")
        : fsr_rcas ? "FSR 1.0 (RCAS only)"
        : use_fsr ? "FSR 1.0 selected (no EASU upscale needed)"
        : upscale == HLE_UPSCALE_FSR && !g_ps.pipe[P_EASU] ? "FSR unavailable (EASU pipeline missing)"
        : upscale == HLE_UPSCALE_FSR && p == P_AREA ? "averaged down (FSR bypassed)"
        : p == P_AREA ? "averaged down"
        : p == P_RCAS ? "FSR 1.0 (RCAS)"
        : p == P_SGSR ? "scaled up by SGSR"
        : "bilinear";
    hle_log("hakuX-hle-back", "present: %ux%u%s into %ux%u: %s", s_w, s_h,
            fxaa ? " after FXAA" : "", vw, vh, path);
}

void hle_present_end(float sx, float sy, bool fxaa, int upscale, bool average, bool cas, float cas_sharpness, bool nis, float nis_sharpness, uint64_t key)
{
    /* FSR destination dimensions are independent of NIS output dimensions. */
    nis_status(12, 0);
    nis_status(13, 0);
    g_ps.shown = false;
    PsFrame *f = &g_ps.fr[g_ps.cur];
    const uint32_t ww = g_ps.wsize.width, wh = g_ps.wsize.height, iw = g_ps.ext.width, ih = g_ps.ext.height;
    uint8_t rgb[3][256];
    bool identity;
    if (hle_disp_gamma(rgb, &identity, &g_ps.ramp_gen)) {
        for (unsigned i = 0; i < 256; i++) {
            for (unsigned c = 0; c < 3; c++) {
                g_ps.ramp[c][i] = identity ? (uint8_t)i : rgb[c][i];
            }
        }
        for (unsigned i = 0; i < PS_FRAMES; i++) {
            g_ps.fr[i].ramp_stale = true;  /* a new ramp shows now, with no new frame */
        }
    }
    if (f->ramp_stale) {
        memcpy(g_ps.ubo_map + (size_t)g_ps.cur * PS_RAMP, g_ps.ramp, 768);
        f->ramp_stale = false;
    }
    T.vkResetCommandPool(g_hvk.dev, f->pool, 0);
    VkCommandBuffer cb = f->cb;
    VkCommandBufferBeginInfo bi = { .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
                                    .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT };
    T.vkBeginCommandBuffer(cb, &bi);
    bool lsfg_capture_ready = g_ps.have &&
        hle_lsfg_shader_count() == ARRAY_SIZE(k_lsfg_passes) &&
        lsfg_modules_init() && lsfg_pipelines_init() &&
        lsfg_history_ensure(g_ps.f.w, g_ps.f.h) &&
        lsfg_flow_ensure(g_ps.f.w, g_ps.f.h, g_ps.lsfg_flow_scale) &&
        lsfg_mipmap_descriptors_update() &&
        lsfg_alpha_ensure() && lsfg_beta_ensure() && lsfg_gamma_ensure() &&
        lsfg_delta_ensure();
    VkImageView src = g_ps.have ? g_ps.f.view : VK_NULL_HANDLE;
    if (lsfg_capture_ready && lsfg_history_capture(cb) &&
        g_ps.lsfg_history_count >= 3 &&
        lsfg_mipmap_dispatch(cb, (unsigned)(g_ps.serial % ARRAY_SIZE(g_ps.lsfg_mipmap_sets))))
        if (lsfg_alpha_dispatch(cb, g_ps.serial) && lsfg_beta_dispatch(cb, g_ps.serial))
            if (lsfg_gamma_dispatch(cb, g_ps.serial, 0))
                lsfg_delta_dispatch(cb, g_ps.serial, 0);
    bool fxaa_done = false;
    /* The frame's writes need no barrier here: each writer of a display
     * image (the swap's blit, the gamma pass, the overlay) ends with one
     * into SHADER_READ_ONLY over all later commands of the queue, ours too
     * (a second queue: the CPU saw the frame complete before it was
     * presented). A barrier of our own drained the GPU at every refresh; a
     * wait on the render timeline at the frame's value sent the submit down
     * a slow path on Qualcomm's driver (Halo 2 at 30 fps, Adreno 830). */
    if (g_ps.have) {
        if (fxaa && g_ps.pipe[P_FXAA] && fxaa_target(g_ps.f.w, g_ps.f.h)) {
            if (g_ps.fx_serial != g_ps.serial) {
                barrier(cb, g_ps.fx_img, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                        VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, 0,
                        VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);
                rendering(cb, g_ps.fx_view, g_ps.f.w, g_ps.f.h, false);
                static const float whole[12] = { -1.0f, -1.0f, 1.0f, 1.0f, 0, 0, 0, 0, 1.0f, 0, 0, 1.0f };
                quad(cb, f, 1, g_ps.pipe[P_FXAA], g_ps.f.view, whole, g_ps.f.w, g_ps.f.h);
                T.vkCmdEndRendering(cb);
                barrier(cb, g_ps.fx_img, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                        VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT, VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                        VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
                g_ps.fx_serial = g_ps.serial;
            }
            src = g_ps.fx_view;
            fxaa_done = true;
        }
    }
    bool nis_done = false;
    bool nis_sharpen_only = false;
    nis_status(1, nis ? 1 : 0);
    nis_status(2, 0);
    nis_status(3, 0);
    nis_status(4, g_ps.have ? (int)g_ps.f.w : 0);
    nis_status(5, g_ps.have ? (int)g_ps.f.h : 0);
    nis_status(6, 0);
    nis_status(7, 0);
    nis_status(8, 0);
    nis_status(9, cas ? 1 : 0);
    nis_status(10, (int)(cas_sharpness * 100.0f + 0.5f));
    nis_status(11, 0);
    if (g_ps.have && nis) {
        const float nw = sx * (float)ww, nh = sy * (float)wh;
        const uint32_t target_w = (uint32_t)(nw + 0.5f), target_h = (uint32_t)(nh + 0.5f);
        const bool requires_downscale = target_w <= g_ps.f.w || target_h <= g_ps.f.h;
        if (requires_downscale) {
            /* Use the SDK's 1:1 path whenever either destination axis does not
             * enlarge. This preserves the NIS sharpened image as the source
             * for the final area/bilinear presentation pass. */
            nis_status(6, (int)g_ps.f.w);
            nis_status(7, (int)g_ps.f.h);
            if (nis_dispatch(cb, f, src, g_ps.f.w, g_ps.f.h,
                             g_ps.f.w, g_ps.f.h, nis_sharpness)) {
                src = g_ps.nis_out_view;
                nis_done = true;
                nis_sharpen_only = true;
            }
        } else {
            /* NIS supports upscaling by at most 2x per axis. For larger
             * display scales, render a <=2x intermediate and let final
             * presentation perform the remaining enlargement. */
            const uint32_t nis_w = MIN(target_w, g_ps.f.w * 2u);
            const uint32_t nis_h = MIN(target_h, g_ps.f.h * 2u);
            const float nrx = (float)g_ps.f.w / (float)nis_w;
            const float nry = (float)g_ps.f.h / (float)nis_h;
            nis_status(6, (int)nis_w);
            nis_status(7, (int)nis_h);
            if (nis_w >= g_ps.f.w && nis_h >= g_ps.f.h &&
                nrx >= 0.5f && nrx <= 1.0f && nry >= 0.5f && nry <= 1.0f) {
                if (nis_dispatch(cb, f, src, g_ps.f.w, g_ps.f.h, nis_w, nis_h, nis_sharpness)) {
                    src = g_ps.nis_out_view;
                    nis_done = true;
                }
            } else {
                nis_status(3, 5);
                static bool reported_scale_range;
                if (!reported_scale_range) {
                    hle_log("hakuX-hle-back", "NIS dispatch skipped: unsupported upscale target %ux%u from %ux%u",
                            target_w, target_h, g_ps.f.w, g_ps.f.h);
                    reported_scale_range = true;
                }
            }
        }
    }
    /* FSR 1.0 (HAKUX_FSR): use EASU when the frame must be enlarged.
     * At native/output resolution, keep FSR selected and run RCAS directly
     * on the frame. RCAS also sharpens the final image if EASU cannot allocate. */
    bool use_fsr = false;
    bool fsr_easu = false;
#if HAKUX_FSR
    if (g_ps.have && !nis_done && upscale == HLE_UPSCALE_FSR && g_ps.pipe[P_EASU]) {
        const float fvw = sx * (float)ww, fvh = sy * (float)wh;
        const bool fdown = (g_ps.f.w > fvw * 1.01f || g_ps.f.h > fvh * 1.01f) && average && g_ps.pipe[P_AREA];
        const bool fup = (float)g_ps.f.w < fvw * 0.99f || (float)g_ps.f.h < fvh * 0.99f;
        const uint32_t ow = (uint32_t)(fvw + 0.5f), oh = (uint32_t)(fvh + 0.5f);
        nis_status(12, (int)ow);
        nis_status(13, (int)oh);
        if (!fdown) {
            use_fsr = true;
            if (fup && ow >= 2 && oh >= 2 && ow <= 8192 && oh <= 8192 && fsr_target(ow, oh)) {
                if (g_ps.fs_serial != g_ps.serial || g_ps.fs_fxaa != fxaa_done) {
                    barrier(cb, g_ps.fs_img, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                            VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, 0,
                            VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);
                    rendering(cb, g_ps.fs_view, ow, oh, false);
                    const float easu[12] = { -1.0f, -1.0f, 1.0f, 1.0f,
                                             1.0f / (float)g_ps.f.w, 1.0f / (float)g_ps.f.h,
                                             (float)g_ps.f.w, (float)g_ps.f.h,
                                             1.0f, 0.0f, 0.0f, 1.0f };
                    quad(cb, f, 2, g_ps.pipe[P_EASU], src, easu, ow, oh);
                    T.vkCmdEndRendering(cb);
                    barrier(cb, g_ps.fs_img, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                            VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT, VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                            VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
                    g_ps.fs_serial = g_ps.serial;
                    g_ps.fs_fxaa = fxaa_done;
                }
                fsr_easu = true;
            }
        }
    }
#endif
    VkImage dst = g_ps.img[g_ps.idx];
    barrier(cb, dst, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
            VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, 0, VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
            VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);
    rendering(cb, g_ps.view[g_ps.idx], iw, ih, true);
    if (g_ps.have) {
        /* the frame's pixels per window pixel: over 1 (either axis) averaged
         * down, under 1 scaled up as chosen */
        const float vw = sx * (float)ww, vh = sy * (float)wh;
        const float rx = (float)g_ps.f.w / vw, ry = (float)g_ps.f.h / vh;
        unsigned p = P_BILINEAR;
        float pc[12] = { -sx, -sy, sx, sy, 0.0f, 0.0f, 0.0f, 0.0f, g_ps.rot[0], g_ps.rot[1], g_ps.rot[2], g_ps.rot[3] };
        if ((!nis_done || nis_sharpen_only) && (rx > 1.01f || ry > 1.01f) && average && g_ps.pipe[P_AREA]) {
            p = P_AREA;
            pc[4] = rx;
            pc[5] = ry;
        } else if (use_fsr) {
            if (fsr_easu) {
                src = g_ps.fs_view;
            }
            /* EASU is the upscaler and must remain useful even if the
             * output-format RCAS pipeline is unavailable. RCAS/CAS is a
             * separate optional sharpening pass; never let its failure
             * disable the actual FSR enlargement. */
            if (cas && g_ps.pipe[P_RCAS]) {
                p = P_RCAS;
                pc[4] = exp2f(-cas_sharpness); /* RCAS strength in stops */
            } else {
                p = P_BILINEAR; /* EASU is complete; CAS is optional */
                static bool reported_rcas_unavailable;
                if (cas && !g_ps.pipe[P_RCAS] && !reported_rcas_unavailable) {
                    hle_log("hakuX-hle-back",
                            "FSR: EASU active; RCAS pipeline unavailable, using neutral final copy");
                    reported_rcas_unavailable = true;
                }
            }
        } else if (!nis_done && (rx < 0.99f || ry < 0.99f) && upscale == HLE_UPSCALE_SGSR && g_ps.pipe[P_SGSR]) {
            p = P_SGSR;
            pc[4] = 1.0f / (float)g_ps.f.w;
            pc[5] = 1.0f / (float)g_ps.f.h;
            pc[6] = (float)g_ps.f.w;
            pc[7] = (float)g_ps.f.h;
        }
        quad(cb, f, 0, g_ps.pipe[p], src, pc, iw, ih);
        const bool fsr_rcas = use_fsr && cas && g_ps.pipe[P_RCAS] && p == P_RCAS;
        nis_status(8, nis_done ? (nis_sharpen_only ? 4 : 3) :
                   fsr_easu ? (fsr_rcas ? 6 : 5) : fsr_rcas ? 7 : use_fsr ? 8 :
                   p == P_AREA ? 1 : p == P_SGSR ? 2 : 0);
        nis_status(11, fsr_rcas ? 1 : 0);
        log_path(p, fxaa_done, nis_done, upscale, use_fsr, fsr_easu, fsr_rcas,
                 (uint32_t)(vw + 0.5f), (uint32_t)(vh + 0.5f));
    }
    T.vkCmdEndRendering(cb);
    barrier(cb, dst, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
            VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
            VK_PIPELINE_STAGE_2_NONE, 0);
    T.vkEndCommandBuffer(cb);

    /* The present waits for the pass on the GPU: Turnip's hands the
     * compositor a fence over it (Mesa's vk_android.c: a submit and a sync
     * file), and the window's thread goes on to the next refresh. Waiting
     * for the pass here, behind the game's frames on the one queue, held
     * the thread past refreshes whenever the GPU was busy, and the next
     * front frame was replaced before it was picked up ("UI missed").
     * HLE_QUIRK_PRESENT_CPU_WAIT: the image is presented once it's drawn,
     * with nothing left to wait for. Presenting with a semaphore to wait on
     * made Android's present ask Qualcomm's driver for a fence over it,
     * which waited (3-6 ms a refresh) with the queue's lock held, the render
     * thread's submits waiting on it; Halo 2 lost 2% of its frames to GL's
     * presenter. The wait is ours there, unlocked. */
    const bool cpu_wait = hle_quirk(HLE_QUIRK_PRESENT_CPU_WAIT);
    const uint64_t value = g_ps.submitted + 1;
    VkSemaphoreSubmitInfo wait = { .sType = VK_STRUCTURE_TYPE_SEMAPHORE_SUBMIT_INFO, .semaphore = f->acquired,
                                   .stageMask = VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT };
    VkSemaphoreSubmitInfo sig[2] = {
        { .sType = VK_STRUCTURE_TYPE_SEMAPHORE_SUBMIT_INFO, .semaphore = g_ps.tl, .value = value,
          .stageMask = VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT },
        { .sType = VK_STRUCTURE_TYPE_SEMAPHORE_SUBMIT_INFO, .semaphore = g_ps.drawn[g_ps.idx],
          .stageMask = VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT } };
    VkCommandBufferSubmitInfo cbi = { .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_SUBMIT_INFO, .commandBuffer = cb };
    VkSubmitInfo2 si = { .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO_2, .waitSemaphoreInfoCount = 1,
                         .pWaitSemaphoreInfos = &wait, .commandBufferInfoCount = 1,
                         .pCommandBufferInfos = &cbi, .signalSemaphoreInfoCount = cpu_wait ? 1 : 2,
                         .pSignalSemaphoreInfos = sig };
    VkPresentInfoKHR pi = { .sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR, .waitSemaphoreCount = cpu_wait ? 0 : 1,
                            .pWaitSemaphores = &g_ps.drawn[g_ps.idx], .swapchainCount = 1,
                            .pSwapchains = &g_ps.sc, .pImageIndices = &g_ps.idx };
    hle_vk_queue_lock();
    const int64_t ts0 = hle_now_ns();
    VkResult r = T.vkQueueSubmit2(g_hvk.queue, 1, &si, VK_NULL_HANDLE);
    g_submit_ns += hle_now_ns() - ts0;
    hle_vk_queue_unlock();
    if (r == VK_SUCCESS) {
        g_ps.submitted = value;
        f->value = value;
    } else {
        hle_vk_check(r, "vkQueueSubmit2 (present)");
    }
    if (g_ps.using) {
        g_ps.using = false;
        hle_disp_use_end();
    }
    VkResult pr = r;
    if (r == VK_SUCCESS) {
        if (cpu_wait) {
            const int64_t tw0 = hle_now_ns();
            wait_value(value);          /* (timed out: presented all the same, or the image stays ours) */
            g_wait_ns += hle_now_ns() - tw0;
        }
        hle_vk_queue_lock();
        const int64_t tp0 = hle_now_ns();
        pr = T.vkQueuePresentKHR(g_hvk.queue, &pi);
        g_present_ns += hle_now_ns() - tp0;
        hle_vk_queue_unlock();
    }
    if (pr == VK_SUCCESS || pr == VK_SUBOPTIMAL_KHR) {
        g_ps.shown = true;
        g_ps.shown_key = key;
    }
    if (pr == VK_ERROR_OUT_OF_DATE_KHR || (pr == VK_SUBOPTIMAL_KHR && g_ps.follows)) {
        g_ps.stale = true;
    } else if (pr == VK_ERROR_SURFACE_LOST_KHR) {
        lose_window();
    } else if (pr == VK_ERROR_DEVICE_LOST) {
        hle_vk_lose(pr, "vkQueuePresentKHR");
    }
    g_ps.cur = (g_ps.cur + 1) % PS_FRAMES;
    static int64_t s_t0;
    const int64_t now = hle_now_ns();
    if (!s_t0) {
        s_t0 = now;
    } else if (now - s_t0 >= 10000000000ll) {
        const double s = (now - s_t0) / 1e9;
        hle_log("hakuX-hle-back", "present: the queue's lock waited for %.1f ms/s; the presenter's submits "
                "held it %.1f ms/s, its presents %.1f; it waited %.1f ms/s for its passes (%s), %.1f for images",
                hle_vk_queue_wait_ns() / 1e6 / s, g_submit_ns / 1e6 / s, g_present_ns / 1e6 / s,
                g_wait_ns / 1e6 / s,
                hle_quirk(HLE_QUIRK_PRESENT_CPU_WAIT) ? "each before its present" : "the present waits on the GPU",
                g_acquire_ns / 1e6 / s);
        g_submit_ns = g_present_ns = g_wait_ns = g_acquire_ns = 0;
        s_t0 = now;
    }
}
