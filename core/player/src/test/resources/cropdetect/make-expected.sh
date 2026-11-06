#!/bin/sh
# Makes the cropdetect fixtures: synthetic 192x108 grey clips (FFmpeg's lavfi sources, 24 frames each), stored as raw
# 8-bit luma, gzipped, and FFmpeg's own cropdetect (GPL-2.0-or-later, used here as an oracle only, never as code) run
# over exactly those bytes, its per-frame line kept as <clip>.<case>.txt. Made with FFmpeg n9.0.2.
#   letterbox  a 2.35:1 picture with bars above and below (bars at video black, 16, with noise)
#   pillarbox  a 4:3 picture with bars left and right
#   windowbox  a 4:3 picture with bars on all four sides
#   dark       a full-frame night scene: dim texture under 24 with one bright light, no bars at all
#   subtitles  the letterbox with a white line of "text" in the bottom bar from frame 8 to 15
set -e
here=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
clip() {
  ffmpeg -v error -y -f lavfi -i "$2,format=gray" -frames:v 24 -f rawvideo "$work/$1.gray"
  gzip -9n -c "$work/$1.gray" > "$here/$1.gray.gz"
}
moving="testsrc2=r=25:d=1"
clip letterbox "$moving:s=192x78,pad=192:108:0:15:0x101010,noise=alls=3:allf=t"
clip pillarbox "$moving:s=144x108,pad=192:108:24:0:0x101010,noise=alls=3:allf=t"
clip windowbox "$moving:s=144x80,pad=192:108:24:14:0x101010,noise=alls=3:allf=t"
clip dark "nullsrc=s=192x108:r=25:d=1,geq=lum='12+8*sin(X/17+N/5)*cos(Y/23)+200*lt(hypot(X-96-N\,Y-48)\,9)'"
clip subtitles "$moving:s=192x80,pad=192:108:0:14:0x101010,drawbox=x=60:y=98:w=72:h=6:color=white:t=fill:enable='between(n,8,15)',noise=alls=3:allf=t"
detect() {
  ffmpeg -v info -f rawvideo -pix_fmt gray -s 192x108 -r 25 -i "$work/$1.gray" -vf "cropdetect$3" -f null - 2>&1 |
    sed -n 's/.*\] \(x1:.*\) pts:.* crop=.*/\1/p' > "$here/$1.$2.txt"
}
for c in letterbox pillarbox windowbox dark subtitles; do detect $c default ""; done
detect subtitles reset "=reset=10"
detect subtitles round2 "=round=2:skip=0"
detect dark outliers "=max_outliers=3:limit=40"
