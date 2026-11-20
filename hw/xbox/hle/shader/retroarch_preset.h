#ifndef HW_XBOX_HLE_SHADER_RETROARCH_PRESET_H
#define HW_XBOX_HLE_SHADER_RETROARCH_PRESET_H
#include <stdbool.h>
#include <stddef.h>
#define HLE_RA_MAX_PASSES 32
#define HLE_RA_PATH_MAX 1024
typedef struct HleRaPass {
    char shader[HLE_RA_PATH_MAX];
    bool filter_linear;
    unsigned frame_count;
    enum { HLE_RA_SCALE_SOURCE, HLE_RA_SCALE_ABSOLUTE, HLE_RA_SCALE_VIEWPORT } scale_type;
    float scale_x, scale_y;
} HleRaPass;
typedef struct HleRaPreset { unsigned pass_count; HleRaPass passes[HLE_RA_MAX_PASSES]; } HleRaPreset;
bool hle_ra_preset_parse(const char *text, size_t length, HleRaPreset *out,
                         char *error, size_t error_size);
#endif
