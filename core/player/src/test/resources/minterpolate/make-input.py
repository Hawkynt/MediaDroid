# Writes input.gray.gz: 10 frames of 128 x 96 8-bit grey at 25 fps — a textured background panning 3
# pixels right and 1 down a frame, a textured box moving 5 left and 2 down over it, and a cut to a still,
# differently textured picture after the 7th frame. Fixed arithmetic only, the same file on every run.
# The expected file is FFmpeg 6.0's (configured --disable-x86asm --disable-inline-asm) output for it at
# its defaults (bilateral EPZS search, 16-pixel blocks, OBMC, scene changes by frame difference):
#   zcat input.gray.gz | ffmpeg -f rawvideo -pix_fmt gray -s 128x96 -r 25 -i - \
#     -vf minterpolate=fps=50 -f rawvideo -pix_fmt gray - | gzip -n > expected.gray.gz
import gzip

W, H, FRAMES = 128, 96, 10


def texture(x, y, seed):
    v = (x * 37 + y * 91 + seed) % 97
    v = (v * v + x * 13 + y * 7) % 211
    return 30 + v


def frame(t):
    rows = bytearray()
    for y in range(H):
        for x in range(W):
            if t < 7:
                v = texture(x - 3 * t, y - t, 5)
                bx, by = 70 - 5 * t, 20 + 2 * t
                if bx <= x < bx + 28 and by <= y < by + 24:
                    v = 250 - texture(x - bx, y - by, 11) // 2
            else:
                v = texture(x, y, 77) // 2 + 60
            rows.append(max(0, min(255, v)))
    return bytes(rows)


with gzip.GzipFile("input.gray.gz", "wb", mtime=0) as out:
    for t in range(FRAMES):
        out.write(frame(t))
