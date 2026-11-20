#ifndef HW_XBOX_HLE_SHADER_RETROARCH_RUNTIME_H
#define HW_XBOX_HLE_SHADER_RETROARCH_RUNTIME_H

#include "hw/xbox/hle/shader/retroarch_preset.h"

#define HLE_RA_SOURCE_MAX (256u * 1024u)
#define HLE_RA_TOTAL_SOURCE_MAX (2u * 1024u * 1024u)

typedef struct HleRaProgram {
    HleRaPreset preset;
    char *sources[HLE_RA_MAX_PASSES];
    size_t source_lengths[HLE_RA_MAX_PASSES];
} HleRaProgram;

/* Loads a .glslp and every referenced pass source. Shader paths are resolved
 * relative to the preset directory without following symlinks or escaping it. */
bool hle_ra_program_load(const char *preset_path, HleRaProgram *out,
                         char *error, size_t error_size);
void hle_ra_program_unload(HleRaProgram *program);

#endif
