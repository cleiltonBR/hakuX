#include "hw/xbox/hle/shader/retroarch_runtime.h"
#include "hw/xbox/hle/shader/glsl_compile.h"
#include "hw/xbox/hle/shader/retroarch_spirv.h"

/* Compile one already-loaded preset pass. Include data is retained until
 * glslang has finished preprocessing, parsing and SPIR-V generation. */
uint32_t *hle_ra_program_compile_pass(const HleRaProgram *program,
                                      unsigned pass_index,
                                      HleShaderStage stage,
                                      size_t *nwords)
{
    if (nwords) *nwords = 0;
    if (!program || !nwords || pass_index >= program->preset.pass_count ||
        !program->sources[pass_index] || program->source_root_fd < 0)
        return NULL;

    HleRaIncludeCache includes;
    if (!hle_ra_include_cache_init(&includes, program))
        return NULL;
    uint32_t *spirv = hle_glsl_compile_with_includes(
        stage, program->sources[pass_index], hle_ra_include_resolve,
        &includes, nwords);
    hle_ra_include_cache_finalize(&includes);
    HleRaSpirvInfo reflection;
    if (spirv && !hle_ra_spirv_inspect(spirv, *nwords, stage, &reflection)) {
        g_free(spirv);
        *nwords = 0;
        return NULL;
    }
    return spirv;
}

