/* C interface for the native LSFG shader-resource cache. */
#ifndef HW_XBOX_HLE_VK_LSFG_NATIVE_H
#define HW_XBOX_HLE_VK_LSFG_NATIVE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Parse the user's PE as data and retain its validated native SPIR-V modules.
 * The Windows image is never loaded or executed. */
int hle_lsfg_load_shaders(const char *path, int allow_fp16, int prefer_fp16,
                          char *error, size_t error_capacity);
void hle_lsfg_clear_shaders(void);
int hle_lsfg_shader_count(void);
/* Returned words remain valid until hle_lsfg_clear_shaders() or another load. */
int hle_lsfg_get_shader(uint32_t id, const uint32_t **words, size_t *word_count);

#ifdef __cplusplus
}
#endif
#endif
