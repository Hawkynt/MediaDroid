#!/bin/sh
# Makes the expected files: libplacebo's own pl_shader_deband (7.360, LGPL-2.1-or-later) on input.ppm, run by
# tools/shader-check/placebo.cpp on Mesa's OpenGL (llvmpipe, no display needed), one file per case:
# <iterations>-<threshold>-<radius>-<grain>-<frame>.ppm. Needs libplacebo's and Mesa's EGL development files.
set -e
here=$(cd "$(dirname "$0")" && pwd)
tools=$here/../../../../../../tools/shader-check
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
g++ -std=c++17 -O2 -I"$tools" -o "$work/placebo_oracle" "$tools/placebo.cpp" -lplacebo -lEGL
python3 -I "$here/make-input.py" "$here/input.ppm"
export __EGL_VENDOR_LIBRARY_FILENAMES=/usr/share/glvnd/egl_vendor.d/50_mesa.json LIBGL_ALWAYS_SOFTWARE=1
for case in "1 3 16 0 0" "1 3 16 4 0" "1 3 16 4 5" "2 4 16 6 1" "4 6 16 8 0" "3 5 24 0 255"; do
  # shellcheck disable=SC2086
  "$work/placebo_oracle" deband "$here/input.ppm" "$here/$(echo $case | tr ' ' -).ppm" $case
done
