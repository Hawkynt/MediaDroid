/*
 * FFmpeg's own deblock filter (libavfilter/vf_deblock.c, LGPL-2.1-or-later) on a binary PPM, each RGB channel a plane:
 * its edge filters, pulled out of the file by make-expected.sh into filters.h, and its order over the picture
 * (filter_frame's loop, below as it is there). make-expected.sh builds it twice: with the filters as FFmpeg has them,
 * which it holds to FFmpeg's command-line output, and with the vertical-edge filters moving on to the next row whether
 * they filtered one or not (FFmpeg's `dst += dst_linesize` moved into the loop's head), as the horizontal ones do.
 *
 *   harness <in.ppm> <out.ppm> <weak|strong> <alpha> <beta> <gamma> <delta>
 */
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define FFABS(a) ((a) >= 0 ? (a) : (-(a)))
#define FFMIN(a, b) ((a) > (b) ? (b) : (a))
static int av_clip(int a, int amin, int amax) { return a < amin ? amin : a > amax ? amax : a; }

#include "filters.h"

int main(int argc, char **argv)
{
    if (argc != 8) return 2;
    FILE *in = fopen(argv[1], "rb");
    int width, height, maxval;
    if (!in || fscanf(in, "P6 %d %d %d", &width, &height, &maxval) != 3 || maxval != 255) return 2;
    fgetc(in);
    uint8_t *rgb = malloc((size_t)width * height * 3);
    if (fread(rgb, 3, (size_t)width * height, in) != (size_t)width * height) return 2;
    fclose(in);
    const int strong = !strcmp(argv[3], "strong");
    void (*deblockh)(uint8_t *, ptrdiff_t, int, int, int, int, int, int) =
        strong ? deblockh8_strong : deblockh8_weak;
    void (*deblockv)(uint8_t *, ptrdiff_t, int, int, int, int, int, int) =
        strong ? deblockv8_strong : deblockv8_weak;
    // config_output: the thresholds as FFmpeg keeps them, floats times the maximum, cut to integers.
    const int max = 255;
    const int ath = strtof(argv[4], NULL) * max;
    const int bth = strtof(argv[5], NULL) * max;
    const int gth = strtof(argv[6], NULL) * max;
    const int dth = strtof(argv[7], NULL) * max;
    const int block = 8;
    uint8_t *plane = malloc((size_t)width * height);
    for (int c = 0; c < 3; c++) {
        for (int i = 0; i < width * height; i++) plane[i] = rgb[i * 3 + c];
        uint8_t *dst = plane;
        const ptrdiff_t linesize = width;
        int x, y;
        // filter_frame, for one plane.
        for (x = block; x < width; x += block)
            deblockv(dst + x, linesize, FFMIN(block, height), ath, bth, gth, dth, max);

        for (y = block; y < height - block; y += block) {
            dst += linesize * block;

            deblockh(dst, linesize, FFMIN(block, width), ath, bth, gth, dth, max);

            for (x = block; x < width; x += block) {
                deblockh(dst + x, linesize, FFMIN(block, width - x), ath, bth, gth, dth, max);
                deblockv(dst + x, linesize, FFMIN(block, height - y), ath, bth, gth, dth, max);
            }
        }

        dst += linesize * block;
        for (x = block; x < width; x += block)
            deblockv(dst + x, linesize, FFMIN(block, height - y), ath, bth, gth, dth, max);
        for (int i = 0; i < width * height; i++) rgb[i * 3 + c] = plane[i];
    }
    FILE *out = fopen(argv[2], "wb");
    fprintf(out, "P6\n%d %d\n255\n", width, height);
    fwrite(rgb, 3, (size_t)width * height, out);
    fclose(out);
    return 0;
}
