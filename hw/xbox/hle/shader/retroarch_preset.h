#ifndef HW_XBOX_HLE_SHADER_RETROARCH_PRESET_H
#define HW_XBOX_HLE_SHADER_RETROARCH_PRESET_H
#include <stdbool.h>
#include <stddef.h>
#define HLE_RA_MAX_PASSES 64
#define HLE_RA_PATH_MAX 1024
#define HLE_RA_MAX_PARAMETERS 256
#define HLE_RA_PARAMETER_NAME_MAX 128
#define HLE_RA_PARAMETER_VALUE_MAX 128
typedef struct HleRaPass {
    char shader[HLE_RA_PATH_MAX];
    bool filter_linear;
    unsigned frame_count;
    enum { HLE_RA_SCALE_SOURCE, HLE_RA_SCALE_ABSOLUTE, HLE_RA_SCALE_VIEWPORT } scale_type;
    float scale_x, scale_y;
} HleRaPass;
typedef struct HleRaParameter { char name[HLE_RA_PARAMETER_NAME_MAX]; char value[HLE_RA_PARAMETER_VALUE_MAX]; } HleRaParameter;
typedef struct HleRaPreset { unsigned pass_count; HleRaPass passes[HLE_RA_MAX_PASSES]; unsigned parameter_count; HleRaParameter parameters[HLE_RA_MAX_PARAMETERS]; } HleRaPreset;
bool hle_ra_preset_parse(const char *text, size_t length, HleRaPreset *out,
                         char *error, size_t error_size);
#endif
