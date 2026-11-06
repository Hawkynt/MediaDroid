#!/bin/bash
# make-tomsmocomp.sh: TomsMoComp's expected output for input.gray (top field first) and input-bff.gray (bottom field
# first), written by make-input.py: tomsmocomp.gray and tomsmocomp-bff.gray, frames 1 to 3, each with its second field
# rebuilt and then its first — six pictures — by GStreamer 1.28.8's TomsMoComp through tomsmocomp-harness.c.
# Needs curl and an x86-64 C compiler. Run from this folder.
set -euo pipefail
W=48
H=64
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
base=https://raw.githubusercontent.com/GStreamer/gstreamer/1.28.8/subprojects/gst-plugins-good/gst/deinterlace/tvtime
mkdir -p "$work/tomsmocomp"
curl -fsSL -o "$work/x86-64_macros.inc" "$base/x86-64_macros.inc"
for f in tomsmocompmacros.h TomsMoCompAll2.inc SearchLoopTop.inc SearchLoopBottom.inc WierdBob.inc StrangeBob.inc \
  SearchLoop0A.inc SearchLoopOddA2.inc SearchLoopOddAH2.inc SearchLoopOddA.inc SearchLoopOddA6.inc SearchLoopOddAH.inc \
  SearchLoopEdgeA.inc SearchLoopEdgeA8.inc SearchLoopVA.inc SearchLoopVAH.inc; do
  curl -fsSL -o "$work/tomsmocomp/$f" "$base/tomsmocomp/$f"
done
cc -O1 -I"$work" -o "$work/harness" tomsmocomp-harness.c
for order in tff bff; do
  input=$([ $order = tff ] && echo input.gray || echo input-bff.gray)
  suffix=$([ $order = tff ] && echo "" || echo "-bff")
  # The rows rebuilt with the second field kept back: odd ones top field first.
  later=$([ $order = tff ] && echo 1 || echo 0)
  : > "tomsmocomp$suffix.gray"
  for k in 1 2 3; do
    # The second field rebuilt: between the frame before's and the frame's own; then the first, between the frame's
    # own and the next one's.
    "$work/harness" "$input" $W $H "$work/x.gray" $k $((k - 1)) $k $((k - 1)) $later
    cat "$work/x.gray" >> "tomsmocomp$suffix.gray"
    "$work/harness" "$input" $W $H "$work/x.gray" $((k + 1)) $k $k $((k - 1)) $((1 - later))
    cat "$work/x.gray" >> "tomsmocomp$suffix.gray"
  done
done
