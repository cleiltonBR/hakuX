#include "hw/xbox/hle/shader/retroarch_spirv.h"
#include <spirv_reflect.h>
#include <string.h>

static bool descriptor_count(const SpvReflectShaderModule *module, uint32_t *count)
{
    uint32_t n = 0;
    SpvReflectResult r = spvReflectEnumerateDescriptorBindings(module, &n, NULL);
    if (r != SPV_REFLECT_RESULT_SUCCESS) return false;
    *count = n;
    return true;
}

static bool push_constant_count(const SpvReflectShaderModule *module, uint32_t *count)
{
    uint32_t n = 0;
    SpvReflectResult r = spvReflectEnumeratePushConstantBlocks(module, &n, NULL);
    if (r != SPV_REFLECT_RESULT_SUCCESS) return false;
    *count = n;
    return true;
}

static bool input_count(const SpvReflectShaderModule *module, uint32_t *count)
{
    uint32_t n = 0;
    SpvReflectResult r = spvReflectEnumerateInputVariables(module, &n, NULL);
    if (r != SPV_REFLECT_RESULT_SUCCESS) return false;
    *count = n;
    return true;
}

static bool output_count(const SpvReflectShaderModule *module, uint32_t *count)
{
    uint32_t n = 0;
    SpvReflectResult r = spvReflectEnumerateOutputVariables(module, &n, NULL);
    if (r != SPV_REFLECT_RESULT_SUCCESS) return false;
    *count = n;
    return true;
}

bool hle_ra_spirv_inspect(const uint32_t *words, size_t word_count,
                          HleShaderStage expected_stage, HleRaSpirvInfo *out)
{
    if (!words || word_count < 5 || !out || word_count > SIZE_MAX / sizeof(*words))
        return false;

    SpvReflectShaderModule module;
    memset(&module, 0, sizeof(module));
    if (spvReflectCreateShaderModule(word_count * sizeof(*words), words, &module) !=
        SPV_REFLECT_RESULT_SUCCESS)
        return false;

    const SpvReflectShaderStageFlagBits expected =
        expected_stage == HLE_SHADER_VERTEX ? SPV_REFLECT_SHADER_STAGE_VERTEX_BIT :
        expected_stage == HLE_SHADER_FRAGMENT ? SPV_REFLECT_SHADER_STAGE_FRAGMENT_BIT :
        expected_stage == HLE_SHADER_COMPUTE ? SPV_REFLECT_SHADER_STAGE_COMPUTE_BIT : 0;
    bool ok = expected != 0 && module.entry_point_count == 1 &&
              module.entry_points != NULL &&
              module.entry_points[0].shader_stage == expected;

    HleRaSpirvInfo info = { .stage = expected_stage };
    if (ok) ok = descriptor_count(&module, &info.descriptor_count) &&
                 push_constant_count(&module, &info.push_constant_count) &&
                 input_count(&module, &info.input_count) &&
                 output_count(&module, &info.output_count);
    spvReflectDestroyShaderModule(&module);
    if (ok) *out = info;
    return ok;
}
