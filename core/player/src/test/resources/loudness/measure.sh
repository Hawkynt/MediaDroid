#!/bin/sh
# Measures the loudness test signals with two independent oracles and prints the lines of
# expected.txt beside it: libebur128 (MIT, full precision) and FFmpeg's ebur128 filter
# (to 0.1 LU). Needs a C compiler, libebur128 and ffmpeg on the PATH. Run from the repository root.
set -e
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cc -O2 -o "$work/oracle" core/player/src/test/resources/loudness/oracle.c -lebur128
./gradlew -q --no-daemon :core:player:loudnessSignals -Pfolder="$work/signals"
for file in "$work"/signals/*.f32; do
  base=$(basename "$file" .f32)
  channels=${base##*.}
  rest=${base%.*}
  rate=${rest##*.}
  name=${rest%.*}
  libebur128=$("$work/oracle" "$file" "$rate" "$channels")
  ffmpeg=$(ffmpeg -hide_banner -nostats -f f32le -ar "$rate" -ac "$channels" -i "$file" -af ebur128 -f null - 2>&1 |
    sed -n '/Integrated loudness:/,/LRA/p' | sed -n 's/^ *I: *\(-*[0-9.inf]*\) LUFS/\1/p')
  echo "$name $libebur128 $ffmpeg"
done
