#include "hw/xbox/hle/base.h"
#include "hw/xbox/hle/shader/retroarch_vk.h"
#include "hw/xbox/hle/shader/retroarch_spirv.h"
#include <spirv_reflect.h>
#include <string.h>

static bool scaled_dimension(uint32_t base, float scale, uint32_t *out)
{
    if (!base || !(scale > 0.0f) || !out) return false;
    double value = (double)base * (double)scale;
    if (!(value >= 1.0) || value > 16384.0) return false;
    *out = (uint32_t)(value + 0.5);
    return *out != 0;
}

static bool target_dimensions(const HleRaPass *pass,
                              uint32_t input_width, uint32_t input_height,
                              uint32_t viewport_width, uint32_t viewport_height,
                              uint32_t *width, uint32_t *height)
{
    if (pass->frame_count != 1) return false;
    switch (pass->scale_type) {
    case HLE_RA_SCALE_SOURCE:
        return scaled_dimension(input_width, pass->scale_x, width) &&
               scaled_dimension(input_height, pass->scale_y, height);
    case HLE_RA_SCALE_ABSOLUTE:
        return scaled_dimension(1, pass->scale_x, width) &&
               scaled_dimension(1, pass->scale_y, height);
    case HLE_RA_SCALE_VIEWPORT:
        return scaled_dimension(viewport_width, pass->scale_x, width) &&
               scaled_dimension(viewport_height, pass->scale_y, height);
    default:
        return false;
    }
}

bool hle_ra_target_transition(VkCommandBuffer cb, HleRaTarget *target,
                              VkImageLayout new_layout)
{
    if (!cb || !target || !target->image) return false;
    const VkImageLayout old_layout = target->layout;
    if (old_layout == new_layout) return true;

    VkPipelineStageFlags src_stage;
    VkAccessFlags src_access;
    switch (old_layout) {
    case VK_IMAGE_LAYOUT_UNDEFINED:
        src_stage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
        src_access = 0;
        break;
    case VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL:
        src_stage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        src_access = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        break;
    case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL:
        src_stage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
        src_access = VK_ACCESS_SHADER_READ_BIT;
        break;
    default:
        return false;
    }

    VkPipelineStageFlags dst_stage;
    VkAccessFlags dst_access;
    switch (new_layout) {
    case VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL:
        dst_stage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dst_access = VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        break;
    case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL:
        dst_stage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
        dst_access = VK_ACCESS_SHADER_READ_BIT;
        break;
    default:
        return false;
    }

    VkImageMemoryBarrier barrier = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .srcAccessMask = src_access,
        .dstAccessMask = dst_access,
        .oldLayout = old_layout,
        .newLayout = new_layout,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .image = target->image,
        .subresourceRange = {
            .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
            .baseMipLevel = 0, .levelCount = 1,
            .baseArrayLayer = 0, .layerCount = 1,
        },
    };
    g_hvk.t.vkCmdPipelineBarrier(cb, src_stage, dst_stage, 0,
                                  0, NULL, 0, NULL, 1, &barrier);
    target->layout = new_layout;
    return true;
}

void hle_ra_targets_destroy(HleRaTargets *targets)
{
    if (!targets) return;
    if (g_hvk.dev) {
        for (unsigned i = 0; i < HLE_RA_MAX_PASSES; ++i) {
            HleRaTarget *target = &targets->passes[i];
            if (target->framebuffer)
                g_hvk.t.vkDestroyFramebuffer(g_hvk.dev, target->framebuffer, NULL);
            if (target->render_pass)
                g_hvk.t.vkDestroyRenderPass(g_hvk.dev, target->render_pass, NULL);
            if (target->view) g_hvk.t.vkDestroyImageView(g_hvk.dev, target->view, NULL);
            if (target->image) g_hvk.t.vkDestroyImage(g_hvk.dev, target->image, NULL);
            if (target->memory) g_hvk.t.vkFreeMemory(g_hvk.dev, target->memory, NULL);
        }
    }
    memset(targets, 0, sizeof(*targets));
}

