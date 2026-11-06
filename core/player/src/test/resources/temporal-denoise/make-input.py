# Writes frame0.ppm .. frame4.ppm: five 64 x 48 frames of what temporal denoising is for — a still background of soft
# shapes and a bar moving 3 pixels a frame, each frame with its own grain (a fixed linear congruential sequence, a few
# steps either way, more in the dark) — so still parts can be averaged over time and the moving bar must not be.
# Fixed arithmetic only, the same files on every run. The expected files beside them: make-expected.sh.
import sys

W, H = 64, 48
out = sys.argv[1] if len(sys.argv) > 1 else '.'
state = 12345


def noise():
    global state
    state = (state * 1103515245 + 12345) & 0x7FFFFFFF
    return (state >> 16) % 9 - 4


for k in range(5):
    data = bytearray()
    for y in range(H):
        for x in range(W):
            r = 60 + x * 2
            g = 90 + y * 2
            b = 120 if (x - 20) ** 2 + (y - 24) ** 2 > 100 else 200
            if 10 + 3 * k <= x < 18 + 3 * k:
                r, g, b = 230, 220, 40
            for v in (r, g, b):
                data.append(max(0, min(255, v + noise())))
    open(f'{out}/frame{k}.ppm', 'wb').write(b'P6\n%d %d\n255\n' % (W, H) + bytes(data))
