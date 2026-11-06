#!/bin/bash
# make-greedy.sh: the Greedy deinterlacers' expected output for input.gray (top field first) and input-bff.gray (bottom
# field first), written by make-input.py.
#   greedyh.gray, greedyh-bff.gray: GStreamer's own deinterlace element, method greedyh (gst-plugins-good 1.28.8,
#     tvtime's Greedy High Motion relicensed LGPL; its C path for planar formats), every field, fed as Y444 with flat
#     chroma; the luma of each output picture in turn — picture i keeps field i.
#   greedy2frame.gray, greedy2frame-bff.gray: xine-lib's port of DScaler's Greedy 2-Frame (GPL-2.0, run as an oracle
#     only) through greedy2frame-harness.c, for frames 1 to 3, each with its second field rebuilt and then its first —
#     six pictures.
# Needs gst-launch-1.0, curl and an x86-64 C compiler. Run from this folder.
set -euo pipefail
W=48
H=64
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

for order in tff bff; do
  input=$([ $order = tff ] && echo input.gray || echo input-bff.gray)
  suffix=$([ $order = tff ] && echo "" || echo "-bff")
  python3 -I -c "
import sys
d = open(sys.argv[1], 'rb').read(); n = $W * $H
open(sys.argv[2], 'wb').write(b''.join(d[k*n:(k+1)*n] + bytes([128]) * 2 * n for k in range(len(d) // n)))
" "$input" "$work/in.yuv"
  gst-launch-1.0 -q filesrc location="$work/in.yuv" ! \
    rawvideoparse width=$W height=$H format=y444 framerate=25/1 interlaced=true \
    top-field-first=$([ $order = tff ] && echo true || echo false) ! \
    deinterlace method=greedyh fields=all mode=interlaced tff=$order ! filesink location="$work/out.yuv"
  python3 -I -c "
import sys
d = open(sys.argv[1], 'rb').read(); n = $W * $H
open(sys.argv[2], 'wb').write(b''.join(d[k*3*n:k*3*n+n] for k in range(len(d) // (3 * n))))
" "$work/out.yuv" "greedyh$suffix.gray"
done

curl -fsSL -o "$work/greedy2frame_template.c" \
  "https://raw.githubusercontent.com/AOSC-Tracking/xine-lib/5388979efeba5a7f1fc955f17c19247c55b5250e/src/post/deinterlace/plugins/greedy2frame_template.c"
cc -O1 -I"$work" -o "$work/harness" greedy2frame-harness.c
for order in tff bff; do
  input=$([ $order = tff ] && echo input.gray || echo input-bff.gray)
  suffix=$([ $order = tff ] && echo "" || echo "-bff")
  : > "greedy2frame$suffix.gray"
  for k in 1 2 3; do
    # The second field rebuilt: the newest field is the frame's own second one (xine's second_field), between the
    # frame before and the frame itself; then the first, the newest field the next frame's first.
    second=$([ $order = tff ] && echo 1 || echo 0)
    "$work/harness" "$input" $W $H "$work/x.gray" $k $((k - 1)) $((k - 1)) $second 1
    cat "$work/x.gray" >> "greedy2frame$suffix.gray"
    "$work/harness" "$input" $W $H "$work/x.gray" $((k + 1)) $k $((k - 1)) $((1 - second)) 0
    cat "$work/x.gray" >> "greedy2frame$suffix.gray"
  done
done
