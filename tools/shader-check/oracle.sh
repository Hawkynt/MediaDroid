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

cmake -S "$HERE" -B "$B" -DORACLE_DIR="$O" >/dev/null && cmake --build "$B" -j >/dev/null ||
  { echo "build failed"; exit 2; }
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
# The GLSL ES 3.00 variants (floats' bits read directly, as on ES 3 chips) have to agree just the same.
for size in "480 360" "1080 810"; do
  SHADER_CHECK_FSR=es3 "$C" fsr-easu "$W/picture.ppm" "$W/easu-es3.ppm" $size
  "$Q" fsr-amd-easu "$O" "$W/picture.ppm" "$W/easu-amd.ppm" $size
  check "FSR EASU (ES 3.00) ${size/ /×} against AMD's" "$W/easu-es3.ppm" "$W/easu-amd.ppm" 1 0.01
  SHADER_CHECK_FSR=es3 "$C" fsr "$W/picture.ppm" "$W/fsr-es3.ppm" $size
  "$Q" fsr-amd "$O" "$W/picture.ppm" "$W/fsr-amd.ppm" $size
  check "FSR (ES 3.00) ${size/ /×} against AMD's" "$W/fsr-es3.ppm" "$W/fsr-amd.ppm" 5 0.01 1
done
SHADER_CHECK_FSR=es3 "$C" fsr-rcas "$W/easu-amd.ppm" "$W/rcas-es3.ppm"
"$Q" fsr-amd-rcas "$O" "$W/easu-amd.ppm" "$W/rcas-amd.ppm" 0 0
check "FSR RCAS (ES 3.00) on the same picture against AMD's" "$W/rcas-es3.ppm" "$W/rcas-amd.ppm" 0 0 1
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

# xBRZ: against the original's own output (core/imaging's references, which Xbrz is held to too), every factor; the
# patterns read from the app's Xbrz.kt.
python3 -I "$HERE/xbrz-table.py" "$HERE/../../core/imaging/src/main/kotlin/com/hawkynt/mediadroid/imaging/Xbrz.kt" \
  "$W/xbrz-table.bin"
command -v ffmpeg >/dev/null || { echo "the rest needs FFmpeg with libplacebo"; exit 2; }
for name in shapes noise gradient; do
  ref="$HERE/../../core/imaging/src/test/resources/xbrz"
  ffmpeg -loglevel error -y -i "$ref/$name.png" -pix_fmt rgb24 "$W/xbrz-$name.ppm"
  for factor in 2 3 4 5 6; do
    ffmpeg -loglevel error -y -i "$ref/$name-${factor}x.png" -pix_fmt rgb24 "$W/xbrz-ref.ppm"
    "$C" xbrz "$W/xbrz-$name.ppm" "$W/xbrz.ppm" $factor "$W/xbrz-table.bin"
    check "xBRZ $name ${factor}× against the original" "$W/xbrz.ppm" "$W/xbrz-ref.ppm" 0 0
  done
done

# YADIF and BWDIF: against FFmpeg's own filters on planar RGB, both field orders and both fields, PAL and NTSC sizes;
# then once more with every read addressed 1/256 texel off, as a GPU's division may land — nearest sampling has to
# keep it byte for byte.
mkdir -p "$W/nudged/video"
sed 's|vec2(x + 0.5, uSize.y - y - 0.5) / uSize|vec2(x + 0.5 - 0.004, uSize.y - y - 0.5 + 0.004) / uSize|' \
  "$HERE/../../stage/src/main/glsl/video/temporal.frag" > "$W/nudged/video/temporal.frag"
cp "$HERE/../../stage/src/main/glsl/video/vertex.vert" "$W/nudged/video/"
for size in "720 576" "720 480"; do
  "$C" picture "$W/interlaced-source.ppm" $size
  python3 -I "$HERE/fields.py" "$W/interlaced-source.ppm" "$W/field"
  for method in yadif bwdif; do
    for tff in 1 0; do
      parity=$([ $tff = 1 ] && echo tff || echo bff)
      ffmpeg -loglevel error -y -framerate 25 -i "$W/field%d.ppm" -start_number 0 \
        -vf "format=gbrp,$method=mode=send_field:parity=$parity:deint=all,format=rgb24" "$W/ffmpeg-%d.ppm"
      for second in 0 1; do
        "$C" temporal "$W/field0.ppm" "$W/field1.ppm" "$W/field2.ppm" "$W/temporal.ppm" $method $tff $second
        check "${method^^} ${size/ /×} $parity field $((second + 1)) against FFmpeg" "$W/temporal.ppm" \
          "$W/ffmpeg-$((2 + second)).ppm" 0 0
      done
    done
    SHADER_CHECK_GLSL="$W/nudged" "$C" temporal "$W/field0.ppm" "$W/field1.ppm" "$W/field2.ppm" "$W/temporal.ppm" \
      $method 0 0
    check "${method^^} ${size/ /×} read 1/256 texel off, against FFmpeg" "$W/temporal.ppm" "$W/ffmpeg-2.ppm" 0 0
  done
  differs "YADIF against the woven frame" "$W/temporal.ppm" "$W/field1.ppm"
