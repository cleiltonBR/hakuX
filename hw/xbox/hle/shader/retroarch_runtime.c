#define _GNU_SOURCE
#include "hw/xbox/hle/shader/retroarch_runtime.h"
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>
#include <unistd.h>
#define RA_PRESET_MAX (256u * 1024u)
#define RA_PATH_MAX 4096u
static void set_error(char *dst, size_t size, const char *message) { if (dst && size) snprintf(dst, size, "%s", message); }
static bool read_fd(int fd, size_t limit, char **data, size_t *length)
{
    struct stat st;
    if (fstat(fd, &st) || !S_ISREG(st.st_mode) || st.st_size <= 0 || (uint64_t)st.st_size > limit) return false;
    size_t size = (size_t)st.st_size; char *buffer = malloc(size + 1); if (!buffer) return false;
    size_t done = 0;
    while (done < size) { ssize_t n = read(fd, buffer + done, size - done); if (n < 0 && errno == EINTR) continue; if (n <= 0) { free(buffer); return false; } done += (size_t)n; }
    buffer[size] = 0; *data = buffer; *length = size; return true;
}
static int open_relative_file(int root, const char *path)
{
    if (!path || !*path || path[0] == '/' || path[0] == '\\') return -1;
    char *copy = malloc(strlen(path) + 1); if (!copy) return -1; strcpy(copy, path);
    int dir = dup(root); if (dir < 0) { free(copy); return -1; }
    char *part = copy; int result = -1;
    for (;;) {
        char *slash = strchr(part, '/'); if (slash) *slash = 0;
        if (!*part || !strcmp(part, ".") || !strcmp(part, "..") || strchr(part, '\\')) break;
        if (slash) { int next = openat(dir, part, O_RDONLY | O_CLOEXEC | O_DIRECTORY | O_NOFOLLOW); if (next < 0) break; close(dir); dir = next; part = slash + 1; if (!*part) break; }
        else { result = openat(dir, part, O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK); break; }
    }
    close(dir); free(copy); return result;
}

