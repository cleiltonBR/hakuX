#define _GNU_SOURCE
#include "hw/xbox/hle/shader/retroarch_runtime.h"

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define RA_PRESET_MAX (256u * 1024u)
#define RA_PATH_MAX 4096u

static void set_error(char *dst, size_t size, const char *message)
{
    if (dst && size) snprintf(dst, size, "%s", message);
}

static bool read_fd(int fd, size_t limit, char **data, size_t *length)
{
    struct stat st;
    if (fstat(fd, &st) || !S_ISREG(st.st_mode) || st.st_size <= 0 ||
        (uint64_t)st.st_size > limit) return false;
    size_t size = (size_t)st.st_size;
    char *buffer = malloc(size + 1);
    if (!buffer) return false;
    size_t done = 0;
    while (done < size) {
        ssize_t n = read(fd, buffer + done, size - done);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) { free(buffer); return false; }
        done += (size_t)n;
    }
    buffer[size] = 0;
    *data = buffer;
    *length = size;
    return true;
}

static int open_relative_file(int root, const char *path)
{
    if (!path || !*path || path[0] == '/' || path[0] == '\\') return -1;
    char *copy = malloc(strlen(path) + 1);
    if (!copy) return -1;
    strcpy(copy, path);
    int dir = dup(root);
    if (dir < 0) { free(copy); return -1; }
    char *part = copy;
    int result = -1;
    for (;;) {
        char *slash = strchr(part, '/');
        if (slash) *slash = 0;
        if (!*part || !strcmp(part, ".") || !strcmp(part, "..") ||
            strchr(part, '\\')) break;
        if (slash) {
            int next = openat(dir, part, O_RDONLY | O_CLOEXEC | O_DIRECTORY | O_NOFOLLOW);
            if (next < 0) break;
            close(dir);
            dir = next;
            part = slash + 1;
            if (!*part) break;
        } else {
            result = openat(dir, part, O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
            break;
        }
    }
    close(dir);
    free(copy);
    return result;
}

void hle_ra_program_unload(HleRaProgram *program)
{
    if (!program) return;
    for (unsigned i = 0; i < HLE_RA_MAX_PASSES; ++i) {
        free(program->sources[i]);
        program->sources[i] = NULL;
        program->source_lengths[i] = 0;
    }
    memset(&program->preset, 0, sizeof(program->preset));
}

bool hle_ra_program_load(const char *preset_path, HleRaProgram *out,
                         char *error, size_t error_size)
{
    if (!preset_path || !*preset_path || !out || strlen(preset_path) >= RA_PATH_MAX) {
        set_error(error, error_size, "invalid preset path");
        return false;
    }
    HleRaProgram loaded = { 0 };
    char path[RA_PATH_MAX];
    strcpy(path, preset_path);
    char *slash = strrchr(path, '/');
    const char *filename = path;
    const char *directory = ".";
    if (slash) {
        *slash = 0;
        filename = slash + 1;
        directory = path[0] ? path : "/";
    }
    if (!*filename) {
        set_error(error, error_size, "preset filename is empty");
        return false;
    }
    int root = open(directory, O_RDONLY | O_CLOEXEC | O_DIRECTORY);
    if (root < 0) {
        set_error(error, error_size, "cannot open preset directory");
        return false;
    }
    int preset_fd = openat(root, filename, O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
    char *preset_text = NULL;
    size_t preset_length = 0;
    bool ok = preset_fd >= 0 && read_fd(preset_fd, RA_PRESET_MAX, &preset_text, &preset_length);
    if (preset_fd >= 0) close(preset_fd);
    if (!ok) {
        close(root);
        set_error(error, error_size, "cannot read preset (regular file, max 256 KiB)");
        return false;
    }
    if (!hle_ra_preset_parse(preset_text, preset_length, &loaded.preset, error, error_size)) {
        free(preset_text);
        close(root);
        return false;
    }
    free(preset_text);

    size_t total = 0;
    for (unsigned i = 0; i < loaded.preset.pass_count; ++i) {
        int fd = open_relative_file(root, loaded.preset.passes[i].shader);
        if (fd < 0 || !read_fd(fd, HLE_RA_SOURCE_MAX, &loaded.sources[i],
                               &loaded.source_lengths[i])) {
            if (fd >= 0) close(fd);
            close(root);
            hle_ra_program_unload(&loaded);
            set_error(error, error_size, "cannot read shader pass safely (max 256 KiB each)");
            return false;
        }
        close(fd);
        total += loaded.source_lengths[i];
        if (total > HLE_RA_TOTAL_SOURCE_MAX) {
            close(root);
            hle_ra_program_unload(&loaded);
            set_error(error, error_size, "shader sources exceed 2 MiB total");
            return false;
        }
    }
    close(root);
    *out = loaded;
    if (error && error_size) error[0] = 0;
    return true;
}
