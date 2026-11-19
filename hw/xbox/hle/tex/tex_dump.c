#include "hw/xbox/hle/base.h"
#include "hw/xbox/hle/tex/tex_dump.h"

#include <pthread.h>

#define TEX_DUMP_QUEUE_LIMIT 4

typedef struct TexDumpItem {
    struct TexDumpItem *next;
    uint32_t id, format, width, height, depth, levels, faces, size;
    uint8_t *pixels;
} TexDumpItem;

static pthread_once_t g_dump_once = PTHREAD_ONCE_INIT;
static pthread_mutex_t g_dump_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_dump_cond = PTHREAD_COND_INITIALIZER;
static TexDumpItem *g_dump_head, *g_dump_tail;
static unsigned g_dump_queued;
static char g_dump_dir[PATH_MAX];

static bool dump_write_dds(const TexDumpItem *item)
{
    /* DDS_HEADER, serialized as little-endian DWORDs (31 words). */
    uint32_t h[31] = {0};
    h[0] = 124;
    h[1] = 0x1 | 0x2 | 0x4 | 0x1000; /* CAPS | HEIGHT | WIDTH | PIXELFORMAT */
    if (item->levels > 1) h[1] |= 0x20000;
    if (item->depth > 1) h[1] |= 0x800000;
    h[2] = item->height;
    h[3] = item->width;
    h[5] = item->depth > 1 ? item->depth : 0;
    h[6] = item->levels;
    h[18] = 32; /* DDS_PIXELFORMAT size */
    h[19] = 0x4; /* DDPF_FOURCC */
    if (item->format == 0x0C || item->format == 0x0E || item->format == 0x0F) {
        h[20] = item->format == 0x0C ? 0x31545844 :
                item->format == 0x0E ? 0x33545844 : 0x35545844;
    } else {
        h[19] = 0x41; /* RGB | ALPHAPIXELS */
        h[20] = 0;
        h[21] = 32;
        h[22] = 0x00ff0000;
        h[23] = 0x0000ff00;
        h[24] = 0x000000ff;
        h[25] = 0xff000000;
    }
    h[26] = 0x1000; /* TEXTURE */
    if (item->levels > 1) h[26] |= 0x8 | 0x400000;
    if (item->depth > 1) h[26] |= 0x200000;
    if (item->faces == 6) h[26] |= 0x8 | 0x400;
    h[27] = item->faces == 6 ? 0xFE00 : 0;
    h[28] = item->depth > 1 ? 0x200000 : 0;

    char path[PATH_MAX];
    int n = snprintf(path, sizeof(path), "%s/tex_%08x_%ux%u_f%x.dds",
                     g_dump_dir, item->id, item->width, item->height, item->format);
    if (n <= 0 || (size_t)n >= sizeof(path)) return false;
    FILE *f = fopen(path, "wb");
    if (!f) return false;
    bool ok = fwrite("DDS ", 1, 4, f) == 4 &&
              fwrite(h, sizeof(h), 1, f) == 1 &&
              fwrite(item->pixels, 1, item->size, f) == item->size;
    if (fclose(f)) ok = false;
    return ok;
}

static void *tex_dump_worker(void *unused)
{
    (void)unused;
    for (;;) {
        pthread_mutex_lock(&g_dump_lock);
        while (!g_dump_head) pthread_cond_wait(&g_dump_cond, &g_dump_lock);
        TexDumpItem *item = g_dump_head;
        g_dump_head = item->next;
        if (!g_dump_head) g_dump_tail = NULL;
        g_dump_queued--;
        pthread_mutex_unlock(&g_dump_lock);

        bool ok = dump_write_dds(item);
        if (!ok) {
            fprintf(stderr, "hakuX texture dump: failed to write texture %u\n", item->id);
        }
        free(item->pixels);
        free(item);
    }
    return NULL;
}

static void tex_dump_start(void)
{
    const char *dir = getenv("HAKUX_TEXTURE_DUMP_DIR");
    if (!dir || !dir[0]) return;
    if (snprintf(g_dump_dir, sizeof(g_dump_dir), "%s", dir) >= (int)sizeof(g_dump_dir)) {
        g_dump_dir[0] = 0;
        return;
    }
    pthread_t thread;
    if (pthread_create(&thread, NULL, tex_dump_worker, NULL)) {
        g_dump_dir[0] = 0;
        return;
    }
    pthread_detach(thread);
}

bool hle_tex_dump_submit(uint32_t id, uint32_t format, uint32_t width, uint32_t height,
                         uint32_t depth, uint32_t levels, uint32_t faces,
                         const void *pixels, uint32_t size)
{
    pthread_once(&g_dump_once, tex_dump_start);
    if (!g_dump_dir[0] || !pixels || !size || !width || !height ||
        (format != 0x0C && format != 0x0E && format != 0x0F &&
         format != 0x06 && format != 0x12)) {
        return false;
    }
    TexDumpItem *item = calloc(1, sizeof(*item));
    if (!item) return false;
    item->pixels = malloc(size);
    if (!item->pixels) { free(item); return false; }
    memcpy(item->pixels, pixels, size);
    item->id = id; item->format = format; item->width = width; item->height = height;
    item->depth = depth; item->levels = levels; item->faces = faces; item->size = size;

    pthread_mutex_lock(&g_dump_lock);
    if (g_dump_queued >= TEX_DUMP_QUEUE_LIMIT) {
        pthread_mutex_unlock(&g_dump_lock);
        free(item->pixels); free(item);
        return false;
    }
    if (g_dump_tail) g_dump_tail->next = item; else g_dump_head = item;
    g_dump_tail = item;
    g_dump_queued++;
    pthread_cond_signal(&g_dump_cond);
    pthread_mutex_unlock(&g_dump_lock);
    return true;
}