#define RA_REFERENCE_MAX_DEPTH 16u /* bounded inheritance chain */
#define RA_EXPANDED_MAX (1024u * 1024u)
static bool ra_append(char **dst, size_t *used, const char *data, size_t length)
{
    if (!dst || !used || !data || length > RA_EXPANDED_MAX - *used) return false;
    char *next = realloc(*dst, *used + length + 1);
    if (!next) return false;
    memcpy(next + *used, data, length);
    *used += length;
    next[*used] = 0;
    *dst = next;
    return true;
}
static bool ra_normalize_relative(const char *base_file, const char *value,
                                  char *out, size_t out_size)
{
    if (!value || !*value || value[0] == '/' || value[0] == '\\' ||
        strchr(value, '\\') || (value[0] && value[1] == ':')) return false;
    char joined[RA_PATH_MAX * 2];
    const char *slash = strrchr(base_file, '/');
    size_t base_len = slash ? (size_t)(slash - base_file + 1) : 0;
    if (base_len + strlen(value) >= sizeof(joined)) return false;
    if (base_len) memcpy(joined, base_file, base_len);
    strcpy(joined + base_len, value);
    char *parts[RA_PATH_MAX / 2];
    size_t count = 0;
    char *save = NULL;
    for (char *part = strtok_r(joined, "/", &save); part;
         part = strtok_r(NULL, "/", &save)) {
        if (!strcmp(part, ".") || !*part) continue;
        if (!strcmp(part, "..")) {
            if (!count) return false;
            --count;
        } else {
            if (count >= sizeof(parts) / sizeof(parts[0])) return false;
            parts[count++] = part;
        }
    }
    size_t used = 0;
    for (size_t i = 0; i < count; ++i) {
        size_t n = strlen(parts[i]);
        if (used + n + (i != 0) + 1 > out_size) return false;
        if (i) out[used++] = '/';
        memcpy(out + used, parts[i], n);
        used += n;
    }
    out[used] = 0;
    return used != 0;
}
static bool ra_is_preset_reference(const char *path)
{
    const char *ext = strrchr(path, '.');
    return ext && (!strcasecmp(ext, ".glslp") || !strcasecmp(ext, ".slangp") || !strcasecmp(ext, ".params"));
}
static bool ra_expand_preset(int root, const char *relative_path,
                             char **expanded, size_t *expanded_length,
                             char active[RA_REFERENCE_MAX_DEPTH][RA_PATH_MAX],
                             unsigned depth)
{
    if (depth >= RA_REFERENCE_MAX_DEPTH) return false;
    for (unsigned i = 0; i < depth; ++i)
        if (!strcmp(active[i], relative_path)) return false;
    if (strlen(relative_path) >= RA_PATH_MAX) return false;
    strcpy(active[depth], relative_path);
    int fd = open_relative_file(root, relative_path);
    char *source = NULL;
    size_t source_length = 0;
    if (fd < 0 || !read_fd(fd, RA_PRESET_MAX, &source, &source_length)) {
        if (fd >= 0) close(fd);
        return false;
    }
    close(fd);
    char *copy = source;
    char *save = NULL;
    bool ok = true;
    for (char *line = strtok_r(copy, "\n", &save); line && ok;
         line = strtok_r(NULL, "\n", &save)) {
        char *trimmed = line;
        while (*trimmed == ' ' || *trimmed == '\t' || *trimmed == '\r') ++trimmed;
        if (!strncmp(trimmed, "#reference", 10) &&
            (trimmed[10] == ' ' || trimmed[10] == '\t')) {
            char *quote = strchr(trimmed + 10, '"');
            char *end = quote ? strchr(quote + 1, '"') : NULL;
            if (!quote || !end) { ok = false; break; }
            *end = 0;
            const char *reference = quote + 1;
            /* Defer core/driver-specific macro references to the frontend. */
            if (strchr(reference, '
                char normalized[RA_PATH_MAX];
                if (!ra_normalize_relative(relative_path, reference,
                                           normalized, sizeof(normalized)) ||
                    !ra_expand_preset(root, normalized, expanded,
                                      expanded_length, active, depth + 1))
                    ok = false;
            }
            continue; /* .params and other reference assets are not passes. */
        }
        char *eq = strchr(trimmed, '=');
        if (eq) {
            char *key_end = eq;
            while (key_end > trimmed && (key_end[-1] == ' ' || key_end[-1] == '\t')) --key_end;
            char *key_start = trimmed;
            while (key_start < key_end && (*key_start == ' ' || *key_start == '\t')) ++key_start;
            if ((size_t)(key_end - key_start) > 6 &&
                !strncmp(key_start, "shader", 6) && key_start[6] >= '0' &&
                key_start[6] <= '9') {
                char *value = eq + 1;
                while (*value == ' ' || *value == '\t') ++value;
                char *value_end = value + strlen(value);
                while (value_end > value && (value_end[-1] == '\r' ||
                       value_end[-1] == ' ' || value_end[-1] == '\t')) --value_end;
                if (value < value_end && *value == '"' && value_end[-1] == '"') {
                    ++value; --value_end;
                }
                char raw[RA_PATH_MAX], normalized[RA_PATH_MAX];
                size_t n = (size_t)(value_end - value);
                if (!n || n >= sizeof(raw)) { ok = false; break; }
                memcpy(raw, value, n); raw[n] = 0;
                if (!ra_normalize_relative(relative_path, raw,
                                           normalized, sizeof(normalized))) {
                    ok = false; break;
                }
                char rewritten[RA_PATH_MAX + 64];
                int written = snprintf(rewritten, sizeof(rewritten), "%.*s = %s\n",
                    (int)(key_end - trimmed), key_start, normalized);
                if (written < 0 || (size_t)written >= sizeof(rewritten) ||
                    !ra_append(expanded, expanded_length, rewritten, (size_t)written))
                    ok = false;
                continue;
            }
        }
        size_t n = strlen(line);
        if (!ra_append(expanded, expanded_length, line, n) ||
            !ra_append(expanded, expanded_length, "\n", 1)) ok = false;
    }
    free(source);
    return ok;
}

void hle_ra_program_unload(HleRaProgram *program)
{
    if (!program) return;
    for (unsigned i = 0; i < HLE_RA_MAX_PASSES; ++i) { free(program->sources[i]); program->sources[i] = NULL; program->source_lengths[i] = 0; }
    if (program->source_root_fd > STDERR_FILENO) close(program->source_root_fd);
    program->source_root_fd = -1; memset(&program->preset, 0, sizeof(program->preset));
}
bool hle_ra_program_load(const char *preset_path, HleRaProgram *out, char *error, size_t error_size)
{
    if (!preset_path || !*preset_path || !out || strlen(preset_path) >= RA_PATH_MAX) { set_error(error, error_size, "invalid preset path"); return false; }
    HleRaProgram loaded = { .source_root_fd = -1 }; char path[RA_PATH_MAX]; strcpy(path, preset_path);
    char *slash = strrchr(path, '/'); const char *filename = path; const char *directory = ".";
    if (slash) { *slash = 0; filename = slash + 1; directory = path[0] ? path : "/"; }
    if (!*filename) { set_error(error, error_size, "preset filename is empty"); return false; }
    int root = open(directory, O_RDONLY | O_CLOEXEC | O_DIRECTORY);
    if (root < 0) { set_error(error, error_size, "cannot open preset directory"); return false; }
    int preset_fd = openat(root, filename, O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
    char *preset_text = NULL; size_t preset_length = 0;
    bool ok = preset_fd >= 0 && read_fd(preset_fd, RA_PRESET_MAX, &preset_text, &preset_length);
    if (preset_fd >= 0) close(preset_fd);
    if (!ok) { close(root); set_error(error, error_size, "cannot read preset (regular file, max 256 KiB)"); return false; }
    free(preset_text);
    char *expanded_preset = NULL;
    size_t expanded_length = 0;
    char active[RA_REFERENCE_MAX_DEPTH][RA_PATH_MAX] = {{0}};
    if (!ra_expand_preset(root, filename, &expanded_preset, &expanded_length,
                          active, 0) ||
        !hle_ra_preset_parse(expanded_preset, expanded_length,
                             &loaded.preset, error, error_size)) {
        free(expanded_preset);
        close(root);
        if (error && error_size && !error[0])
            set_error(error, error_size, "cannot resolve preset #reference chain safely");
        return false;
    }
    free(expanded_preset);
    size_t total = 0;
    for (unsigned i = 0; i < loaded.preset.pass_count; ++i) {
        int fd = open_relative_file(root, loaded.preset.passes[i].shader);
        if (fd < 0 || !read_fd(fd, HLE_RA_SOURCE_MAX, &loaded.sources[i], &loaded.source_lengths[i])) {
            if (fd >= 0) close(fd); close(root); hle_ra_program_unload(&loaded);
            set_error(error, error_size, "cannot read shader pass safely (max 256 KiB each)"); return false;
        }
        close(fd); total += loaded.source_lengths[i];
        if (total > HLE_RA_TOTAL_SOURCE_MAX) { close(root); hle_ra_program_unload(&loaded); set_error(error, error_size, "shader sources exceed 2 MiB total"); return false; }
    }
    loaded.source_root_fd = dup(root); close(root);
    if (loaded.source_root_fd < 0) { hle_ra_program_unload(&loaded); set_error(error, error_size, "cannot retain preset directory for includes"); return false; }
    hle_ra_program_unload(out); *out = loaded; if (error && error_size) error[0] = 0; return true;
}
bool hle_ra_include_cache_init(HleRaIncludeCache *cache, const HleRaProgram *program)
{
    if (!cache || !program || program->source_root_fd < 0) return false;
    memset(cache, 0, sizeof(*cache)); cache->root_fd = dup(program->source_root_fd); return cache->root_fd >= 0;
}
const char *hle_ra_include_resolve(void *ctx, const char *name, size_t *length)
{
    HleRaIncludeCache *cache = ctx; if (length) *length = 0;
    if (!cache || cache->root_fd < 0 || !name || !*name || strlen(name) >= RA_PATH_MAX) return NULL;
    for (unsigned i = 0; i < cache->count; ++i) if (!strcmp(cache->entries[i].name, name)) { if (length) *length = cache->entries[i].length; return cache->entries[i].data; }
    if (cache->count >= HLE_RA_INCLUDE_MAX) return NULL;
    int fd = open_relative_file(cache->root_fd, name); if (fd < 0) return NULL;
    char *data = NULL; size_t size = 0; bool ok = read_fd(fd, HLE_RA_SOURCE_MAX, &data, &size); close(fd);
    if (!ok || size > HLE_RA_TOTAL_SOURCE_MAX - cache->total_length) { free(data); return NULL; }
    char *key = strdup(name); if (!key) { free(data); return NULL; }
    HleRaIncludeEntry *entry = &cache->entries[cache->count++]; entry->name = key; entry->data = data; entry->length = size;
    cache->total_length += size; if (length) *length = size; return data;
}
void hle_ra_include_cache_finalize(HleRaIncludeCache *cache)
{
    if (!cache) return;
    for (unsigned i = 0; i < cache->count; ++i) { free(cache->entries[i].name); free(cache->entries[i].data); }
    if (cache->root_fd > STDERR_FILENO) close(cache->root_fd);
    memset(cache, 0, sizeof(*cache)); cache->root_fd = -1;
}


bool hle_ra_program_load_from_root(const char *library_root,
                                   const char *preset_relative_path,
                                   HleRaProgram *out, char *error,
                                   size_t error_size)
{
    if (!library_root || !*library_root || !preset_relative_path ||
        !*preset_relative_path || !out || strlen(preset_relative_path) >= RA_PATH_MAX) {
        set_error(error, error_size, "invalid shader library root or preset path");
        return false;
    }
    char normalized[RA_PATH_MAX];
    if (!ra_normalize_relative("", preset_relative_path, normalized, sizeof(normalized))) {
        set_error(error, error_size, "preset path must stay inside the shader library");
        return false;
    }
    int root = open(library_root, O_RDONLY | O_CLOEXEC | O_DIRECTORY | O_NOFOLLOW);
    if (root < 0) { set_error(error, error_size, "cannot open shader library root"); return false; }
    HleRaProgram loaded = { .source_root_fd = -1 };
    char *expanded = NULL;
    size_t expanded_length = 0;
    char active[RA_REFERENCE_MAX_DEPTH][RA_PATH_MAX] = {{0}};
    if (!ra_expand_preset(root, normalized, &expanded, &expanded_length, active, 0) ||
        !hle_ra_preset_parse(expanded, expanded_length, &loaded.preset, error, error_size)) {
        free(expanded);
        close(root);
        if (error && error_size && !error[0])
            set_error(error, error_size, "cannot resolve preset references inside shader library");
        return false;
    }
    free(expanded);
    size_t total = 0;
    for (unsigned i = 0; i < loaded.preset.pass_count; ++i) {
        int fd = open_relative_file(root, loaded.preset.passes[i].shader);
        if (fd < 0 || !read_fd(fd, HLE_RA_SOURCE_MAX,
                               &loaded.sources[i], &loaded.source_lengths[i])) {
            if (fd >= 0) close(fd);
            close(root);
            hle_ra_program_unload(&loaded);
            set_error(error, error_size, "cannot read shader pass safely from library root");
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
    loaded.source_root_fd = dup(root);
    close(root);
    if (loaded.source_root_fd < 0) {
        hle_ra_program_unload(&loaded);
        set_error(error, error_size, "cannot retain shader library root for includes");
        return false;
    }
    hle_ra_program_unload(out);
    *out = loaded;
    if (error && error_size) error[0] = 0;
    return true;
}
)) continue;
            if (ra_is_preset_reference(reference)) {
                char normalized[RA_PATH_MAX];
                if (!ra_normalize_relative(relative_path, reference,
                                           normalized, sizeof(normalized)) ||
                    !ra_expand_preset(root, normalized, expanded,
                                      expanded_length, active, depth + 1))
                    ok = false;
            }
            continue; /* .params and other reference assets are not passes. */
        }
        char *eq = strchr(trimmed, '=');
        if (eq) {
            char *key_end = eq;
            while (key_end > trimmed && (key_end[-1] == ' ' || key_end[-1] == '\t')) --key_end;
            char *key_start = trimmed;
            while (key_start < key_end && (*key_start == ' ' || *key_start == '\t')) ++key_start;
            if ((size_t)(key_end - key_start) > 6 &&
                !strncmp(key_start, "shader", 6) && key_start[6] >= '0' &&
                key_start[6] <= '9') {
                char *value = eq + 1;
                while (*value == ' ' || *value == '\t') ++value;
                char *value_end = value + strlen(value);
                while (value_end > value && (value_end[-1] == '\r' ||
                       value_end[-1] == ' ' || value_end[-1] == '\t')) --value_end;
                if (value < value_end && *value == '"' && value_end[-1] == '"') {
                    ++value; --value_end;
                }
                char raw[RA_PATH_MAX], normalized[RA_PATH_MAX];
                size_t n = (size_t)(value_end - value);
                if (!n || n >= sizeof(raw)) { ok = false; break; }
                memcpy(raw, value, n); raw[n] = 0;
                if (!ra_normalize_relative(relative_path, raw,
                                           normalized, sizeof(normalized))) {
                    ok = false; break;
                }
                char rewritten[RA_PATH_MAX + 64];
                int written = snprintf(rewritten, sizeof(rewritten), "%.*s = %s\n",
                    (int)(key_end - trimmed), key_start, normalized);
                if (written < 0 || (size_t)written >= sizeof(rewritten) ||
                    !ra_append(expanded, expanded_length, rewritten, (size_t)written))
                    ok = false;
                continue;
            }
        }
        size_t n = strlen(line);
        if (!ra_append(expanded, expanded_length, line, n) ||
            !ra_append(expanded, expanded_length, "\n", 1)) ok = false;
    }
    free(source);
    return ok;
}

