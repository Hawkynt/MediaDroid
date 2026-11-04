#!/bin/bash
# oracle.sh <work dir>: the app's upscaling shaders against their originals (README.md). Fetches each original at a
# fixed commit, builds shader_check (the app's shaders, as the stage runs them) and shader_oracle (AMD's and NVIDIA's
# originals), runs every comparison and fails when one does not hold. Anime4K and ACNet run in libplacebo, mpv's
# renderer, through FFmpeg's libplacebo filter.
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
W=$(mkdir -p "$1" && cd "$1" && pwd)
O=$W/originals
B=$W/build
mkdir -p "$O"

fetch() {  # fetch <repository> <commit> <path>
  [ -f "$O/$(basename "$3")" ] || curl -fsSL -o "$O/$(basename "$3")" "https://raw.githubusercontent.com/$1/$2/$3" ||
    { echo "cannot fetch $1/$3"; exit 2; }
}
fetch quic/snapdragon-gsr d926f074bcb9d714e179f1ce0fcb9ee2eeb5074e sgsr/v1/include/glsl/sgsr1_shader_mobile.frag
fetch GPUOpen-Effects/FidelityFX-FSR a21ffb8f6c13233ba336352bdff293894c706575 ffx-fsr/ffx_a.h
fetch GPUOpen-Effects/FidelityFX-FSR a21ffb8f6c13233ba336352bdff293894c706575 ffx-fsr/ffx_fsr1.h
fetch NVIDIAGameWorks/NVIDIAImageScaling 35e13ba316c98eeecf16f37eae70ce88019911f6 NIS/NIS_Config.h
fetch NVIDIAGameWorks/NVIDIAImageScaling 35e13ba316c98eeecf16f37eae70ce88019911f6 NIS/NIS_Scaler.h
fetch bloc97/Anime4K 7684e9586f8dcc738af08a1cdceb024cc184f426 glsl/Upscale/Anime4K_Upscale_CNN_x2_S.glsl
fetch TianZerL/ACNetGLSL c6e5940cef3d2c15bebbe407da6b4e0c68ce50ad glsl/acnet/acnet_f8b4.glsl

cmake -S "$HERE" -B "$B" -DORACLE_DIR="$O" >/dev/null && cmake --build "$B" -j >/dev/null || { echo "build failed"; exit 2; }
C=$B/shader_check
Q=$B/shader_oracle
failed=0
check() {  # check <label> <a> <b> <max> <mean> [border]
  echo -n "$1: "
  "$C" compare "$2" "$3" "$4" "$5" "${6:-0}" || { echo "  FAILED"; failed=1; }
}
differs() {  # differs <label> <a> <b>: an upscaler has to differ from bilinear scaling, or its match proves nothing
  echo -n "$1 (has to differ): "
  if "$C" compare "$2" "$3" 0 0; then echo "  FAILED: no different from bilinear"; failed=1; fi
}
placebo() {  # placebo <in> <out> <libplacebo options> [input filters]: through FFmpeg's libplacebo filter, to RGB
  ffmpeg -loglevel error -y -i "$1" \
    -vf "${4:-format=gbrp},libplacebo=w=iw*2:h=ih*2:dithering=none:sigmoid=false:$3:format=gbrp,format=rgb24" \
    -frames:v 1 "$2"
}

"$C" picture "$W/picture.ppm" 320 240
"$C" picture "$W/grey.ppm" 320 240 gray
"$C" plain "$W/picture.ppm" "$W/plain-2x.ppm" 640 480
"$C" plain "$W/grey.ppm" "$W/grey-plain-2x.ppm" 640 480

# GSR: Qualcomm's shader beside ours on Mesa.
for size in "480 360" "640 480" "1080 810"; do
  "$C" gsr "$W/picture.ppm" "$W/gsr.ppm" $size
  "$C" gsr-qualcomm "$O/sgsr1_shader_mobile.frag" "$W/picture.ppm" "$W/gsr-qualcomm.ppm" $size
  check "GSR ${size/ /×} against Qualcomm's" "$W/gsr.ppm" "$W/gsr-qualcomm.ppm" 1 0.01
done
"$C" gsr "$W/picture.ppm" "$W/gsr.ppm" 640 480
differs "GSR against bilinear" "$W/gsr.ppm" "$W/plain-2x.ppm"

