#include "hw/xbox/hle/shader/retroarch_spirv.h"
#include <spirv_reflect.h>
#include <string.h>

static bool reflect_descriptors(const SpvReflectShaderModule *module,
                                HleRaSpirvInfo *info)
{
    uint32_t count = 0;
    if (spvReflectEnumerateDescriptorBindings(module, &count, NULL) !=
            SPV_REFLECT_RESULT_SUCCESS ||
        count > HLE_RA_SPIRV_MAX_DESCRIPTORS)
        return false;

    SpvReflectDescriptorBinding *bindings[HLE_RA_SPIRV_MAX_DESCRIPTORS];
    if (count && spvReflectEnumerateDescriptorBindings(
                     module, &count, bindings) != SPV_REFLECT_RESULT_SUCCESS)
        return false;

    for (uint32_t i = 0; i < count; ++i) {
        const SpvReflectDescriptorBinding *binding = bindings[i];
        if (!binding) return false;
        for (uint32_t j = 0; j < i; ++j) {
            if (info->descriptors[j].set == binding->set &&
                info->descriptors[j].binding == binding->binding)
                return false;
        }
        info->descriptors[i] = (HleRaSpirvDescriptor) {
            .set = binding->set,
            .binding = binding->binding,
            .type = (uint32_t)binding->descriptor_type,
            .count = binding->count,
        };
    }
    info->descriptor_count = count;
    return true;
}

static bool reflect_push_constants(const SpvReflectShaderModule *module,
                                   HleRaSpirvInfo *info)
{
    uint32_t count = 0;
    if (spvReflectEnumeratePushConstantBlocks(module, &count, NULL) !=
            SPV_REFLECT_RESULT_SUCCESS ||
        count > HLE_RA_SPIRV_MAX_PUSH_CONSTANTS)
        return false;

    SpvReflectBlockVariable *blocks[HLE_RA_SPIRV_MAX_PUSH_CONSTANTS];
    if (count && spvReflectEnumeratePushConstantBlocks(
                     module, &count, blocks) != SPV_REFLECT_RESULT_SUCCESS)
        return false;

    for (uint32_t i = 0; i < count; ++i) {
        if (!blocks[i] || !blocks[i]->size ||
            blocks[i]->offset > UINT32_MAX - blocks[i]->size)
            return false;
        info->push_constants[i] = (HleRaSpirvPushConstant) {
            .offset = blocks[i]->offset,
            .size = blocks[i]->size,
        };
    }
    info->push_constant_count = count;
    return true;
}

static bool reflect_interfaces(const SpvReflectShaderModule *module,
                               bool inputs, HleRaSpirvInfo *info)
{
    uint32_t count = 0;
    SpvReflectResult result = inputs
        ? spvReflectEnumerateInputVariables(module, &count, NULL)
        : spvReflectEnumerateOutputVariables(module, &count, NULL);
    if (result != SPV_REFLECT_RESULT_SUCCESS ||
        count > HLE_RA_SPIRV_MAX_INTERFACES)
        return false;

    SpvReflectInterfaceVariable *variables[HLE_RA_SPIRV_MAX_INTERFACES];
    if (count) {
        result = inputs
            ? spvReflectEnumerateInputVariables(module, &count, variables)
            : spvReflectEnumerateOutputVariables(module, &count, variables);
        if (result != SPV_REFLECT_RESULT_SUCCESS) return false;
    }

    HleRaSpirvInterface *target = inputs ? info->inputs : info->outputs;
    for (uint32_t i = 0; i < count; ++i) {
        if (!variables[i]) return false;
        target[i] = (HleRaSpirvInterface) {
            .location = variables[i]->location,
            .format = (uint32_t)variables[i]->format,
            .built_in = variables[i]->built_in,
        };
    }
    if (inputs) info->input_count = count;
    else info->output_count = count;
    return true;
}

bool hle_ra_spirv_inspect(const uint32_t *words, size_t word_count,
                          HleShaderStage expected_stage, HleRaSpirvInfo *out)
{
    if (!words || word_count < 5 || !out ||
        word_count > SIZE_MAX / sizeof(*words))
        return false;

    const SpvReflectShaderStageFlagBits expected =
        expected_stage == HLE_SHADER_VERTEX ? SPV_REFLECT_SHADER_STAGE_VERTEX_BIT :
        expected_stage == HLE_SHADER_FRAGMENT ? SPV_REFLECT_SHADER_STAGE_FRAGMENT_BIT :
        expected_stage == HLE_SHADER_COMPUTE ? SPV_REFLECT_SHADER_STAGE_COMPUTE_BIT : 0;
    if (!expected) return false;

    SpvReflectShaderModule module;
    memset(&module, 0, sizeof(module));
    if (spvReflectCreateShaderModule(word_count * sizeof(*words), words, &module) !=
        SPV_REFLECT_RESULT_SUCCESS)
        return false;

    HleRaSpirvInfo info = { .stage = expected_stage };
    bool ok = module.entry_point_count == 1 && module.entry_points &&
              module.entry_points[0].shader_stage == expected &&
              reflect_descriptors(&module, &info) &&
              reflect_push_constants(&module, &info) &&
              reflect_interfaces(&module, true, &info) &&
              reflect_interfaces(&module, false, &info);
    spvReflectDestroyShaderModule(&module);
    if (ok) *out = info;
    return ok;
}