void hle_ra_program_unload(HleRaProgram *program)
{
    if (!program) return;
    for (unsigned i = 0; i < HLE_RA_MAX_PASSES; ++i) { free(program->sources[i]); program->sources[i] = NULL; program->source_lengths[i] = 0; }
    if (program->source_root_fd > STDERR_FILENO) close(program->source_root_fd);
    program->source_root_fd = -1; memset(&program->preset, 0, sizeof(program->preset));
}
bool hle_ra_program_load(const char *preset_path, HleRaProgram *out, char *error, size_t error_size)
{
    if (!preset_path || !*preset_path || !out || strlen(preset_path) >= RA_PATH_MAX) { set_error(error, error_size, "invalid preset path"); return false; }
    HleRaProgram loaded = { .source_root_fd = -1 }; char path[RA_PATH_MAX]; strcpy(path, preset_path);
    char *slash = strrchr(path, '/'); const char *filename = path; const char *directory = ".";
    if (slash) { *slash = 0; filename = slash + 1; directory = path[0] ? path : "/"; }
    if (!*filename) { set_error(error, error_size, "preset filename is empty"); return false; }
    int root = open(directory, O_RDONLY | O_CLOEXEC | O_DIRECTORY);
    if (root < 0) { set_error(error, error_size, "cannot open preset directory"); return false; }
    int preset_fd = openat(root, filename, O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
    char *preset_text = NULL; size_t preset_length = 0;
    bool ok = preset_fd >= 0 && read_fd(preset_fd, RA_PRESET_MAX, &preset_text, &preset_length);
    if (preset_fd >= 0) close(preset_fd);
    if (!ok) { close(root); set_error(error, error_size, "cannot read preset (regular file, max 256 KiB)"); return false; }
    free(preset_text);
    char *expanded_preset = NULL;
    size_t expanded_length = 0;
    char active[RA_REFERENCE_MAX_DEPTH][RA_PATH_MAX] = {{0}};
    if (!ra_expand_preset(root, filename, &expanded_preset, &expanded_length,
                          active, 0) ||
        !hle_ra_preset_parse(expanded_preset, expanded_length,
                             &loaded.preset, error, error_size)) {
        free(expanded_preset);
        close(root);
        if (error && error_size && !error[0])
            set_error(error, error_size, "cannot resolve preset #reference chain safely");
        return false;
    }
    free(expanded_preset);
    size_t total = 0;
    for (unsigned i = 0; i < loaded.preset.pass_count; ++i) {
        int fd = open_relative_file(root, loaded.preset.passes[i].shader);
        if (fd < 0 || !read_fd(fd, HLE_RA_SOURCE_MAX, &loaded.sources[i], &loaded.source_lengths[i])) {
            if (fd >= 0) close(fd); close(root); hle_ra_program_unload(&loaded);
            set_error(error, error_size, "cannot read shader pass safely (max 256 KiB each)"); return false;
        }
        close(fd); total += loaded.source_lengths[i];
        if (total > HLE_RA_TOTAL_SOURCE_MAX) { close(root); hle_ra_program_unload(&loaded); set_error(error, error_size, "shader sources exceed 2 MiB total"); return false; }
    }
    loaded.source_root_fd = dup(root); close(root);
    if (loaded.source_root_fd < 0) { hle_ra_program_unload(&loaded); set_error(error, error_size, "cannot retain preset directory for includes"); return false; }
    hle_ra_program_unload(out); *out = loaded; if (error && error_size) error[0] = 0; return true;
}
bool hle_ra_include_cache_init(HleRaIncludeCache *cache, const HleRaProgram *program)
{
    if (!cache || !program || program->source_root_fd < 0) return false;
    memset(cache, 0, sizeof(*cache)); cache->root_fd = dup(program->source_root_fd); return cache->root_fd >= 0;
}
const char *hle_ra_include_resolve(void *ctx, const char *name, size_t *length)
{
    HleRaIncludeCache *cache = ctx; if (length) *length = 0;
    if (!cache || cache->root_fd < 0 || !name || !*name || strlen(name) >= RA_PATH_MAX) return NULL;
    for (unsigned i = 0; i < cache->count; ++i) if (!strcmp(cache->entries[i].name, name)) { if (length) *length = cache->entries[i].length; return cache->entries[i].data; }
    if (cache->count >= HLE_RA_INCLUDE_MAX) return NULL;
    int fd = open_relative_file(cache->root_fd, name); if (fd < 0) return NULL;
    char *data = NULL; size_t size = 0; bool ok = read_fd(fd, HLE_RA_SOURCE_MAX, &data, &size); close(fd);
    if (!ok || size > HLE_RA_TOTAL_SOURCE_MAX - cache->total_length) { free(data); return NULL; }
    char *key = strdup(name); if (!key) { free(data); return NULL; }
    HleRaIncludeEntry *entry = &cache->entries[cache->count++]; entry->name = key; entry->data = data; entry->length = size;
    cache->total_length += size; if (length) *length = size; return data;
}
void hle_ra_include_cache_finalize(HleRaIncludeCache *cache)
{
    if (!cache) return;
    for (unsigned i = 0; i < cache->count; ++i) { free(cache->entries[i].name); free(cache->entries[i].data); }
    if (cache->root_fd > STDERR_FILENO) close(cache->root_fd);
    memset(cache, 0, sizeof(*cache)); cache->root_fd = -1;
}


bool hle_ra_program_load_from_root(const char *library_root,
                                   const char *preset_relative_path,
                                   HleRaProgram *out, char *error,
                                   size_t error_size)
{
    if (!library_root || !*library_root || !preset_relative_path ||
        !*preset_relative_path || !out || strlen(preset_relative_path) >= RA_PATH_MAX) {
        set_error(error, error_size, "invalid shader library root or preset path");
        return false;
    }
    char normalized[RA_PATH_MAX];
    if (!ra_normalize_relative("", preset_relative_path, normalized, sizeof(normalized))) {
        set_error(error, error_size, "preset path must stay inside the shader library");
        return false;
    }
    int root = open(library_root, O_RDONLY | O_CLOEXEC | O_DIRECTORY | O_NOFOLLOW);
    if (root < 0) { set_error(error, error_size, "cannot open shader library root"); return false; }
    HleRaProgram loaded = { .source_root_fd = -1 };
    char *expanded = NULL;
    size_t expanded_length = 0;
    char active[RA_REFERENCE_MAX_DEPTH][RA_PATH_MAX] = {{0}};
    if (!ra_expand_preset(root, normalized, &expanded, &expanded_length, active, 0) ||
        !hle_ra_preset_parse(expanded, expanded_length, &loaded.preset, error, error_size)) {
        free(expanded);
        close(root);
        if (error && error_size && !error[0])
            set_error(error, error_size, "cannot resolve preset references inside shader library");
        return false;
    }
    free(expanded);
    size_t total = 0;
    for (unsigned i = 0; i < loaded.preset.pass_count; ++i) {
        int fd = open_relative_file(root, loaded.preset.passes[i].shader);
        if (fd < 0 || !read_fd(fd, HLE_RA_SOURCE_MAX,
                               &loaded.sources[i], &loaded.source_lengths[i])) {
            if (fd >= 0) close(fd);
            close(root);
            hle_ra_program_unload(&loaded);
            set_error(error, error_size, "cannot read shader pass safely from library root");
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
    loaded.source_root_fd = dup(root);
    close(root);
    if (loaded.source_root_fd < 0) {
        hle_ra_program_unload(&loaded);
        set_error(error, error_size, "cannot retain shader library root for includes");
        return false;
    }
    hle_ra_program_unload(out);
    *out = loaded;
    if (error && error_size) error[0] = 0;
    return true;
}
