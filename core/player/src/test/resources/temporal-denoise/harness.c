/*
 * FFmpeg's own temporal denoiser (libavfilter/vf_atadenoise.c, LGPL-2.1-or-later; its parallel, unweighted filter):
 * the middle one of an odd number of binary PPMs denoised against the others, each RGB channel a plane, the thresholds
 * as config_input works them out. filters.h is the file's FILTER_ROW, pulled out by make-expected.sh.
 *
 *   harness <out.ppm> <a> <b> <frame.ppm>...
 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

#define FFABS(a) ((a) >= 0 ? (a) : (-(a)))
#define SIZE 129

#include "filters.h"

static uint8_t *read_ppm(const char *path, int *w, int *h)
{
    FILE *in = fopen(path, "rb");
    int maxval;
    if (!in || fscanf(in, "P6 %d %d %d", w, h, &maxval) != 3 || maxval != 255) exit(2);
    fgetc(in);
    uint8_t *rgb = malloc((size_t)*w * *h * 3);
    if (fread(rgb, 3, (size_t)*w * *h, in) != (size_t)*w * *h) exit(2);
    fclose(in);
    return rgb;
}

int main(int argc, char **argv)
{
    const int size = argc - 4;
    if (size < 3 || !(size & 1)) return 2;
    const int mid = size / 2;
    int w = 0, h = 0;
    uint8_t *frames[SIZE];
    for (int i = 0; i < size; i++) frames[i] = read_ppm(argv[4 + i], &w, &h);
    // config_input, 8 bits: the fraction times 256, less one, cut to an integer.
    const int thra = strtof(argv[2], NULL) * (1 << 8) - 1;
    const int thrb = strtof(argv[3], NULL) * (1 << 8) - 1;
    uint8_t *planes[SIZE], *dst = malloc((size_t)w * h), *out = malloc((size_t)w * h * 3);
    for (int i = 0; i < size; i++) planes[i] = malloc((size_t)w * h);
    for (int c = 0; c < 3; c++) {
        for (int i = 0; i < size; i++)
            for (int p = 0; p < w * h; p++) planes[i][p] = frames[i][p * 3 + c];
        for (int y = 0; y < h; y++) {
            const uint8_t *srcf[SIZE];
            for (int i = 0; i < size; i++) srcf[i] = planes[i] + y * w;
            filter_row8(planes[mid] + y * w, dst + y * w, srcf, w, mid, size, thra, thrb, NULL);
        }
        for (int p = 0; p < w * h; p++) out[p * 3 + c] = dst[p];
    }
    FILE *o = fopen(argv[1], "wb");
    fprintf(o, "P6\n%d %d\n255\n", w, h);
    fwrite(out, 3, (size_t)w * h, o);
    fclose(o);
    return 0;
}