done

# YUV to RGB (FFmpeg's decoded frames): against FFmpeg's own conversion, BT.601 and BT.709, limited range; grey is
# exact (no chroma to scale), colour within FFmpeg's bilinear chroma.
for matrix in 601 709; do
  for name in grey picture; do
    ffmpeg -loglevel error -y -i "$W/$name.ppm" -f rawvideo \
      -vf "scale=out_color_matrix=bt$matrix:out_range=tv:flags=accurate_rnd+full_chroma_int,format=yuv420p" \
      "$W/$name-$matrix.yuv"
    ffmpeg -loglevel error -y -f rawvideo -pix_fmt yuv420p -s 320x240 -i "$W/$name-$matrix.yuv" \
      -vf "scale=in_color_matrix=bt$matrix:in_range=tv:flags=accurate_rnd+full_chroma_int+bilinear,format=rgb24" \
      "$W/$name-$matrix-ffmpeg.ppm"
    "$C" yuv "$W/$name-$matrix.yuv" 320 240 $matrix "$W/$name-$matrix-ours.ppm"
    check "YUV BT.$matrix, $name, against FFmpeg" "$W/$name-$matrix-ours.ppm" "$W/$name-$matrix-ffmpeg.ppm" 1 0.05
  done
done

# Full range (MJPEG cameras' JPEG range): the FFmpeg decoder squeezes it into limited range (mediadroid_range.h)
# before the stage reads it as limited; against FFmpeg's own conversion of the full-range frame, within the step the
# squeeze rounds to (256 levels onto 220 and 225). Read as limited without the squeeze, as before, it has to fail.
for matrix in 601 709; do
  for name in grey picture; do
    limits=$([ $name = grey ] && echo "1 0.2" || echo "2 0.4")
    ffmpeg -loglevel error -y -i "$W/$name.ppm" -f rawvideo \
      -vf "scale=out_color_matrix=bt$matrix:out_range=pc:flags=accurate_rnd+full_chroma_int,format=yuv420p" \
      "$W/$name-$matrix-full.yuv"
    ffmpeg -loglevel error -y -f rawvideo -pix_fmt yuv420p -s 320x240 -i "$W/$name-$matrix-full.yuv" \
      -vf "scale=in_color_matrix=bt$matrix:in_range=pc:flags=accurate_rnd+full_chroma_int+bilinear,format=rgb24" \
      "$W/$name-$matrix-full-ffmpeg.ppm"
    "$C" yuv "$W/$name-$matrix-full.yuv" 320 240 $matrix "$W/$name-$matrix-full-ours.ppm" full
    check "YUV BT.$matrix full range, $name, against FFmpeg" \
      "$W/$name-$matrix-full-ours.ppm" "$W/$name-$matrix-full-ffmpeg.ppm" $limits
    "$C" yuv "$W/$name-$matrix-full.yuv" 320 240 $matrix "$W/$name-$matrix-full-unsqueezed.ppm"
    echo -n "YUV BT.$matrix full range, $name, read as limited (has to fail): "
    if "$C" compare "$W/$name-$matrix-full-unsqueezed.ppm" "$W/$name-$matrix-full-ffmpeg.ppm" $limits; then
      echo "  FAILED: the squeeze proves nothing"; failed=1
    fi
  done
done

# Bob and Auto: the first field stays for either field order — a frame whose top field is 200 and bottom field 50
# has to come out all 200 top field first and all 50 bottom field first.
python3 -I - "$W" <<'PY'
import sys
w, h = 64, 48
open(f'{sys.argv[1]}/fields.ppm', 'wb').write(b'P6\n%d %d\n255\n' % (w, h) +
                                              b''.join(bytes([200 if y % 2 == 0 else 50] * w * 3) for y in range(h)))
for v in (200, 50):
    open(f'{sys.argv[1]}/flat{v}.ppm', 'wb').write(b'P6\n%d %d\n255\n' % (w, h) + bytes([v] * w * h * 3))
PY
for tff in 1 0; do
  first=$([ $tff = 1 ] && echo 200 || echo 50)
  "$C" deinterlace "$W/fields.ppm" "$W/bob.ppm" 3 $tff
  order=$([ $tff = 1 ] && echo top || echo bottom)
  check "Bob keeps the first field ($order first)" "$W/bob.ppm" "$W/flat$first.ppm" 0 0 1
done

