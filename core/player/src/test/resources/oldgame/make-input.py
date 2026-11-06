# Writes console.gray.gz: 12 frames of 256 x 448 8-bit grey, top field first, as a capture card weaves a 240p game
# console's picture (here 224 lines, a SNES's) — each field a whole picture of its own, 1/60 s after the one before,
# on alternate rows. Frames 0-5 stand still (a sprite over a backdrop of thin horizontal stripes, the detail bob and
# YADIF turn into flicker and blocks); from frame 6 the backdrop's bars scroll 2 pixels a field. Fixed arithmetic only,
# the same file on every run. The expected files are FFmpeg n9.0.2's: each field on its own, stretched back to the full
# height by bilinear scaling (the stretch old-console mode does; its averaging of a still field with the one before
# changes nothing on a picture that stands still):
#   for f in top bottom; do
#     zcat console.gray.gz | ffmpeg -f rawvideo -pix_fmt gray -s 256x448 -i - \
#       -vf "field=$f,scale=256:448:flags=bilinear+accurate_rnd" -f rawvideo - | gzip -n > $f.gray.gz
#   done
import gzip

W, H, LINES = 256, 448, 224


def picture(t):
    """The console's picture at field t: LINES rows."""
    scroll = 2 * max(t - 11, 0)
    rows = []
    for y in range(LINES):
        row = bytearray()
        for x in range(W):
            u = x + scroll
            v = 60 if y % 8 < 4 else 120
            if u % 32 < 6:
                v = 200
            if 100 <= x < 132 and 90 <= y < 122:
                v = 240 if (x // 4 + y // 4) % 2 == 0 else 20
            row.append(v)
        rows.append(bytes(row))
    return rows


with gzip.GzipFile('console.gray.gz', 'wb', mtime=0) as out:
    for k in range(12):
        top, bottom = picture(2 * k), picture(2 * k + 1)
        out.write(b''.join(top[y // 2] if y % 2 == 0 else bottom[y // 2] for y in range(H)))
