#include "hw/xbox/hle/shader/retroarch_vk.h"
#include <math.h>
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

void hle_ra_targets_destroy(HleRaTargets *targets)
{
    if (!targets) return;
    if (g_hvk.dev) {
        for (unsigned i = 0; i < HLE_RA_MAX_PASSES; ++i) {
            HleRaTarget *target = &targets->passes[i];
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
