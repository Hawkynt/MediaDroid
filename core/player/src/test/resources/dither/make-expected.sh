#!/bin/sh
# Makes the expected files: libplacebo's own dithering (7.360, LGPL-2.1-or-later) run by tools/shader-check/placebo.cpp
# on Mesa's OpenGL (llvmpipe, no display needed). expected.ppm: input.ppm (16-bit) dithered to 8 bits by
# pl_shader_dither with its defaults (blue noise, 64 × 64, not temporal), as libplacebo's renderer ends on an 8-bit
# screen; bluenoise.txt: that matrix as pl_generate_blue_noise makes it in a fresh process, as ranks 0 to 4095. Needs
# libplacebo's and Mesa's EGL development files, and glibc (the matrix breaks its ties with rand()).
set -e
here=$(cd "$(dirname "$0")" && pwd)
tools=$here/../../../../../../tools/shader-check
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
g++ -std=c++17 -O2 -I"$tools" -o "$work/placebo_oracle" "$tools/placebo.cpp" -lplacebo -lEGL
python3 -I "$here/make-input.py" "$here/input.ppm"
export __EGL_VENDOR_LIBRARY_FILENAMES=/usr/share/glvnd/egl_vendor.d/50_mesa.json LIBGL_ALWAYS_SOFTWARE=1
"$work/placebo_oracle" dither "$here/input.ppm" "$here/expected.ppm"
"$work/placebo_oracle" bluenoise >"$here/bluenoise.txt"
