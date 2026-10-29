# Writes input.gray: 5 frames of 48 x 64 8-bit grey, each woven top field first from two moments 1/50 s
# apart, with a bar sweeping 3 pixels per field, a diagonal edge and a textured disc drifting, so the
# fields comb; input-bff.gray the same bottom field first, input-progressive.gray both fields from one
# moment. Fixed arithmetic only, the same files on every run. The expected files are FFmpeg 6.0's
# (configured --disable-x86asm --disable-inline-asm) output for them:
#   for m in yadif bwdif; do for mode in 0 1; do
#     ffmpeg -f rawvideo -pix_fmt gray -s 48x64 -r 25 -i input.gray \
#       -vf "$m=mode=$mode:parity=tff:deint=all,format=gray" -f rawvideo $m-$mode.gray
#   done; done
#   for m in yadif bwdif; do for mode in 0 1; do
#     ffmpeg -f rawvideo -pix_fmt gray -s 48x64 -r 25 -i input-bff.gray \
#       -vf "$m=mode=$mode:parity=bff:deint=all,format=gray" -f rawvideo $m-$mode-bff.gray
#   done; done
#   for i in input input-bff input-progressive; do
#     ffmpeg -v debug -f rawvideo -pix_fmt gray -s 48x64 -r 25 -i $i.gray -vf idet -f null - 2>&1 |
#       grep -o "Single frame:.*" > $i.idet.txt
#   done
W, H, FRAMES = 48, 64, 5

def scene(t):
    rows = []
    for y in range(H):
        row = []
        for x in range(W):
            v = 40 + (x * 7 + y * 3) % 23
            if (x - 2 * y + 4 * t) % 40 < 14:
                v += 70
            if abs(x - (5 + 3 * t) % W) < 4:
                v = 235
            dx, dy = x - 30 + t, y - 40 + 2 * t
            if dx * dx + dy * dy < 90:
                v = 120 + ((x * 31 + y * 17 + t * 5) % 41) * 3
            row.append(min(v, 255))
        rows.append(row)
    return rows

INPUTS = (("input.gray", True, 1), ("input-bff.gray", False, 1), ("input-progressive.gray", True, 0))

for name, top_first, step in INPUTS:
    with open(name, "wb") as out:
        for f in range(FRAMES):
            first, second = scene(2 * f), scene(2 * f + step)
            top, bottom = (first, second) if top_first else (second, first)
            for y in range(H):
                out.write(bytes(top[y] if y % 2 == 0 else bottom[y]))
