#!/bin/sh
# Makes the fixtures. input.ppm: FFmpeg's testsrc2 (96 x 64) coded as MPEG-2 at the coarsest quantiser and decoded —
# the 8 x 8 blocks a starved encoder leaves. <filter>-<alpha>-<beta>-<gamma>-<delta>.ppm: FFmpeg's own deblock filter
# (libavfilter/vf_deblock.c at n8.0, LGPL-2.1-or-later) on it, each RGB channel a plane, run by harness.c — with the
# vertical-edge filters moving on to the next row whether they filtered one or not: FFmpeg's own stop at the first row
# they leave alone (their row pointer moves only after a filtered row), which leaves the rest of the block's edge as
# it is. The harness built from FFmpeg's filters unchanged has to give what FFmpeg's command line gives, byte for byte,
# or nothing is written. FFmpeg n9.0.2 made the committed files.
set -e
here=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
curl -fsSL -o "$work/vf_deblock.c" https://raw.githubusercontent.com/FFmpeg/FFmpeg/n8.0/libavfilter/vf_deblock.c
# The edge filters alone: from WEAK_HFILTER's definition to the last instantiation.
mkdir -p "$work/ffmpeg" "$work/moved"
sed -n '/^#define WEAK_HFILTER/,/^STRONG_VFILTER(16/p' "$work/vf_deblock.c" > "$work/ffmpeg/filters.h"
sed -e 's/for (y = 0; y < block; y++) {/for (y = 0; y < block; y++, dst += dst_linesize) {/' \
  -e '/^ *dst += dst_linesize; *\\$/d' "$work/ffmpeg/filters.h" > "$work/moved/filters.h"
for kind in ffmpeg moved; do
  cc -O2 -std=c99 -I"$work/$kind" -o "$work/harness-$kind" "$here/harness.c"
done
ffmpeg -loglevel error -y -f lavfi -i testsrc2=size=96x64:rate=25 -frames:v 1 -c:v mpeg2video -q:v 31 -qmin 31 \
  "$work/coded.m2v"
ffmpeg -loglevel error -y -i "$work/coded.m2v" -pix_fmt rgb24 "$here/input.ppm"
for case in "weak 0.098 0.05 0.05 0.05" "strong 0.098 0.05 0.05 0.05" "strong 0.196 0.1 0.1 0.1" \
  "weak 0.05 0.025 0.025 0.025"; do
  set -- $case
  ffmpeg -loglevel error -y -i "$here/input.ppm" \
    -vf "format=gbrp,deblock=filter=$1:alpha=$2:beta=$3:gamma=$4:delta=$5,format=rgb24" "$work/cli.ppm"
  "$work/harness-ffmpeg" "$here/input.ppm" "$work/harness.ppm" "$@"
  cmp -s "$work/cli.ppm" "$work/harness.ppm" || { echo "the harness is not FFmpeg's deblock ($case)"; exit 1; }
  "$work/harness-moved" "$here/input.ppm" "$here/$1-$2-$3-$4-$5.ppm" "$@"
done
