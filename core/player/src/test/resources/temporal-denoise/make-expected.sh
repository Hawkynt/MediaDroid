#!/bin/sh
# Makes the fixtures. frame0..4.ppm: make-input.py. <a>-<b>.ppm: frame 2 denoised against frames 1 and 3 by FFmpeg's own
# atadenoise filter (n8.0's vf_atadenoise.c, LGPL-2.1-or-later, its parallel unweighted rows) run by harness.c — over
# three frames, where FFmpeg's own options start at five. The harness has to give, over all five, what FFmpeg n9.0.2's
# command line gives for the middle frame, byte for byte, or nothing is written.
set -e
here=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
curl -fsSL -o "$work/vf_atadenoise.c" https://raw.githubusercontent.com/FFmpeg/FFmpeg/n8.0/libavfilter/vf_atadenoise.c
sed -n '/^#define FILTER_ROW(type, name)/,/^FILTER_ROW(uint8_t, 8)/p' "$work/vf_atadenoise.c" > "$work/filters.h"
cc -O2 -std=c99 -I"$work" -o "$work/harness" "$here/harness.c"
python3 -I "$here/make-input.py" "$here"
for case in "0.02 0.04" "0.08 0.16"; do
  set -- $case
  ffmpeg -loglevel error -y -framerate 25 -i "$here/frame%d.ppm" \
    -vf "format=gbrp,atadenoise=0a=$1:0b=$2:1a=$1:1b=$2:2a=$1:2b=$2:s=5,format=rgb24" -start_number 0 "$work/cli%d.ppm"
  "$work/harness" "$work/five.ppm" "$1" "$2" "$here"/frame0.ppm "$here"/frame1.ppm "$here"/frame2.ppm \
    "$here"/frame3.ppm "$here"/frame4.ppm
  cmp -s "$work/cli2.ppm" "$work/five.ppm" || { echo "the harness is not FFmpeg's atadenoise ($case)"; exit 1; }
  "$work/harness" "$here/$1-$2.ppm" "$1" "$2" "$here"/frame1.ppm "$here"/frame2.ppm "$here"/frame3.ppm
done
