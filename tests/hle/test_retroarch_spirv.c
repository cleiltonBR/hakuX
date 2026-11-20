#include "hw/xbox/hle/shader/retroarch_spirv.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>

static uint32_t *read_spirv(const char *path, size_t *word_count)
{
    FILE *file = fopen(path, "rb");
    assert(file);
    assert(fseek(file, 0, SEEK_END) == 0);
    long length = ftell(file);
    assert(length >= 20 && (length % 4) == 0);
    assert(fseek(file, 0, SEEK_SET) == 0);
    uint32_t *words = malloc((size_t)length);
    assert(words);
    assert(fread(words, 1, (size_t)length, file) == (size_t)length);
    assert(fclose(file) == 0);
    *word_count = (size_t)length / sizeof(*words);
    return words;
}

static bool has_user_location(const HleRaSpirvInterface *items,
                              uint32_t count, uint32_t location)
{
    for (uint32_t i = 0; i < count; ++i)
        if (items[i].location == location && items[i].built_in < 0)
            return true;
    return false;
}

int main(int argc, char **argv)
{
    assert(argc == 3);
    size_t vertex_words = 0, fragment_words = 0;
    uint32_t *vertex = read_spirv(argv[1], &vertex_words);
    uint32_t *fragment = read_spirv(argv[2], &fragment_words);
    HleRaSpirvInfo info;

    assert(hle_ra_spirv_inspect(vertex, vertex_words,
                                HLE_SHADER_VERTEX, &info));
    assert(info.stage == HLE_SHADER_VERTEX);
    assert(info.push_constant_count == 1);
    assert(info.push_constants[0].offset == 0);
    assert(info.push_constants[0].size == 16);
    assert(has_user_location(info.inputs, info.input_count, 0));
    assert(has_user_location(info.outputs, info.output_count, 0));
    assert(!hle_ra_spirv_inspect(vertex, vertex_words,
                                 HLE_SHADER_FRAGMENT, &info));

    assert(hle_ra_spirv_inspect(fragment, fragment_words,
                                HLE_SHADER_FRAGMENT, &info));
    assert(info.stage == HLE_SHADER_FRAGMENT);
    assert(info.descriptor_count == 1);
    assert(info.descriptors[0].set == 0);
    assert(info.descriptors[0].binding == 0);
    assert(info.push_constant_count == 1);
    assert(info.push_constants[0].offset == 0);
    assert(info.push_constants[0].size == 16);
    assert(has_user_location(info.inputs, info.input_count, 0));
    assert(has_user_location(info.outputs, info.output_count, 0));
    assert(!hle_ra_spirv_inspect(fragment, fragment_words,
                                 HLE_SHADER_VERTEX, &info));

    uint32_t invalid[5] = { 0, 0, 0, 0, 0 };
    assert(!hle_ra_spirv_inspect(invalid, 5, HLE_SHADER_VERTEX, &info));
    free(vertex);
    free(fragment);
    return 0;
}
