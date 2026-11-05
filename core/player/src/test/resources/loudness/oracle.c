/*
 * Integrated loudness (EBU R128 / ITU-R BS.1770) of a raw little-endian float32 file through libebur128, printed with
 * full precision: oracle <file> <rate> <channels>. The expected values of core/player's LoudnessMeterTest come from
 * here; see measure.sh beside it.
 */
#include <ebur128.h>
#include <stdio.h>
#include <stdlib.h>

int main(int argc, char** argv) {
  if (argc != 4) {
    fprintf(stderr, "usage: %s <file.f32> <rate> <channels>\n", argv[0]);
    return 2;
  }
  unsigned long rate = strtoul(argv[2], NULL, 10);
  unsigned int channels = (unsigned int) strtoul(argv[3], NULL, 10);
  FILE* in = fopen(argv[1], "rb");
  if (!in) {
    perror(argv[1]);
    return 1;
  }
  ebur128_state* st = ebur128_init(channels, rate, EBUR128_MODE_I);
  float buffer[4096 * 8];
  size_t frames;
  while ((frames = fread(buffer, sizeof(float) * channels, 4096, in)) > 0) {
    ebur128_add_frames_float(st, buffer, frames);
  }
  fclose(in);
  double loudness;
  ebur128_loudness_global(st, &loudness);
  printf("%.6f\n", loudness);
  ebur128_destroy(&st);
  return 0;
}
