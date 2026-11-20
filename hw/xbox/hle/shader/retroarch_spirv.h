#ifndef HW_XBOX_HLE_SHADER_RETROARCH_SPIRV_H
#define HW_XBOX_HLE_SHADER_RETROARCH_SPIRV_H

#include "hw/xbox/hle/shader/glsl_compile.h"

/* Bounded reflection data used to build Vulkan layouts without trusting
 * arbitrary shader-controlled allocation sizes. */
#define HLE_RA_SPIRV_MAX_DESCRIPTORS 64
#define HLE_RA_SPIRV_MAX_PUSH_CONSTANTS 8
#define HLE_RA_SPIRV_MAX_INTERFACES 64

typedef struct HleRaSpirvDescriptor {
    uint32_t set;
    uint32_t binding;
    uint32_t type;
    uint32_t count;
} HleRaSpirvDescriptor;

typedef struct HleRaSpirvPushConstant {
    uint32_t offset;
    uint32_t size;
} HleRaSpirvPushConstant;

typedef struct HleRaSpirvInterface {
    uint32_t location;
    uint32_t format;
    int32_t built_in;
} HleRaSpirvInterface;

typedef struct HleRaSpirvInfo {
    HleShaderStage stage;
    uint32_t descriptor_count;
    uint32_t push_constant_count;
    uint32_t input_count;
    uint32_t output_count;
    HleRaSpirvDescriptor descriptors[HLE_RA_SPIRV_MAX_DESCRIPTORS];
    HleRaSpirvPushConstant push_constants[HLE_RA_SPIRV_MAX_PUSH_CONSTANTS];
    HleRaSpirvInterface inputs[HLE_RA_SPIRV_MAX_INTERFACES];
    HleRaSpirvInterface outputs[HLE_RA_SPIRV_MAX_INTERFACES];
} HleRaSpirvInfo;

/* Validates the module's entry point/stage and extracts bounded Vulkan
 * descriptor, push-constant and stage-interface requirements. */
bool hle_ra_spirv_inspect(const uint32_t *words, size_t word_count,
                          HleShaderStage expected_stage,
                          HleRaSpirvInfo *out);

#endif
