#!/bin/bash
# Checks MediaDroid's FFmpeg demuxer and codec-parameter handover on the host, against ffprobe:
#
#   tools/ffmpeg/test/check.sh <host-ffmpeg-6.0-prefix> <out-dir>
#
# The prefix is a desktop build of the same FFmpeg 6.0 (programs and static libraries). Writes short
# sample files of every container the app demuxes with FFmpeg, then runs FfmpegDemuxer.java over JNI
# on them (DemuxCheck), decodes every stream from the codec-parameter blob alone (codecpar_check), and
# records ffprobe's packet counts as the oracle; each thumbnail must be one of the frames of ffmpeg's own
# decode at the same size, byte for byte. expected.txt beside this script is the reviewed result; the
# output must match it.
set -euo pipefail
P="$(realpath "$1")"; OUT="$(realpath -m "$2")"
HERE="$(cd "$(dirname "$0")" && pwd)"; SRC="$HERE/../mediadroid"
mkdir -p "$OUT/samples" "$OUT/classes" "$OUT/frames"
F="$P/bin/ffmpeg -v error -y -f lavfi -i testsrc=size=320x240:rate=25:duration=2"
A="-f lavfi -i sine=frequency=440:duration=2"
S="$OUT/samples"
$F $A -c:v wmv2 -c:a wmav2 -b:a 64k "$S/sample.wmv"
$F $A -c:v msmpeg4 -c:a wmav1 -b:a 64k "$S/sample.asf"
$F $A -c:v rv20 -c:a ac3 -ar 48000 "$S/sample.rm"
$F $A -c:v rv10 -c:a real_144 -ar 8000 -ac 1 "$S/sample-144.rm"
$P/bin/ffmpeg -v error -y -f lavfi -i testsrc=size=720x576:rate=25:duration=2 $A \
  -pix_fmt yuv420p -c:v dvvideo -c:a pcm_s16le -ar 48000 -ac 2 "$S/sample.dv"
$F $A -c:v mpeg2video -c:a pcm_s16le -ar 48000 "$S/sample.mxf"
$F $A -c:v mpeg4 -c:a mp2 -ar 48000 "$S/sample.nut"
$F $A -c:v mpeg2video -c:a mp2 -ar 48000 "$S/sample.wtv"
$F -c:v mpeg2video -f mpeg2video "$S/sample.m2v"
$F -pix_fmt yuv420p "$S/sample.y4m"

LIBS="$P/lib/libavformat.a $P/lib/libavcodec.a $P/lib/libswscale.a $P/lib/libswresample.a $P/lib/libavutil.a
  -lz -lm -lpthread"
g++ -O1 -shared -fPIC -I"$SRC/jni" -I"$P/include" -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
  "$SRC/jni/mediadroid_demux.cc" -Wl,-Bsymbolic $LIBS -o "$OUT/libffmpegJNI.so"
g++ -O1 -I"$SRC/jni" -I"$P/include" "$HERE/codecpar_check.cc" $LIBS -o "$OUT/codecpar_check"
{ find "$HERE/stubs" -name '*.java'; echo "$SRC/java/com/hawkynt/mediadroid/ffmpeg/FfmpegDemuxer.java"
  echo "$HERE/DemuxCheck.java"; } | sed 's/.*/"&"/' > "$OUT/sources.txt"
"$JAVA_HOME/bin/javac" -d "$OUT/classes" @"$OUT/sources.txt"

FILES=$(ls "$S"/* | sort)
"$JAVA_HOME/bin/java" -Dffmpeg.lib="$OUT/libffmpegJNI.so" -Dframes.dir="$OUT/frames" -cp "$OUT/classes" \
  DemuxCheck $FILES > "$OUT/demux.txt"
{
  echo "== DemuxCheck (FfmpegDemuxer over JNI)"
  cat "$OUT/demux.txt"
  echo "== ffprobe packet counts (oracle)"
  for f in $FILES; do
    echo "$(basename "$f") $("$P/bin/ffprobe" -v error -count_packets \
      -show_entries stream=index,codec_name,nb_read_packets -of csv=p=0 "$f" | tr '\n' ' ')"
  done
  echo "== thumbnails: which frame of ffmpeg's own decode at the same size (oracle) each one is, byte for byte"
  for f in $FILES; do
    name="$(basename "$f")"
    line="$(grep -A40 "^$name " "$OUT/demux.txt" | grep -m1 '  frame ' || true)"
    case "$line" in
      *" at "*)
        dims="${line#*frame }"; dims="${dims%% *}"
        rm -rf "$OUT/frames/$name.d"; mkdir -p "$OUT/frames/$name.d"
        "$P/bin/ffmpeg" -v error -y -i "$f" -vf "scale=${dims/x/:}:flags=bilinear" -pix_fmt bgra \
          -f rawvideo "$OUT/frames/$name.all"
        bytes=$(( ${dims%x*} * ${dims#*x} * 4 ))
        split -a 4 -d -b "$bytes" "$OUT/frames/$name.all" "$OUT/frames/$name.d/"
        found=none
        for part in "$OUT/frames/$name.d"/*; do
          if cmp -s "$part" "$OUT/frames/$name.bgra"; then found="$((10#$(basename "$part")))"; break; fi
        done
        echo "$name $dims is decoded frame $found" ;;
      *) echo "$name no frame" ;;
    esac
  done
  echo "== codecpar_check (decoding from the blob alone)"
  "$OUT/codecpar_check" $FILES
} > "$OUT/report.txt"
cat "$OUT/report.txt"
diff -u "$HERE/expected.txt" "$OUT/report.txt" && echo "MATCHES expected.txt"
