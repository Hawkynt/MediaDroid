/* greedy2frame-harness.c: runs xine-lib's port of DScaler's Greedy 2-Frame (greedy2frame_template.c, the MMXEXT
 * variant; GPL-2.0, fetched by make-greedy.sh at build time and never part of the app) on grey frames as YUY2 with flat
 * chroma — the oracle Deinterlacer's GREEDY_2FRAME is held to. Writes the luma of the frame it makes.
 *   greedy2frame-harness <in.gray> <width> <height> <out.gray> <f0> <f1> <f2> <bottom_field> <second_field>
 * f0, f1 and f2 are frame numbers in <in.gray>, newest first, as xine's deinterlace_frame_data_t has them. */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define ARCH_X86 1
typedef union {
    uint64_t uq;
    uint8_t ub[8];
} mmx_t;
typedef struct {
    uint8_t *f0, *f1, *f2;
} deinterlace_frame_data_t;
#define xine_fast_memcpy memcpy
#define GREEDYTWOFRAMETHRESHOLD 4
#define GREEDYTWOFRAMETHRESHOLD2 8
#define IS_MMXEXT 1
#include "greedy2frame_template.c"

int main(int argc, char** argv) {
    if (argc != 10) return 2;
    const int w = atoi(argv[2]), h = atoi(argv[3]);
    FILE* in = fopen(argv[1], "rb");
    if (!in) return 1;
    fseek(in, 0, SEEK_END);
    const long size = ftell(in);
    fseek(in, 0, SEEK_SET);
    uint8_t* grey = malloc(size);
    if (fread(grey, 1, size, in) != (size_t)size) return 1;
    fclose(in);
    const int frames = size / (w * h);
    uint8_t** yuy2 = malloc(sizeof(uint8_t*) * frames);
    for (int k = 0; k < frames; k++) {
        yuy2[k] = aligned_alloc(16, (size_t)w * h * 2);
        for (int i = 0; i < w * h; i++) {
            yuy2[k][2 * i] = grey[k * w * h + i];
            yuy2[k][2 * i + 1] = 128;
        }
    }
    deinterlace_frame_data_t data = {yuy2[atoi(argv[5])], yuy2[atoi(argv[6])], yuy2[atoi(argv[7])]};
    uint8_t* out = aligned_alloc(16, (size_t)w * h * 2);
    memset(out, 0, (size_t)w * h * 2);
    DeinterlaceGreedy2Frame_MMXEXT(out, w * 2, &data, atoi(argv[8]), atoi(argv[9]), w, h);
    FILE* o = fopen(argv[4], "wb");
    if (!o) return 1;
    for (int i = 0; i < w * h; i++) fputc(out[2 * i], o);
    fclose(o);
    return 0;
}
