#!/bin/bash
# oracle.sh <build dir> <work dir> [acnet_f8b4.glsl]: the app's upscalers against their originals (README.md).
#   GSR:   glsl/video/sgsr.frag against Qualcomm's sgsr1_shader_mobile.frag, both on Mesa (fetched)
#   ACNet: glsl/acnet against ACNetGLSL's acnet_f8b4.glsl in libplacebo (mpv's renderer) through FFmpeg's libplacebo
#          filter, on a grey picture, so mpv's LUMA plane and the stage's luma are the same value
set -euo pipefail
B=$1
W=$2
ACNET=${3:-}
GSR_COMMIT=d926f074bcb9d714e179f1ce0fcb9ee2eeb5074e
mkdir -p "$W"
check() { echo -n "$1: "; "$B/shader_check" compare "$2" "$3" "$4" "$5"; }

"$B/shader_check" picture "$W/picture.ppm" 320 240
"$B/shader_check" picture "$W/grey.ppm" 320 240 gray

QC=$W/sgsr1_shader_mobile.frag
[ -f "$QC" ] || curl -fsSL -o "$QC" \
  "https://raw.githubusercontent.com/quic/snapdragon-gsr/$GSR_COMMIT/sgsr/v1/include/glsl/sgsr1_shader_mobile.frag"
for size in "480 360" "640 480" "1080 810"; do
  "$B/shader_check" gsr "$W/picture.ppm" "$W/gsr.ppm" $size
  "$B/shader_check" gsr-qualcomm "$QC" "$W/picture.ppm" "$W/gsr-qualcomm.ppm" $size
  check "GSR $size against Qualcomm's" "$W/gsr.ppm" "$W/gsr-qualcomm.ppm" 1 0.01
done
"$B/shader_check" plain "$W/picture.ppm" "$W/plain.ppm" 1080 810
echo -n "GSR against bilinear (has to differ): "
! "$B/shader_check" compare "$W/gsr.ppm" "$W/plain.ppm" 0 0

if [ -n "$ACNET" ]; then
  "$B/shader_check" acnet "$W/grey.ppm" "$W/acnet.ppm"
  placebo="libplacebo=w=iw*2:h=ih*2:custom_shader_path=$ACNET:dithering=none:format=yuv444p"
  ffmpeg -loglevel error -y -i "$W/grey.ppm" \
    -vf "scale=out_range=full,format=yuv444p,$placebo,scale=in_range=full,format=rgb24" \
    -frames:v 1 "$W/acnet-libplacebo.ppm"
  check "ACNet against libplacebo" "$W/acnet.ppm" "$W/acnet-libplacebo.ppm" 2 0.2
  "$B/shader_check" plain "$W/grey.ppm" "$W/grey-plain.ppm" 640 480
  echo -n "ACNet against bilinear (has to differ): "
  ! "$B/shader_check" compare "$W/acnet.ppm" "$W/grey-plain.ppm" 0 0
fi
