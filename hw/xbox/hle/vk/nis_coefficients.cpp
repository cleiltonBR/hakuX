/*
 * hakuX HLE — NVIDIA Image Scaling coefficient tables.
 *
 * Keep the SDK arrays in this translation unit: NIS_Config.h intentionally
 * gives them internal linkage. The shader reads two RGBA texels per phase.
 */
#include "hw/xbox/hle/vk/nis_coefficients.h"
#include "third_party/NVIDIAImageScaling/NIS_Config.h"

#include <cstring>
#include <algorithm>
#include <cstdint>

static_assert(sizeof(NISConfig) == 256, "NISConfig uniform buffer ABI changed");

static_assert(sizeof(coef_scale) / sizeof(float) == HLE_NIS_COEFFICIENT_FLOATS);
static_assert(sizeof(coef_usm) / sizeof(float) == HLE_NIS_COEFFICIENT_FLOATS);

extern "C" void hle_nis_copy_coefficients(float *scaler_rgba, size_t scaler_count,
                                           float *usm_rgba, size_t usm_count)
{
    if (!scaler_rgba || !usm_rgba ||
        scaler_count < HLE_NIS_COEFFICIENT_FLOATS ||
        usm_count < HLE_NIS_COEFFICIENT_FLOATS) {
        return;
    }

    std::memcpy(scaler_rgba, coef_scale, sizeof(coef_scale));
    std::memcpy(usm_rgba, coef_usm, sizeof(coef_usm));
}

extern "C" int hle_nis_update_scaler_config(void *config, size_t config_size,
                                             float sharpness,
                                             uint32_t input_width, uint32_t input_height,
                                             uint32_t output_width, uint32_t output_height)
{
    if (!config || config_size < sizeof(NISConfig) ||
        !input_width || !input_height || !output_width || !output_height) {
        return 0;
    }

    NISConfig cfg{};
    const float safe_sharpness = std::clamp(sharpness, 0.0f, 1.0f);
    if (!NVScalerUpdateConfig(cfg, safe_sharpness,
                              0, 0, input_width, input_height,
                              input_width, input_height,
                              0, 0, output_width, output_height,
                              output_width, output_height,
                              NISHDRMode::None)) {
        return 0;
    }

    std::memcpy(config, &cfg, sizeof(cfg));
    return 1;
}

extern "C" void hle_nis_get_dispatch_size(uint32_t output_width, uint32_t output_height,
                                            uint32_t *groups_x, uint32_t *groups_y)
{
    if (groups_x) {
        *groups_x = output_width ? (output_width + 31u) / 32u : 0u;
    }
    if (groups_y) {
        *groups_y = output_height ? (output_height + 23u) / 24u : 0u;
    }
}
