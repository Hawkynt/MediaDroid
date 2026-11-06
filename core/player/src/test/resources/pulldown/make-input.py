# Writes pal.gray.gz: 80 frames of 256 x 192 8-bit grey at 25 fps, top field first, in four parts, and pal.txt, what
# each frame is made of. Frames 0-19 are video (every field its own moment, the picture moving 2 pixels a field);
# 20-44 film in 2:2 pulldown (both fields of a frame from one film frame, 4 pixels a film frame); at 45 an edit to a
# second scene whose film frames start on a bottom field: 45-64 pair each frame's top field with the bottom field
# before it (phase-shifted 2:2); at 65 an edit back to video. The picture has no detail from top to bottom that a
# correctly paired frame could comb on — bars and soft ramps — so a frame woven from the right
# fields is the film frame itself. Fixed arithmetic only, the same file on every run. `--film` writes the film
# frames each frame should come back to (pal-film.gray.gz), `--yuv` the clip as YUV 4:2:0 with flat chroma to
# standard output, for FFmpeg's idet:
#   python3 make-input.py --yuv | ffmpeg -f rawvideo -pix_fmt yuv420p -s 256x192 -r 25 -i - -vf idet -f null -
import gzip
import sys

W, H = 256, 192


def scene(position, second):
    """The scene at [position] pixels along: the first, or the [second] after the edit."""
    rows = []
    for y in range(H):
        row = bytearray()
        for x in range(W):
            u = x + position
            if second:
                v = 190 - y // 4 if u % 40 < 16 else 30 + y // 4
            else:
                v = 200 if u % 32 < 12 else 40 + y // 3
                if (u // 64) % 2 == 0 and u % 32 >= 12:
                    v += 30
            row.append(v)
        rows.append(bytes(row))
    return rows


def frame(top, bottom):
    return b''.join(top[y] if y % 2 == 0 else bottom[y] for y in range(H))


def clip():
    frames, film, notes = [], [], []
    for k in range(20):
        frames.append(frame(scene(4 * k, False), scene(4 * k + 2, False)))
        film.append(None)
        notes.append(f'{len(notes)} video')
    for j in range(25):
        f = scene(100 + 4 * j, False)
        frames.append(frame(f, f))
        film.append(b''.join(f))
        notes.append(f'{len(notes)} film {j} in frame')
    # The second scene's film frame j covers the bottom field of clip frame 44 + j and the top field of 45 + j.
    for j in range(20):
        frames.append(frame(scene(4 * (j + 1), True), scene(4 * (j + 2), True)))
        film.append(b''.join(scene(4 * (j + 1), True)))
        notes.append(f'{len(notes)} film {j + 1} across')
    for k in range(15):
        frames.append(frame(scene(300 + 4 * k, True), scene(300 + 4 * k + 2, True)))
        film.append(None)
        notes.append(f'{len(notes)} video')
    return frames, film, notes


frames, film, notes = clip()
if '--yuv' in sys.argv:
    chroma = bytes([128]) * (W * H // 4)
    for f in frames:
        sys.stdout.buffer.write(f + chroma + chroma)
elif '--film' in sys.argv:
    with gzip.GzipFile('pal-film.gray.gz', 'wb', mtime=0) as out:
        for f in film:
            out.write(f if f is not None else bytes(W * H))
else:
    with gzip.GzipFile('pal.gray.gz', 'wb', mtime=0) as out:
        for f in frames:
            out.write(f)
    with open('pal.txt', 'w') as out:
        out.write('\n'.join(notes) + '\n')
