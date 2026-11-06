#!/bin/sh
# How the expected pictures beside this script were made, for LibAssTest: libass's own test program (test/test.c of the
# libass 0.17.5 release, as :native:assSources unpacks it) built against the system's libass — Arch Linux's libass
# 0.17.5 with its assembly, FreeType 2.15.0, FriBidi 1.0.16 and HarfBuzz 14.6.0 — drawing styled.ass onto a transparent
# 640 x 360 frame at 0.5 s, 1.5 s and 2.5 s. Its only font is NotoSans-Basic.ttf (Noto Sans Regular, OFL-1.1, cut to
# printable ASCII with fontTools' pyftsubset): Fontconfig is pointed at a configuration of its own that knows no other,
# or it would hand libass the system's bold and italic faces, which the app does not have.
#   sh oracle.sh            (from this folder, after ./gradlew :native:assSources; needs libass and libpng to build on)
set -e
here=$(pwd)
work=$(mktemp -d)
cc -O2 -o "$work/test" "$here/../../../../../../native/build/ass/libass/test/test.c" \
  $(pkg-config --cflags --libs libass libpng)
cp NotoSans-Basic.ttf "$work/"
printf '<?xml version="1.0"?>\n<fontconfig><dir>%s</dir><cachedir>%s/cache</cachedir></fontconfig>\n' "$work" "$work" \
  > "$work/fonts.conf"
export FONTCONFIG_FILE="$work/fonts.conf"
for ms in 500 1500 2500; do
  "$work/test" "styled-${ms}ms.png" styled.ass "$(echo "$ms" | awk '{ print $1 / 1000 }')" 640 360 > /dev/null
done
rm -r "$work"
# styled.mkv (MediaReaderTest): styled.ass as a Matroska subtitle stream over a black 64 x 48 FFV1 video, the font
# attached:
#   ffmpeg -f lavfi -i color=c=black:s=64x48:r=25:d=3 -i styled.ass -attach NotoSans-Basic.ttf \
#     -metadata:s:t mimetype=font/ttf -metadata:s:t filename=NotoSans-Basic.ttf -c:v ffv1 -c:s copy \
#     -metadata:s:s:0 language=jpn styled.mkv
