# Writes telecined.gray.gz: 30 frames of 128 x 96 8-bit grey at 29.97 fps, 3:2 pulldown (top field first)
# of 24 film frames (telecined-bff.gray.gz: the same bottom field first): a bar sweeping 4 pixels a frame, a drifting diagonal edge and a textured disc, a cut
# to a second scene after the 12th film frame and a still stretch at the end. Fixed arithmetic only, the
# same file on every run. The expected files are FFmpeg 6.0's (configured --disable-x86asm
# --disable-inline-asm) debug output for it, fed as YUV 4:2:0 with flat chroma (`--yuv` writes that to
# standard output) — fieldmatch takes no grey, and a conversion would rescale the luma:
#   python3 make-input.py --yuv | ffmpeg -v debug -f rawvideo -pix_fmt yuv420p -s 128x96 -r 30000/1001 -i - \
#     -vf fieldmatch=order=tff -f null - 2>&1 | grep -o "SC:.*" > fieldmatch.txt
#   python3 make-input.py --yuv | ffmpeg -v debug -f rawvideo -pix_fmt yuv420p -s 128x96 -r 30000/1001 -i - \
#     -vf fieldmatch=order=tff,decimate -f null - 2>&1 | grep -E "frame drop|#[0-9]: " | sed "s/^\[[^]]*\] *//" \
#     > decimate.txt
#   python3 make-input.py --yuv --bff | ffmpeg -v debug -f rawvideo -pix_fmt yuv420p -s 128x96 -r 30000/1001 \
#     -i - -vf fieldmatch=order=bff -f null - 2>&1 | grep -o "SC:.*" > fieldmatch-bff.txt
import gzip
import sys

W, H, FILM = 128, 96, 24


def scene(t):
    rows = []
    cut = t >= 12
    still = min(t, 20)
    for y in range(H):
        row = []
        for x in range(W):
            if not cut:
                v = 40 + (x * 7 + y * 3) % 23
                if (x - 2 * y + 6 * still) % 48 < 16:
                    v += 80
                if abs(x - (10 + 4 * still) % W) < 5:
                    v = 235
                dx, dy = x - 80 + 2 * still, y - 50 + still
                if dx * dx + dy * dy < 300:
                    v = 120 + ((x * 31 + y * 17 + still * 5) % 41) * 3
            else:
                v = 200 - (x * 5 + y * 11) % 37
                if (y + 3 * still) % 20 < 6:
                    v -= 120
                if abs(x - (100 - 5 * still) % W) < 6:
                    v = 20
            row.append(max(0, min(v, 255)))
        rows.append(row)
    return rows


film = [scene(t) for t in range(FILM)]
# 2:3 pulldown, top field first: every four film frames A B C D become AA BB BC CD DD.
pattern = [(0, 0), (1, 1), (1, 2), (2, 3), (3, 3)]

def telecined(top_first):
    frames = []
    for group in range(FILM // 4):
        for first, second in pattern:
            a, b = film[group * 4 + first], film[group * 4 + second]
            top, bottom = (a, b) if top_first else (b, a)
            frames.append(b"".join(bytes(top[y] if y % 2 == 0 else bottom[y]) for y in range(H)))
    return frames


if "--yuv" in sys.argv:
    chroma = bytes([128]) * (W // 2 * H // 2 * 2)
    for frame in telecined("--bff" not in sys.argv):
        sys.stdout.buffer.write(frame + chroma)
else:
    for name, top_first in (("telecined.gray.gz", True), ("telecined-bff.gray.gz", False)):
        with gzip.GzipFile(name, "wb", mtime=0) as out:
            for frame in telecined(top_first):
                out.write(frame)