# Debanding: libplacebo's own pl_shader_deband, run by libplacebo on the same Mesa (placebo.cpp), on a picture with
# 8-bit steps (core/player's fixture) and the test picture, the frame held as the stages hold it (top row at v = 1)
# and turned round (at v = 0). Without grain an average of four landing on a half step may round the other way in the
# shader compiler's float arithmetic (README.md). The GLSL ES 1.00 variant draws other random numbers
# (no unsigned integers for pcg3d): without grain it can only move a value within the threshold, as libplacebo's.
python3 -I "$HERE/../../core/player/src/test/resources/deband/make-input.py" "$W/band.ppm"
for name in band picture; do
  for case in "1 3 16 0 0" "1 3 16 4 3" "4 6 16 8 7" "3 5 24 2 255"; do
    # shellcheck disable=SC2086
    "$B/placebo_oracle" deband "$W/$name.ppm" "$W/deband-libplacebo.ppm" $case
    for rows in bottom top; do
      # shellcheck disable=SC2086
      SHADER_CHECK_DEBAND=$rows "$C" deband "$W/$name.ppm" "$W/deband.ppm" $case
      check "Deband $name ($case), top row at v = $([ $rows = top ] && echo 0 || echo 1), against libplacebo" \
        "$W/deband.ppm" "$W/deband-libplacebo.ppm" 1 0.01
    done
  done
  "$B/placebo_oracle" deband "$W/$name.ppm" "$W/deband-libplacebo.ppm" 1 3 16 0 0
  SHADER_CHECK_DEBAND=es1 "$C" deband "$W/$name.ppm" "$W/deband-es1.ppm" 1 3 16 0 0
  check "Deband $name (GLSL ES 1.00, other random numbers) against libplacebo" "$W/deband-es1.ppm" \
    "$W/deband-libplacebo.ppm" 2 0.2
  "$C" deband "$W/$name.ppm" "$W/deband.ppm" 4 6 16 0 0
  differs "Deband $name against the picture it was given" "$W/deband.ppm" "$W/$name.ppm"
done

# HDR tone mapping: libplacebo's own colour mapping (BT.2390, gamut by hard clip, to BT.709 BT.1886), run by libplacebo
# on the same Mesa (placebo.cpp), on core/player's HDR picture read as PQ at several peaks and as HLG; ours from the
# stage's half-float HDR frame. libplacebo reads its curve from a 256-entry table, ours computes it (README.md). Each
# peak has to give another picture, or the peak would not reach the pass.
mkdir -p "$W/hdr"
python3 -I "$HERE/../../core/player/src/test/resources/tonemap/make-input.py" "$W/hdr"
for case in "pq 0" "pq 400" "pq 1000" "pq 4000" "hlg 0"; do
  # shellcheck disable=SC2086
  "$B/placebo_oracle" tonemap "$W/hdr/input.ppm" "$W/tonemap-libplacebo.ppm" $case
  # shellcheck disable=SC2086
  "$C" tonemap "$W/hdr/input.ppm" "$W/tonemap.ppm" $case
  check "Tone mapping ($case) against libplacebo" "$W/tonemap.ppm" "$W/tonemap-libplacebo.ppm" 1 0.05
done
"$C" tonemap "$W/hdr/input.ppm" "$W/tonemap-4000.ppm" pq 4000
differs "Tone mapping at 1000 nits against 4000" "$W/tonemap.ppm" "$W/tonemap-4000.ppm"

# HDR peak detection: libplacebo's own, each picture a scene's first frame (where its smoothing takes the measurement as
# it is), against peak.frag's blocks as PeakDetect takes them — both in PQ, within six 14-bit steps: the stage's HDR
# frame holds half floats, which put a coded value up to four steps off near the top (libplacebo reads 16 bits), and a
# pixel's luminance is truncated to its step.
for transfer in pq hlg; do
  for name in input dimmer darker dark; do
    expected=$("$B/placebo_oracle" peak $transfer "$W/hdr/$name.ppm" 2>/dev/null)
    ours=$("$C" peak $transfer "$W/hdr/$name.ppm")
    echo -n "Peak detection ($transfer, $name) against libplacebo: $ours / $expected: "
    if awk -v a="$ours" -v b="$expected" 'BEGIN { split(a, x, " "); split(b, y, " "); d = 6 / 16383;
         exit !((x[1] - y[1]) ^ 2 <= d * d && (x[2] - y[2]) ^ 2 <= d * d) }'; then
      echo ok
    else
      echo FAILED
      failed=1
    fi
  done
done

# Lanczos and EWA: their kernels against libplacebo's lanczos and ewa_lanczossharp, antiringing off on both sides
# (ours pulls into the four nearest pixels' range, libplacebo's weighs a soft minimum and maximum: not the same rule).
for size in "480 360" "640 480"; do
  for kernel in lanczos ewa; do
    "$C" $kernel "$W/picture.ppm" "$W/$kernel.ppm" $size 0
    scaler=$([ $kernel = ewa ] && echo ewa_lanczossharp || echo lanczos)
    scale="libplacebo=w=${size% *}:h=${size#* }:upscaler=$scaler:antiringing=0:dithering=none:sigmoid=false"
    ffmpeg -loglevel error -y -i "$W/picture.ppm" -vf "format=gbrp,$scale:format=gbrp,format=rgb24" \
      -frames:v 1 "$W/$kernel-libplacebo.ppm"
    label=$([ $kernel = ewa ] && echo EWA || echo Lanczos)
    check "$label kernel ${size/ /×} against libplacebo" "$W/$kernel.ppm" "$W/$kernel-libplacebo.ppm" 2 0.1 4
  done
done
differs "Lanczos against bilinear" "$W/lanczos.ppm" "$W/plain-2x.ppm"

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
