#ifndef HW_XBOX_HLE_SHADER_RETROARCH_SPIRV_H
#define HW_XBOX_HLE_SHADER_RETROARCH_SPIRV_H

#include "hw/xbox/hle/shader/glsl_compile.h"

typedef struct HleRaSpirvInfo {
    HleShaderStage stage;
    uint32_t descriptor_count;
    uint32_t push_constant_count;
    uint32_t input_count;
    uint32_t output_count;
} HleRaSpirvInfo;

/* Validates a compiled module and reports the resources needed to build its
 * Vulkan descriptor and pipeline layouts. */
bool hle_ra_spirv_inspect(const uint32_t *words, size_t word_count,
                          HleShaderStage expected_stage,
                          HleRaSpirvInfo *out);

#endif
