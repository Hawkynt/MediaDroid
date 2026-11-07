#!/bin/sh
# Makes the fixtures for the stage's mpv user shaders (GlStageTest): mpv shaders fetched at fixed revisions —
# bloc97's Anime4K_Upscale_CNN_x2_S.glsl (MIT, hooks MAIN), igv's FSRCNNX_x2_8-0-4-1.glsl (LGPL-3.0-or-later, hooks
# LUMA), bjin's ravu-lite-ar-r2.hook and its compute variant (LGPL-3.0-or-later, hook LUMA, embed a lookup table as a
# //!TEXTURE; the compute one runs as a //!COMPUTE pass) and Shiandow's KrigBilateral.glsl (LGPL-3.0-or-later, hooks
# CHROMA) — the pictures written by make-input.py (and core/player's chroma/make-input.py for the 4:2:0 frame), and
# libplacebo 7.360's own renderer running each shader on them, on Mesa's OpenGL (llvmpipe), through
# tools/shader-check/placebo.cpp: <shader>.ppm at twice the picture's size; FSRCNNX and RAVU on the grey picture,
# handed over as YCbCr planes so their LUMA hooks run; KrigBilateral on chroma.yuv, its RGB at the picture's size.
# Needs libplacebo's and Mesa's EGL development files.
set -e
here=$(cd "$(dirname "$0")" && pwd)
tools=$here/../../../../../../tools/shader-check
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
g++ -std=c++17 -O2 -I"$tools" -o "$work/placebo_oracle" "$tools/placebo.cpp" -lplacebo -lEGL
curl -fsSL -o "$here/Anime4K_Upscale_CNN_x2_S.glsl" \
  https://raw.githubusercontent.com/bloc97/Anime4K/7684e9586f8dcc738af08a1cdceb024cc184f426/glsl/Upscale/Anime4K_Upscale_CNN_x2_S.glsl
curl -fsSL -o "$here/FSRCNNX_x2_8-0-4-1.glsl" \
  https://github.com/igv/FSRCNN-TensorFlow/releases/download/1.1/FSRCNNX_x2_8-0-4-1.glsl
prescalers=https://raw.githubusercontent.com/bjin/mpv-prescalers/b3f0a59d68f33b7162051ea5970a5169558f0ea2
curl -fsSL -o "$here/ravu-lite-ar-r2.hook" "$prescalers/ravu-lite-ar-r2.hook"
curl -fsSL -o "$here/ravu-lite-ar-r2-compute.hook" "$prescalers/compute/ravu-lite-ar-r2.hook"
curl -fsSL -o "$here/KrigBilateral.glsl" "$(cat "$tools/krig-url.txt")"
python3 -I "$here/make-input.py" "$here"
python3 -I "$here/../../../../../../core/player/src/test/resources/chroma/make-input.py" "$here/chroma.yuv"
export __EGL_VENDOR_LIBRARY_FILENAMES=/usr/share/glvnd/egl_vendor.d/50_mesa.json LIBGL_ALWAYS_SOFTWARE=1
"$work/placebo_oracle" hook "$here/picture.ppm" "$here/Anime4K_Upscale_CNN_x2_S.glsl" "$here/anime4k.ppm" 128 96
"$work/placebo_oracle" hook "$here/grey.ppm" "$here/FSRCNNX_x2_8-0-4-1.glsl" "$here/fsrcnnx.ppm" 128 96 yuv
"$work/placebo_oracle" hook "$here/grey.ppm" "$here/ravu-lite-ar-r2.hook" "$here/ravu.ppm" 128 96 yuv
"$work/placebo_oracle" hook "$here/grey.ppm" "$here/ravu-lite-ar-r2-compute.hook" "$here/ravu-compute.ppm" 128 96 \
  yuv compute
"$work/placebo_oracle" chroma "$here/chroma.yuv" 64 48 "$here/KrigBilateral.glsl" "$here/krig.ppm" rgb