# FSR: AMD's ffx_fsr1.h on full floats; each half alone, then the two. AMD's RCAS reads past the picture's edge, so the
# outermost pixels are left out of the comparisons RCAS takes part in.
for size in "480 360" "640 480" "1080 810"; do
  "$C" fsr-easu "$W/picture.ppm" "$W/easu.ppm" $size
  "$Q" fsr-amd-easu "$O" "$W/picture.ppm" "$W/easu-amd.ppm" $size
  check "FSR EASU ${size/ /×} against AMD's" "$W/easu.ppm" "$W/easu-amd.ppm" 1 0.01
  "$C" fsr "$W/picture.ppm" "$W/fsr.ppm" $size
  "$Q" fsr-amd "$O" "$W/picture.ppm" "$W/fsr-amd.ppm" $size
  # EASU's 1-step roundings in the 8-bit frame between the passes, sharpened by RCAS.
  check "FSR ${size/ /×} against AMD's" "$W/fsr.ppm" "$W/fsr-amd.ppm" 5 0.01 1
done
"$C" fsr-rcas "$W/easu-amd.ppm" "$W/rcas.ppm"
"$Q" fsr-amd-rcas "$O" "$W/easu-amd.ppm" "$W/rcas-amd.ppm" 0 0
check "FSR RCAS on the same picture against AMD's" "$W/rcas.ppm" "$W/rcas-amd.ppm" 0 0 1
"$C" fsr "$W/picture.ppm" "$W/fsr.ppm" 640 480
differs "FSR against bilinear" "$W/fsr.ppm" "$W/plain-2x.ppm"

# NIS: NVIDIA's NIS_Scaler.h; its tile loader wraps an unsigned index at the top and left, so the border is left out
# (as NisTest does).
for size in "400 300" "480 360" "560 420" "640 480"; do
  "$C" nis "$W/picture.ppm" "$W/nis.ppm" $size
  "$Q" nis-nvidia "$O" "$W/picture.ppm" "$W/nis-nvidia.ppm" $size
  check "NIS ${size/ /×} against NVIDIA's" "$W/nis.ppm" "$W/nis-nvidia.ppm" 1 0.02 8
done
differs "NIS against bilinear" "$W/nis.ppm" "$W/plain-2x.ppm"

command -v ffmpeg >/dev/null || { echo "Anime4K and ACNet need FFmpeg with libplacebo"; exit 2; }

# Anime4K: its mpv shader in libplacebo, on RGB, where its MAIN hook sees the same values the stage does.
"$C" anime4k "$W/picture.ppm" "$W/anime4k.ppm"
placebo "$W/picture.ppm" "$W/anime4k-libplacebo.ppm" "custom_shader_path=$O/Anime4K_Upscale_CNN_x2_S.glsl"
check "Anime4K against libplacebo" "$W/anime4k.ppm" "$W/anime4k-libplacebo.ppm" 1 0.1
differs "Anime4K against bilinear" "$W/anime4k.ppm" "$W/plain-2x.ppm"

# ACNet: its mpv shader in libplacebo on YUV (16 bits, BT.709, full range), the chroma scaled bilinearly, as the
# stage's doubling does it — grey first (the network alone), then in colour (the luma put back into the picture).
yuv="scale=out_color_matrix=bt709:out_range=full,format=yuv444p16le"
"$C" acnet "$W/grey.ppm" "$W/acnet-grey.ppm"
placebo "$W/grey.ppm" "$W/acnet-grey-libplacebo.ppm" "custom_shader_path=$O/acnet_f8b4.glsl" "$yuv"
check "ACNet, grey, against libplacebo" "$W/acnet-grey.ppm" "$W/acnet-grey-libplacebo.ppm" 1 0.05
"$C" acnet "$W/picture.ppm" "$W/acnet.ppm"
placebo "$W/picture.ppm" "$W/acnet-libplacebo.ppm" \
  "custom_shader_path=$O/acnet_f8b4.glsl:extra_opts='plane_upscaler=bilinear'" "$yuv"
check "ACNet, colour, against libplacebo" "$W/acnet.ppm" "$W/acnet-libplacebo.ppm" 1 0.05
placebo "$W/picture.ppm" "$W/acnet-libplacebo-ewa.ppm" \
  "custom_shader_path=$O/acnet_f8b4.glsl:extra_opts='plane_upscaler=ewa_lanczossharp'" "$yuv"
differs "ACNet against libplacebo with other chroma scaling" "$W/acnet.ppm" "$W/acnet-libplacebo-ewa.ppm"
differs "ACNet against bilinear" "$W/acnet.ppm" "$W/plain-2x.ppm"

[ $failed = 0 ] && echo "all agree" || echo "SOME DO NOT AGREE"
exit $failed
