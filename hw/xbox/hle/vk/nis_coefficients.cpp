/*
 * hakuX HLE — NVIDIA Image Scaling coefficient tables.
 *
 * Keep the SDK arrays in this translation unit: NIS_Config.h intentionally
 * gives them internal linkage. The shader reads two RGBA texels per phase.
 */
#include "hw/xbox/hle/vk/nis_coefficients.h"
#include "third_party/NVIDIAImageScaling/NIS_Config.h"

#include <cstring>

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