bool hle_ra_targets_create(const HleRaProgram *program,
                           uint32_t source_width, uint32_t source_height,
                           uint32_t viewport_width, uint32_t viewport_height,
                           HleRaTargets *out)
{
    if (!program || !out || !g_hvk.dev || !program->preset.pass_count ||
        program->preset.pass_count > HLE_RA_MAX_PASSES ||
        !source_width || !source_height || !viewport_width || !viewport_height)
        return false;

    HleRaTargets created = {0};
    uint32_t input_width = source_width, input_height = source_height;
    for (unsigned i = 0; i < program->preset.pass_count; ++i) {
        HleRaTarget *target = &created.passes[i];
        const HleRaPass *pass = &program->preset.passes[i];
        if (!target_dimensions(pass, input_width, input_height,
                               viewport_width, viewport_height,
                               &target->width, &target->height) ||
            (uint64_t)target->width * target->height > HLE_RA_TARGET_MAX_PIXELS)
            goto fail;

        VkImageCreateInfo image_info = {
            .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
            .imageType = VK_IMAGE_TYPE_2D,
            .format = VK_FORMAT_R8G8B8A8_UNORM,
            .extent = { target->width, target->height, 1 },
            .mipLevels = 1,
            .arrayLayers = 1,
            .samples = VK_SAMPLE_COUNT_1_BIT,
            .tiling = VK_IMAGE_TILING_OPTIMAL,
            .usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT |
                     VK_IMAGE_USAGE_SAMPLED_BIT |
                     VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
            .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
            .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
        };
        if (g_hvk.t.vkCreateImage(g_hvk.dev, &image_info, NULL,
                                  &target->image) != VK_SUCCESS)
            goto fail;

        VkMemoryRequirements requirements;
        g_hvk.t.vkGetImageMemoryRequirements(g_hvk.dev, target->image,
                                              &requirements);
        int memory_type = hle_vk_memtype(requirements.memoryTypeBits,
                                         VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0);
        if (memory_type < 0) goto fail;
        VkMemoryAllocateInfo allocation = {
            .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
            .allocationSize = requirements.size,
            .memoryTypeIndex = (uint32_t)memory_type,
        };
        if (g_hvk.t.vkAllocateMemory(g_hvk.dev, &allocation, NULL,
                                     &target->memory) != VK_SUCCESS)
            goto fail;
        if (g_hvk.t.vkBindImageMemory(g_hvk.dev, target->image,
                                      target->memory, 0) != VK_SUCCESS)
            goto fail;

        VkImageViewCreateInfo view_info = {
            .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
            .image = target->image,
            .viewType = VK_IMAGE_VIEW_TYPE_2D,
            .format = VK_FORMAT_R8G8B8A8_UNORM,
            .components = {
                VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY,
                VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY
            },
            .subresourceRange = {
                .aspectMask = VK_IMAGE_ASPECT_COLOR_BIT,
                .baseMipLevel = 0, .levelCount = 1,
                .baseArrayLayer = 0, .layerCount = 1
            },
        };
        if (g_hvk.t.vkCreateImageView(g_hvk.dev, &view_info, NULL,
                                      &target->view) != VK_SUCCESS)
            goto fail;
        VkAttachmentDescription attachment = {
            .format = VK_FORMAT_R8G8B8A8_UNORM,
            .samples = VK_SAMPLE_COUNT_1_BIT,
            .loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE,
            .storeOp = VK_ATTACHMENT_STORE_OP_STORE,
            .stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE,
            .stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE,
            .initialLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
            .finalLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
        };
        VkAttachmentReference color = {
            .attachment = 0,
            .layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
        };
        VkSubpassDescription subpass = {
            .pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS,
            .colorAttachmentCount = 1,
            .pColorAttachments = &color,
        };
        VkRenderPassCreateInfo render_pass_info = {
            .sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO,
            .attachmentCount = 1,
            .pAttachments = &attachment,
            .subpassCount = 1,
            .pSubpasses = &subpass,
        };
        if (g_hvk.t.vkCreateRenderPass(g_hvk.dev, &render_pass_info, NULL,
                                       &target->render_pass) != VK_SUCCESS)
            goto fail;

        VkFramebufferCreateInfo framebuffer_info = {
            .sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO,
            .renderPass = target->render_pass,
            .attachmentCount = 1,
            .pAttachments = &target->view,
            .width = target->width,
            .height = target->height,
            .layers = 1,
        };
        if (g_hvk.t.vkCreateFramebuffer(g_hvk.dev, &framebuffer_info, NULL,
                                        &target->framebuffer) != VK_SUCCESS)
            goto fail;

        target->layout = VK_IMAGE_LAYOUT_UNDEFINED;
        input_width = target->width;
        input_height = target->height;
        created.count = i + 1;
    }

    hle_ra_targets_destroy(out);
    *out = created;
    return true;

fail:
    hle_ra_targets_destroy(&created);
    return false;
}


