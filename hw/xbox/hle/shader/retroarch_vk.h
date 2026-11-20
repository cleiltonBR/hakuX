#ifndef HW_XBOX_HLE_SHADER_RETROARCH_VK_H
#define HW_XBOX_HLE_SHADER_RETROARCH_VK_H

#include "hw/xbox/hle/shader/retroarch_runtime.h"
#include "hw/xbox/hle/shader/retroarch_spirv.h"
#include "hw/xbox/hle/vk/vkdev.h"

#define HLE_RA_TARGET_MAX_PIXELS (16384u * 16384u)

typedef struct HleRaTarget {
    VkImage image;
    VkDeviceMemory memory;
    VkImageView view;
    VkRenderPass render_pass;
    VkFramebuffer framebuffer;
    VkImageLayout layout;
    uint32_t width, height;
} HleRaTarget;

typedef struct HleRaPassGpu {
    uint32_t descriptor_count;
    HleRaSpirvDescriptor descriptors[HLE_RA_SPIRV_MAX_DESCRIPTORS];
    VkShaderModule fragment;
    VkPipeline pipeline;
    VkSampler sampler;
    VkDescriptorPool descriptor_pool;
    VkDescriptorSet descriptor_sets[8];
    VkDescriptorSetLayout set_layouts[8];
    VkPipelineLayout pipeline_layout;
    uint32_t set_count;
} HleRaPassGpu;

typedef struct HleRaTargets {
    unsigned count;
    HleRaTarget passes[HLE_RA_MAX_PASSES];
} HleRaTargets;

/* Allocate one RGBA8 render/sample target, compatible render pass and framebuffer
 * for each preset pass. Dimensions follow RetroArch's source/absolute/viewport
 * scale metadata. Temporal
 * frame_count values other than one are rejected until history is implemented.
 * The output must be zero-initialized or previously released. */
bool hle_ra_targets_create(const HleRaProgram *program,
                           uint32_t source_width, uint32_t source_height,
                           uint32_t viewport_width, uint32_t viewport_height,
                           HleRaTargets *out);
void hle_ra_targets_destroy(HleRaTargets *targets);

/* Compile one preset fragment pass and create its reflected descriptor and
 * pipeline layouts. Unsupported descriptor layouts fail closed. The output
 * must be zero-initialized or hold a previously created pass. */
bool hle_ra_pass_gpu_create(const HleRaProgram *program, unsigned pass_index,
                            const HleRaTarget *target, HleRaPassGpu *out);
void hle_ra_pass_gpu_destroy(HleRaPassGpu *pass);
/* Bind the preceding pass output (or source image) to reflected sampled-image
 * descriptors. Unsupported buffer/storage descriptors fail closed. */
bool hle_ra_pass_gpu_update_input(const HleRaProgram *program, unsigned pass_index,
                                  const HleRaTarget *input, HleRaPassGpu *pass);
bool hle_ra_pass_gpu_record(VkCommandBuffer cb, const HleRaProgram *program,
                            unsigned pass_index, VkImageView source_view,
                            HleRaTarget *target, HleRaPassGpu *pass);

/* Record a supported layout transition in cb and update the tracked layout.
 * Call before rendering into a target or sampling it; never inside a render pass. */
bool hle_ra_target_transition(VkCommandBuffer cb, HleRaTarget *target,
                              VkImageLayout new_layout);

#endif
