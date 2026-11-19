#ifndef HW_XBOX_HLE_TEX_DUMP_H
#define HW_XBOX_HLE_TEX_DUMP_H

#include <stdbool.h>
#include <stdint.h>

/* Copies the converted upload into a bounded background queue. The caller
 * never waits for filesystem I/O; false means dumping is disabled/full. */
bool hle_tex_dump_submit(uint32_t id, uint32_t format, uint32_t width, uint32_t height,
                         uint32_t depth, uint32_t levels, uint32_t faces,
                         const void *pixels, uint32_t size);

#endif
