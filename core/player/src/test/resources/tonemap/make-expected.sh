#!/bin/sh
# Makes the expected files: libplacebo's own colour mapping (7.360, LGPL-2.1-or-later) — the BT.2390 curve, gamut by
# hard clip, to BT.709 BT.1886 — and its peak detection, run by tools/shader-check/placebo.cpp on Mesa's OpenGL
# (llvmpipe, no display needed). pq-<peak>.ppm: input.ppm read as PQ with HDR10 metadata giving that peak in nits
# (0: none); hlg.ppm: read as HLG; curve.txt: the curve's parameters and pl_tone_map_sample at 1001 points for those
# peaks, and the colour matrices; peak.txt and peak-hlg.txt: the peak and average detected after each frame of a
# short scene. Needs libplacebo's and Mesa's EGL development files.
set -e
here=$(cd "$(dirname "$0")" && pwd)
tools=$here/../../../../../../tools/shader-check
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
g++ -std=c++17 -O2 -I"$tools" -o "$work/placebo_oracle" "$tools/placebo.cpp" -lplacebo -lEGL
python3 -I "$here/make-input.py" "$here"
export __EGL_VENDOR_LIBRARY_FILENAMES=/usr/share/glvnd/egl_vendor.d/50_mesa.json LIBGL_ALWAYS_SOFTWARE=1
for peak in 0 400 1000 4000; do
  "$work/placebo_oracle" tonemap "$here/input.ppm" "$here/pq-$peak.ppm" pq "$peak"
done
"$work/placebo_oracle" tonemap "$here/input.ppm" "$here/hlg.ppm" hlg 0
"$work/placebo_oracle" curve 0 400 1000 4000 >"$here/curve.txt"
cd "$here"
"$work/placebo_oracle" peak pq input.ppm input.ppm dimmer.ppm dimmer.ppm dimmer.ppm darker.ppm dark.ppm input.ppm \
  >peak.txt
"$work/placebo_oracle" peak hlg input.ppm darker.ppm >peak-hlg.txt
