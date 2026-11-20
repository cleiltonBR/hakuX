#ifndef HW_XBOX_HLE_SHADER_RETROARCH_RUNTIME_H
#define HW_XBOX_HLE_SHADER_RETROARCH_RUNTIME_H
#include "hw/xbox/hle/shader/retroarch_preset.h"
#include "hw/xbox/hle/shader/glsl_compile.h"
#define HLE_RA_SOURCE_MAX (256u * 1024u)
#define HLE_RA_TOTAL_SOURCE_MAX (2u * 1024u * 1024u)
#define HLE_RA_INCLUDE_MAX 64
typedef struct HleRaProgram {
    HleRaPreset preset;
    char *sources[HLE_RA_MAX_PASSES];
    size_t source_lengths[HLE_RA_MAX_PASSES];
    int source_root_fd;
} HleRaProgram;
typedef struct HleRaIncludeEntry { char *name; char *data; size_t length; } HleRaIncludeEntry;
typedef struct HleRaIncludeCache {
    int root_fd;
    size_t total_length;
    unsigned count;
    HleRaIncludeEntry entries[HLE_RA_INCLUDE_MAX];
} HleRaIncludeCache;
/* out must be zero-initialized or contain a previously loaded program. */
bool hle_ra_program_load(const char *preset_path, HleRaProgram *out,
                         char *error, size_t error_size);
void hle_ra_program_unload(HleRaProgram *program);
bool hle_ra_include_cache_init(HleRaIncludeCache *cache, const HleRaProgram *program);
const char *hle_ra_include_resolve(void *ctx, const char *name, size_t *length);
void hle_ra_include_cache_finalize(HleRaIncludeCache *cache);
/* Compiles one loaded GLSL pass to SPIR-V; caller frees the result with g_free. */
uint32_t *hle_ra_program_compile_pass(const HleRaProgram *program,
                                      unsigned pass_index,
                                      HleShaderStage stage,
                                      size_t *nwords);
#endif
