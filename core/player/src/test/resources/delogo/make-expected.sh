#!/bin/sh
# Makes the fixtures: input.yuv, FFmpeg's testsrc2 (96 x 64) with a half-transparent white box (a logo) in its top right
# corner, as yuv444p (its three planes, each a channel the stage filters alike); <x>-<y>-<w>-<h>.yuv FFmpeg's own delogo
# filter (GPL-2.0-or-later, run as an oracle only) on it for that rectangle. FFmpeg n9.0.2 made the committed files.
set -e
here=$(cd "$(dirname "$0")" && pwd)
ffmpeg -loglevel error -y -f lavfi -i testsrc2=size=96x64:rate=25 -frames:v 1 \
  -vf "drawbox=x=70:y=6:w=18:h=10:color=white@0.7:t=fill" -pix_fmt yuv444p -f rawvideo "$here/input.yuv"
for rect in "69 5 20 12" "1 1 30 18" "60 40 35 23" "40 20 3 2"; do
  set -- $rect
  ffmpeg -loglevel error -y -f rawvideo -pix_fmt yuv444p -s 96x64 -i "$here/input.yuv" \
    -vf "delogo=x=$1:y=$2:w=$3:h=$4" -pix_fmt yuv444p -f rawvideo "$here/$1-$2-$3-$4.yuv"
done