static bool ra_vk_descriptor_type(uint32_t reflected, VkDescriptorType *out)
{
    if (!out) return false;
    switch ((SpvReflectDescriptorType)reflected) {
    case SPV_REFLECT_DESCRIPTOR_TYPE_SAMPLER:
        *out = VK_DESCRIPTOR_TYPE_SAMPLER; return true;
    case SPV_REFLECT_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER:
        *out = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; return true;
    case SPV_REFLECT_DESCRIPTOR_TYPE_SAMPLED_IMAGE:
        *out = VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE; return true;
    case SPV_REFLECT_DESCRIPTOR_TYPE_STORAGE_IMAGE:
        *out = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE; return true;
    case SPV_REFLECT_DESCRIPTOR_TYPE_UNIFORM_BUFFER:
        *out = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER; return true;
    case SPV_REFLECT_DESCRIPTOR_TYPE_STORAGE_BUFFER:
        *out = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER; return true;
    default:
        return false;
    }
}

void hle_ra_pass_gpu_destroy(HleRaPassGpu *pass)
{
    if (!pass) return;
    if (g_hvk.dev) {
        if (pass->pipeline)
            g_hvk.t.vkDestroyPipeline(g_hvk.dev, pass->pipeline, NULL);
        if (pass->sampler)
            g_hvk.t.vkDestroySampler(g_hvk.dev, pass->sampler, NULL);
        if (pass->descriptor_pool)
            g_hvk.t.vkDestroyDescriptorPool(g_hvk.dev, pass->descriptor_pool, NULL);
        if (pass->pipeline_layout)
            g_hvk.t.vkDestroyPipelineLayout(g_hvk.dev, pass->pipeline_layout, NULL);
        for (unsigned i = 0; i < ARRAY_SIZE(pass->set_layouts); ++i) {
            if (pass->set_layouts[i])
                g_hvk.t.vkDestroyDescriptorSetLayout(g_hvk.dev, pass->set_layouts[i], NULL);
        }
        if (pass->fragment)
            g_hvk.t.vkDestroyShaderModule(g_hvk.dev, pass->fragment, NULL);
    }
    memset(pass, 0, sizeof(*pass));
}

