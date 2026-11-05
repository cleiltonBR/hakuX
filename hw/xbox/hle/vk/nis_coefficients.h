/*
 * hakuX HLE — NVIDIA Image Scaling coefficient tables.
 *
 * The NIS SDK keeps its canonical 64-phase filter banks in NIS_Config.h.
 * The Vulkan presenter uploads these as two 2x64 RGBA32F sampled images.
 */
#ifndef HW_XBOX_HLE_VK_NIS_COEFFICIENTS_H
#define HW_XBOX_HLE_VK_NIS_COEFFICIENTS_H

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

#define HLE_NIS_COEFFICIENT_PHASES 64u
#define HLE_NIS_COEFFICIENT_TEXELS_PER_PHASE 2u
#define HLE_NIS_COEFFICIENT_COMPONENTS 4u
#define HLE_NIS_COEFFICIENT_FLOATS \
    (HLE_NIS_COEFFICIENT_PHASES * HLE_NIS_COEFFICIENT_TEXELS_PER_PHASE * HLE_NIS_COEFFICIENT_COMPONENTS)

/*
 * Copy the SDK's scaler and USM filter banks into tightly packed
 * [phase][texel-x][rgba] arrays. Each output therefore contains 64*2 RGBA
 * texels, ready for VK_FORMAT_R32G32B32A32_SFLOAT upload.
 */
void hle_nis_copy_coefficients(float *scaler_rgba, size_t scaler_count,
                               float *usm_rgba, size_t usm_count);

#ifdef __cplusplus
}
#endif

#endif
