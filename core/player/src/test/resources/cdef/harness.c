/*
 * dav1d's own CDEF (src/cdef_tmpl.c and adjust_strength from src/cdef_apply_tmpl.c, 1.5.0, BSD-2-Clause) on a binary
 * PPM, as the app's deringing runs it on an RGB picture: each full 8 x 8 block's direction and variance found on its
 * luma, (77 R + 150 G + 29 B + 128) >> 8, the primary strength adjusted by that variance as dav1d adjusts the luma's,
 * then each of R, G and B filtered with that direction and those strengths, every read from the unfiltered picture and
 * nothing beyond its edges — dav1d's own filter, its edges flagged where the picture ends. Pixels outside full blocks
 * stay as they are. make-expected.sh fetches dav1d's files and builds this against them.
 *
 *   harness <in.ppm> <out.ppm> <primary 0..15> <secondary 0|1|2|4> <damping 3..6>
 */
#include "cdef_tmpl.c"

#include <stdio.h>
#include <string.h>

#include "directions.h"

static int adjust_strength(const int strength, const unsigned var) {
    if (!var) return 0;
    const int i = var >> 6 ? imin(ulog2(var >> 6), 12) : 0;
    return (strength * (4 + i) + 8) >> 4;
}

int main(int argc, char **argv)
{
    if (argc != 6) return 2;
    FILE *in = fopen(argv[1], "rb");
    int w, h, maxval;
    if (!in || fscanf(in, "P6 %d %d %d", &w, &h, &maxval) != 3 || maxval != 255) return 2;
    fgetc(in);
    uint8_t *rgb = malloc((size_t)w * h * 3);
    if (fread(rgb, 3, (size_t)w * h, in) != (size_t)w * h) return 2;
    fclose(in);
    const int pri = atoi(argv[3]), sec = atoi(argv[4]), damping = atoi(argv[5]);
    uint8_t *luma = malloc((size_t)w * h);
    for (int i = 0; i < w * h; i++)
        luma[i] = (77 * rgb[i * 3] + 150 * rgb[i * 3 + 1] + 29 * rgb[i * 3 + 2] + 128) >> 8;
    uint8_t *out = malloc((size_t)w * h * 3);
    memcpy(out, rgb, (size_t)w * h * 3);
    uint8_t *plane = malloc((size_t)w * h), *filtered = malloc((size_t)w * h);
    for (int c = 0; c < 3; c++) {
        for (int i = 0; i < w * h; i++) plane[i] = filtered[i] = rgb[i * 3 + c];
        for (int by = 0; by + 8 <= h; by += 8) {
            for (int bx = 0; bx + 8 <= w; bx += 8) {
                unsigned var = 0;
                int dir = 0, adj = 0;
                if (pri) {
                    dir = cdef_find_dir_c(luma + by * w + bx, w, &var);
                    adj = adjust_strength(pri, var);
                }
                if (!adj && !sec) continue;
                enum CdefEdgeFlags edges = 0;
                if (bx > 0) edges |= CDEF_HAVE_LEFT;
                if (bx + 8 < w) edges |= CDEF_HAVE_RIGHT;
                if (by > 0) edges |= CDEF_HAVE_TOP;
                if (by + 8 < h) edges |= CDEF_HAVE_BOTTOM;
                pixel left[8][2];
                for (int y = 0; y < 8; y++)
                    for (int x = 0; x < 2; x++)
                        left[y][x] = bx > 0 ? plane[(by + y) * w + bx - 2 + x] : 0;
                const pixel *top = by > 0 ? plane + (by - 2) * w + bx : NULL;
                const pixel *bottom = by + 8 < h ? plane + (by + 8) * w + bx : NULL;
                // Filtered in a copy of the unfiltered picture, left to right, so its right neighbours are unfiltered.
                // As dav1d_cdef_brow calls it: the adjusted strength with the block's direction (0 without primary).
                pixel *dst = filtered + by * w + bx;
                cdef_filter_block_8x8_c(dst, w, (const pixel (*)[2])left, top, bottom, adj, sec, dir, damping, edges);
            }
        }
        for (int i = 0; i < w * h; i++) out[i * 3 + c] = filtered[i];
    }
    FILE *o = fopen(argv[2], "wb");
    fprintf(o, "P6\n%d %d\n255\n", w, h);
    fwrite(out, 3, (size_t)w * h, o);
    fclose(o);
    return 0;
}