bool hle_ra_pass_gpu_create(const HleRaProgram *program, unsigned pass_index,
                            const HleRaTarget *target, HleRaPassGpu *out)
{
    if (!program || !target || !target->render_pass || !out || !g_hvk.dev ||
        pass_index >= program->preset.pass_count) return false;

    size_t word_count = 0;
    uint32_t *words = hle_ra_program_compile_pass(program, pass_index,
                                                   HLE_SHADER_FRAGMENT,
                                                   &word_count);
    if (!words) return false;

    HleRaSpirvInfo info;
    bool ok = hle_ra_spirv_inspect(words, word_count, HLE_SHADER_FRAGMENT, &info);
    HleRaPassGpu created = {0};
    if (!ok) goto done;

    VkShaderModuleCreateInfo module_info = {
        .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
        .codeSize = word_count * sizeof(*words),
        .pCode = words,
    };
    if (g_hvk.t.vkCreateShaderModule(g_hvk.dev, &module_info, NULL,
                                    &created.fragment) != VK_SUCCESS)
        goto done;

    uint32_t max_set = 0;
    for (uint32_t i = 0; i < info.descriptor_count; ++i) {
        if (info.descriptors[i].set >= 8u ||
            !info.descriptors[i].count) goto done;
        if (info.descriptors[i].set > max_set) max_set = info.descriptors[i].set;
    }
    created.set_count = info.descriptor_count ? max_set + 1 : 0;

    for (uint32_t set = 0; set < created.set_count; ++set) {
        VkDescriptorSetLayoutBinding bindings[HLE_RA_SPIRV_MAX_DESCRIPTORS];
        uint32_t count = 0;
        for (uint32_t i = 0; i < info.descriptor_count; ++i) {
            const HleRaSpirvDescriptor *d = &info.descriptors[i];
            if (d->set != set) continue;
            VkDescriptorType type;
            if (!ra_vk_descriptor_type(d->type, &type)) goto done;
            bindings[count++] = (VkDescriptorSetLayoutBinding) {
                .binding = d->binding,
                .descriptorType = type,
                .descriptorCount = d->count,
                .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT,
            };
        }
        VkDescriptorSetLayoutCreateInfo layout_info = {
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
            .bindingCount = count,
            .pBindings = count ? bindings : NULL,
        };
        if (g_hvk.t.vkCreateDescriptorSetLayout(g_hvk.dev, &layout_info, NULL,
                                                 &created.set_layouts[set]) != VK_SUCCESS)
            goto done;
    }

    if (info.descriptor_count) {
        VkDescriptorPoolSize pool_sizes[6] = {0};
        uint32_t pool_size_count = 0;
        for (uint32_t i = 0; i < info.descriptor_count; ++i) {
            VkDescriptorType type;
            if (!ra_vk_descriptor_type(info.descriptors[i].type, &type)) goto done;
            uint32_t slot = 0;
            while (slot < pool_size_count && pool_sizes[slot].type != type) ++slot;
            if (slot == pool_size_count) {
                if (pool_size_count >= ARRAY_SIZE(pool_sizes)) goto done;
                pool_sizes[slot].type = type;
                ++pool_size_count;
            }
            if (UINT32_MAX - pool_sizes[slot].descriptorCount <
                info.descriptors[i].count) goto done;
            pool_sizes[slot].descriptorCount += info.descriptors[i].count;
        }
        VkDescriptorPoolCreateInfo pool_info = {
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
            .maxSets = created.set_count,
            .poolSizeCount = pool_size_count,
            .pPoolSizes = pool_sizes,
        };
        if (g_hvk.t.vkCreateDescriptorPool(g_hvk.dev, &pool_info, NULL,
                                           &created.descriptor_pool) != VK_SUCCESS)
            goto done;
        VkDescriptorSetAllocateInfo set_info = {
            .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
            .descriptorPool = created.descriptor_pool,
            .descriptorSetCount = created.set_count,
            .pSetLayouts = created.set_layouts,
        };
        if (g_hvk.t.vkAllocateDescriptorSets(g_hvk.dev, &set_info,
                                              created.descriptor_sets) != VK_SUCCESS)
            goto done;
    }

    VkSamplerCreateInfo sampler_info = {
        .sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO,
        .magFilter = program->preset.passes[pass_index].filter_linear
                         ? VK_FILTER_LINEAR : VK_FILTER_NEAREST,
        .minFilter = program->preset.passes[pass_index].filter_linear
                         ? VK_FILTER_LINEAR : VK_FILTER_NEAREST,
        .mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST,
        .addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        .addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        .addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        .minLod = 0.0f, .maxLod = 0.0f,
        .maxAnisotropy = 1.0f,
    };
    if (g_hvk.t.vkCreateSampler(g_hvk.dev, &sampler_info, NULL,
                                &created.sampler) != VK_SUCCESS)
        goto done;

    VkPushConstantRange ranges[HLE_RA_SPIRV_MAX_PUSH_CONSTANTS];
    for (uint32_t i = 0; i < info.push_constant_count; ++i) {
        const HleRaSpirvPushConstant *p = &info.push_constants[i];
        if ((p->offset & 3u) || (p->size & 3u) ||
            p->offset > g_hvk.props.limits.maxPushConstantsSize ||
            p->size > g_hvk.props.limits.maxPushConstantsSize - p->offset)
            goto done;
        ranges[i] = (VkPushConstantRange) {
            .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT,
            .offset = p->offset,
            .size = p->size,
        };
    }
    VkPipelineLayoutCreateInfo pipeline_info = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
        .setLayoutCount = created.set_count,
        .pSetLayouts = created.set_count ? created.set_layouts : NULL,
        .pushConstantRangeCount = info.push_constant_count,
        .pPushConstantRanges = info.push_constant_count ? ranges : NULL,
    };
    if (g_hvk.t.vkCreatePipelineLayout(g_hvk.dev, &pipeline_info, NULL,
                                       &created.pipeline_layout) != VK_SUCCESS)
        goto done;

    /* Full-screen triangle: no vertex buffers or guest vertex state. */
    static const char vertex_source[] =
        "#version 450\n"
        "#extension GL_ARB_separate_shader_objects : enable\n"
        "layout(location=0) out vec2 v_uv;\n"
        "void main(){\n"
        " vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);\n"
        " v_uv=p*0.5; gl_Position=vec4(p*2.0-1.0,0.0,1.0);\n"
        "}\n";
    size_t vertex_word_count = 0;
    uint32_t *vertex_words = hle_glsl_compile(HLE_SHADER_VERTEX, vertex_source,
                                               &vertex_word_count);
    if (!vertex_words) goto done;
    VkShaderModule vertex_module = VK_NULL_HANDLE;
    VkShaderModuleCreateInfo vertex_module_info = {
        .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
        .codeSize = vertex_word_count * sizeof(*vertex_words),
        .pCode = vertex_words,
    };
    VkResult vertex_result = g_hvk.t.vkCreateShaderModule(
        g_hvk.dev, &vertex_module_info, NULL, &vertex_module);
    g_free(vertex_words);
    if (vertex_result != VK_SUCCESS) goto done;

    VkPipelineShaderStageCreateInfo stages[2] = {
        { .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
          .stage = VK_SHADER_STAGE_VERTEX_BIT, .module = vertex_module,
          .pName = "main" },
        { .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
          .stage = VK_SHADER_STAGE_FRAGMENT_BIT, .module = created.fragment,
          .pName = "main" },
    };
    VkPipelineVertexInputStateCreateInfo vertex_input = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO,
    };
    VkPipelineInputAssemblyStateCreateInfo assembly = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO,
        .topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,
    };
    VkPipelineViewportStateCreateInfo viewport = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO,
        .viewportCount = 1, .scissorCount = 1,
    };
    VkPipelineRasterizationStateCreateInfo raster = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO,
        .polygonMode = VK_POLYGON_MODE_FILL,
        .cullMode = VK_CULL_MODE_NONE,
        .frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE,
        .lineWidth = 1.0f,
    };
    VkPipelineMultisampleStateCreateInfo multisample = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO,
        .rasterizationSamples = VK_SAMPLE_COUNT_1_BIT,
    };
    VkPipelineColorBlendAttachmentState blend_attachment = {
        .colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                          VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT,
    };
    VkPipelineColorBlendStateCreateInfo blend = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO,
        .attachmentCount = 1, .pAttachments = &blend_attachment,
    };
    VkDynamicState dynamic_states[] = {
        VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR,
    };
    VkPipelineDynamicStateCreateInfo dynamic = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO,
        .dynamicStateCount = ARRAY_SIZE(dynamic_states),
        .pDynamicStates = dynamic_states,
    };
    VkGraphicsPipelineCreateInfo graphics = {
        .sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO,
        .stageCount = ARRAY_SIZE(stages), .pStages = stages,
        .pVertexInputState = &vertex_input,
        .pInputAssemblyState = &assembly,
        .pViewportState = &viewport,
        .pRasterizationState = &raster,
        .pMultisampleState = &multisample,
        .pColorBlendState = &blend,
        .pDynamicState = &dynamic,
        .layout = created.pipeline_layout,
        .renderPass = target->render_pass,
        .subpass = 0,
    };
    VkResult pipeline_result = g_hvk.t.vkCreateGraphicsPipelines(
        g_hvk.dev, VK_NULL_HANDLE, 1, &graphics, NULL, &created.pipeline);
    g_hvk.t.vkDestroyShaderModule(g_hvk.dev, vertex_module, NULL);
    if (pipeline_result != VK_SUCCESS) goto done;

    hle_ra_pass_gpu_destroy(out);
    *out = created;
    memset(&created, 0, sizeof(created));
    ok = true;

