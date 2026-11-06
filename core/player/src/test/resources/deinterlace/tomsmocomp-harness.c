/* tomsmocomp-harness.c: runs GStreamer's TomsMoComp (gst-plugins-good's tvtime/tomsmocomp, Tom Barry's, LGPL; fetched by
 * make-tomsmocomp.sh at build time) — its MMXEXT search at the default effort, 5, which GStreamer's own builds leave
 * out on x86-64, where only its C fallback (a bob, no search) runs — on grey frames as YUY2 with flat chroma: the oracle
 * Deinterlacer's TOMSMOCOMP is held to. Writes the luma of the frame it makes: the kept field's rows as they are, the
 * other field's rebuilt.
 *   tomsmocomp-harness <in.gray> <width> <height> <out.gray> <weave> <weaveP> <copy> <copyP> <rebuilt rows 0|1>
 * weave and weaveP are the frames of the fields after and before the kept one, copy the kept field's frame and copyP
 * the frame before it, as GStreamer's pWeaveSrc, pWeaveSrcP, pCopySrc and pCopySrcP (frame numbers in <in.gray>). */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define HAVE_CPU_X86_64 1
#define ARCH_X86_64 1
#define IS_MMXEXT
#define SIMD_TYPE MMXEXT
#define USE_FOR_DSCALER
#include "x86-64_macros.inc"
#include "tomsmocomp/tomsmocompmacros.h"
#define TopFirst IsOdd
#define SEFUNC(x)                                                                                                     \
    Search_Effort_MMXEXT_##x(int src_pitch, int dst_pitch, int rowsize, const unsigned char* pWeaveSrc,              \
                             const unsigned char* pWeaveSrcP, unsigned char* pWeaveDest, int IsOdd,                  \
                             const unsigned char* pCopySrc, const unsigned char* pCopySrcP, int FldHeight)
#include "tomsmocomp/TomsMoCompAll2.inc"

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
    const int weave = atoi(argv[5]), weaveP = atoi(argv[6]), copy = atoi(argv[7]), copyP = atoi(argv[8]);
    const int rebuilt = atoi(argv[9]);
    const int pitch = w * 2;
    const int lines = h / 2;
    uint8_t* out = aligned_alloc(16, (size_t)w * h * 2);
    memset(out, 0, (size_t)w * h * 2);
    for (int y = 1 - rebuilt; y < h; y += 2) memcpy(out + y * pitch, yuy2[copy] + y * pitch, pitch);
    const uint8_t* copySrc = yuy2[copy] + (1 - rebuilt) * pitch;
    uint8_t* weaveDest = out + rebuilt * pitch;
    /* As GStreamer's frame loop: the first and last rebuilt lines are the kept field's first and last. */
    memcpy(weaveDest, copySrc, pitch);
    memcpy(weaveDest + (lines - 1) * 2 * pitch, copySrc + (lines - 1) * 2 * pitch, pitch);
    Search_Effort_MMXEXT_5(pitch * 2, pitch, pitch, yuy2[weave] + rebuilt * pitch, yuy2[weaveP] + rebuilt * pitch,
                           weaveDest, rebuilt, copySrc, yuy2[copyP] + (1 - rebuilt) * pitch, lines);
    __asm__ __volatile__("emms");
    FILE* o = fopen(argv[4], "wb");
    if (!o) return 1;
    for (int i = 0; i < w * h; i++) fputc(out[2 * i], o);
    fclose(o);
    return 0;
}
