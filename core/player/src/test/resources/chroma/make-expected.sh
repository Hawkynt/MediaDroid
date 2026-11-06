#!/bin/sh
# Makes the expected files: libplacebo's own renderer (7.360, LGPL-2.1-or-later) on input.yuv, run by
# tools/shader-check/placebo.cpp on Mesa's OpenGL (llvmpipe, no display needed), the chroma enlarged by its
# spline36 filter and by KrigBilateral (Shiandow's mpv user shader, LGPL-3.0-or-later, at a fixed revision):
# <filter>.ppm, Y, Cb and Cr at the luma's size, 16 bits each. Needs libplacebo's and Mesa's EGL development files.
set -e
here=$(cd "$(dirname "$0")" && pwd)
tools=$here/../../../../../../tools/shader-check
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
g++ -std=c++17 -O2 -I"$tools" -o "$work/placebo_oracle" "$tools/placebo.cpp" -lplacebo -lEGL
curl -fsSL -o "$work/KrigBilateral.glsl" "$(cat "$tools/krig-url.txt")"
python3 -I "$here/make-input.py" "$here/input.yuv"
export __EGL_VENDOR_LIBRARY_FILENAMES=/usr/share/glvnd/egl_vendor.d/50_mesa.json LIBGL_ALWAYS_SOFTWARE=1
for filter in spline36; do
  "$work/placebo_oracle" chroma "$here/input.yuv" 64 48 $filter "$here/$filter.ppm"
done
"$work/placebo_oracle" chroma "$here/input.yuv" 64 48 "$work/KrigBilateral.glsl" "$here/krig.ppm"