done:
    hle_ra_pass_gpu_destroy(&created);
    g_free(words);
    return ok;
}

bool hle_ra_pass_gpu_update_input(const HleRaProgram *program, unsigned pass_index,
                                  const HleRaTarget *input, HleRaPassGpu *pass)
{
    if (!program || !input || !input->view || !pass || !pass->sampler ||
        pass_index >= program->preset.pass_count ||
        input->layout != VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
        return false;

    size_t word_count = 0;
    uint32_t *words = hle_ra_program_compile_pass(program, pass_index,
                                                   HLE_SHADER_FRAGMENT,
                                                   &word_count);
    if (!words) return false;
    HleRaSpirvInfo info;
    bool ok = hle_ra_spirv_inspect(words, word_count, HLE_SHADER_FRAGMENT, &info);
    g_free(words);
    if (!ok) return false;

    VkWriteDescriptorSet writes[HLE_RA_SPIRV_MAX_DESCRIPTORS];
    VkDescriptorImageInfo images[HLE_RA_SPIRV_MAX_DESCRIPTORS];
    uint32_t write_count = 0;
    for (uint32_t i = 0; i < info.descriptor_count; ++i) {
        const HleRaSpirvDescriptor *d = &info.descriptors[i];
        VkDescriptorType type;
        if (!ra_vk_descriptor_type(d->type, &type) || d->count != 1 ||
            d->set >= pass->set_count ||
            !pass->descriptor_sets[d->set])
            return false;
        if (type != VK_DESCRIPTOR_TYPE_SAMPLER &&
            type != VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE &&
            type != VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
            return false;

        VkDescriptorImageInfo image = {
            .sampler = type == VK_DESCRIPTOR_TYPE_SAMPLER ||
                       type == VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                           ? pass->sampler : VK_NULL_HANDLE,
            .imageView = type == VK_DESCRIPTOR_TYPE_SAMPLER
                             ? VK_NULL_HANDLE : input->view,
            .imageLayout = type == VK_DESCRIPTOR_TYPE_SAMPLER
                               ? VK_IMAGE_LAYOUT_UNDEFINED
                               : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
        };
        images[write_count] = image;
        writes[write_count] = (VkWriteDescriptorSet) {
            .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
            .dstSet = pass->descriptor_sets[d->set],
            .dstBinding = d->binding,
            .descriptorCount = 1,
            .descriptorType = type,
            .pImageInfo = &images[write_count],
        };
        ++write_count;
    }
    if (write_count)
        g_hvk.t.vkUpdateDescriptorSets(g_hvk.dev, write_count, writes, 0, NULL);
    return true;
}

