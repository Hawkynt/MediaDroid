#!/bin/sh
# Makes the fixtures. input.ppm: FFmpeg's testsrc2 (96 x 64) coded as MPEG-2 at a coarse quantiser and decoded — the
# ringing a starved encoder leaves around edges and text. <primary>-<secondary>-<damping>.ppm: dav1d 1.5.0's own CDEF
# (BSD-2-Clause) on it, run by harness.c, which this builds against dav1d's files with the few of dav1d's common
# definitions they need written out below. FFmpeg n9.0.2 and dav1d 1.5.0 made the committed files.
set -e
here=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
base=https://code.videolan.org/videolan/dav1d/-/raw/1.5.0
mkdir -p "$work/src" "$work/common"
curl -fsSL -o "$work/cdef_tmpl.c" "$base/src/cdef_tmpl.c"
curl -fsSL -o "$work/src/cdef.h" "$base/src/cdef.h"
curl -fsSL -o "$work/tables.c" "$base/src/tables.c"
# dav1d's direction table, as tables.c has it.
{ echo '#include <stdint.h>'; sed -n '/^const int8_t dav1d_cdef_directions/,/^};/p' "$work/tables.c"; } \
  > "$work/directions.h"
cat > "$work/config.h" <<'EOF'
#define HAVE_ASM 0
#define NOINLINE __attribute__((noinline))
#define COLD
EOF
cat > "$work/common/bitdepth.h" <<'EOF'
#include <assert.h>
#include <stdint.h>
typedef uint8_t pixel;
#define BITDEPTH 8
#define PXSTRIDE(x) (x)
#define HIGHBD_DECL_SUFFIX
#define HIGHBD_TAIL_SUFFIX
#define HIGHBD_CALL_SUFFIX
#define bitdepth_from_max(x) 8
#define bitdepth_max 255
#define bitfn(x) x##_8bpc
#define bitfn_decls(name, ...) name##_8bpc(__VA_ARGS__)
EOF
cat > "$work/common/intops.h" <<'EOF'
#include <stdlib.h>
static inline int imin(const int a, const int b) { return a < b ? a : b; }
static inline int imax(const int a, const int b) { return a > b ? a : b; }
static inline unsigned umin(const unsigned a, const unsigned b) { return a < b ? a : b; }
static inline int iclip(const int v, const int min, const int max) { return v < min ? min : v > max ? max : v; }
static inline int apply_sign(const int v, const int s) { return s < 0 ? -v : v; }
static inline int ulog2(const unsigned v) { return 31 - __builtin_clz(v); }
EOF
cat > "$work/src/tables.h" <<'EOF'
#include <stdint.h>
extern const int8_t dav1d_cdef_directions[12][2];
EOF
cc -O2 -std=gnu99 -I"$work" -o "$work/harness" "$here/harness.c"
ffmpeg -loglevel error -y -f lavfi -i testsrc2=size=96x64:rate=25 -frames:v 1 -c:v mpeg2video -q:v 20 "$work/coded.m2v"
ffmpeg -loglevel error -y -i "$work/coded.m2v" -pix_fmt rgb24 "$here/input.ppm"
for case in "8 2 5" "15 4 6" "4 0 3" "0 2 4" "3 1 4"; do
  set -- $case
  "$work/harness" "$here/input.ppm" "$here/$1-$2-$3.ppm" "$@"
done
