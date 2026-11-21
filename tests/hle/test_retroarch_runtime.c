#define _GNU_SOURCE
#include "hw/xbox/hle/shader/retroarch_runtime.h"
#include <assert.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
static void write_text(const char *path, const char *text)
{
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    assert(fd >= 0);
    size_t length = strlen(text), done = 0;
    while (done < length) {
        ssize_t n = write(fd, text + done, length - done);
        assert(n > 0);
        done += (size_t)n;
    }
    close(fd);
}
int main(void)
{
    char temp[] = "/tmp/hakux-ra-XXXXXX";
    char *dir = mkdtemp(temp);
    assert(dir);
    char preset[512], shader[512], nested_shader[512], include_shader[512];
    char nested[512], linkpath[512], outside[512];
    snprintf(preset, sizeof(preset), "%s/test.glslp", dir);
    snprintf(shader, sizeof(shader), "%s/pass0.glsl", dir);
    snprintf(nested, sizeof(nested), "%s/nested", dir);
    snprintf(nested_shader, sizeof(nested_shader), "%s/pass0.glsl", nested);
    snprintf(include_shader, sizeof(include_shader), "%s/common.glsl", nested);
    snprintf(linkpath, sizeof(linkpath), "%s/linked.glsl", dir);
    snprintf(outside, sizeof(outside), "%s-outside.glsl", dir);
    assert(mkdir(nested, 0700) == 0);
    write_text(shader, "#version 450\nvoid main(){}\n");
    write_text(nested_shader, "#version 450\nvoid main(){ /* nested */ }\n");
    write_text(include_shader, "const float RA_INCLUDE_TEST = 1.0;\n");
    write_text(outside, "outside");
    write_text(preset, "shaders=1\nshader0=pass0.glsl\n");
    HleRaProgram program = { 0 };
    char error[160];
    assert(hle_ra_program_load(preset, &program, error, sizeof(error)));
    assert(program.preset.pass_count == 1);
    assert(program.source_lengths[0] == strlen("#version 450\nvoid main(){}\n"));
    assert(strstr(program.sources[0], "void main") != NULL);
    /* Reload releases previous buffers and retains the directory for includes. */
    assert(hle_ra_program_load(preset, &program, error, sizeof(error)));
    assert(program.preset.pass_count == 1 && program.sources[0] != NULL);
    /* Relative pass paths may use safe subdirectories. */
    write_text(preset, "shaders=1\nshader0=nested/pass0.glsl\n");
    assert(hle_ra_program_load(preset, &program, error, sizeof(error)));
    assert(strstr(program.sources[0], "nested") != NULL);
    HleRaIncludeCache includes;
    assert(hle_ra_include_cache_init(&includes, &program));
    size_t include_length = 0;
    const char *include_data = hle_ra_include_resolve(&includes, "nested/common.glsl", &include_length);
    assert(include_data && include_length == strlen("const float RA_INCLUDE_TEST = 1.0;\n"));
    assert(strstr(include_data, "RA_INCLUDE_TEST") != NULL);
    assert(hle_ra_include_resolve(&includes, "../hakux-ra-outside.glsl", NULL) == NULL);
    hle_ra_include_cache_finalize(&includes);
    /* A failed replacement must leave the last valid program intact. */
    write_text(preset, "shaders=1\nshader0=../hakux-ra-outside.glsl\n");
    assert(!hle_ra_program_load(preset, &program, error, sizeof(error)));
    assert(program.preset.pass_count == 1);
    assert(strstr(program.sources[0], "nested") != NULL);
    hle_ra_program_unload(&program);
    /* A wrapper preset inherits passes from a referenced preset. */
    char presets[512], roots[512], shaders[512], wrapper[512], base[512], base_shader[512];
    snprintf(presets, sizeof(presets), "%s/presets", dir);
    snprintf(roots, sizeof(roots), "%s/Root_Presets", presets);
    snprintf(shaders, sizeof(shaders), "%s/shaders", roots);
    snprintf(wrapper, sizeof(wrapper), "%s/wrapper.slangp", presets);
    snprintf(base, sizeof(base), "%s/base.slangp", roots);
    snprintf(base_shader, sizeof(base_shader), "%s/pass.slang", shaders);
    assert(mkdir(presets, 0700) == 0);
    assert(mkdir(roots, 0700) == 0);
    assert(mkdir(shaders, 0700) == 0);
    write_text(wrapper, "#reference \"Root_Presets/base.slangp\"\n#reference \"../../resource/param_values/base/auto-settings.params\"\n");
    write_text(base, "shaders=1\nshader0=shaders/pass.slang\n");
    write_text(base_shader, "#version 450\nvoid main(){}\n");
    assert(hle_ra_program_load(wrapper, &program, error, sizeof(error)));
    assert(program.preset.pass_count == 1);
    assert(strstr(program.preset.passes[0].shader, "Root_Presets/shaders/pass.slang") != NULL);
    assert(strstr(program.sources[0], "void main") != NULL);
    hle_ra_program_unload(&program);
    /* Circular #reference chains are rejected instead of recursing forever. */
    write_text(base, "#reference \"wrapper.slangp\"\n");
    assert(!hle_ra_program_load(wrapper, &program, error, sizeof(error)));
    unlink(base_shader);
    unlink(base);
    unlink(wrapper);
    rmdir(shaders);
    rmdir(roots);
    rmdir(presets);
    assert(symlink(outside, linkpath) == 0);
    write_text(preset, "shaders=1\nshader0=linked.glsl\n");
    assert(!hle_ra_program_load(preset, &program, error, sizeof(error)));
    write_text(preset, "shaders=1\nshader0=../hakux-ra-outside.glsl\n");
    assert(!hle_ra_program_load(preset, &program, error, sizeof(error)));
    unlink(linkpath);
    unlink(shader);
    unlink(nested_shader);
    unlink(include_shader);
    unlink(outside);
    unlink(preset);
    rmdir(nested);
    rmdir(dir);
    return 0;
}
