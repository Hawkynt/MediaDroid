# Writes picture.ppm (64 x 48, colour: discs, thin lines at angles and a gradient, what an upscaler is judged on) and
# grey.ppm (the same in grey: R = G = B, so a YCbCr picture of it has nothing but its Y). Fixed arithmetic only.
import sys

W, H = 64, 48
out = sys.argv[1] if len(sys.argv) > 1 else '.'


def rgb(x, y):
    if (x - 16) ** 2 + (y - 16) ** 2 <= 100:
        return 220, 60, 40
    if (x - 46) ** 2 + (y - 30) ** 2 <= 64:
        return 40, 90, 210
    if abs(2 * x - y - 20) <= 1 or abs(x + y - 70) == 0 or y == 40:
        return 250, 250, 240
    return 30 + x * 2, 60 + y * 3, 120


picture = bytearray()
grey = bytearray()
for y in range(H):
    for x in range(W):
        r, g, b = rgb(x, y)
        picture += bytes((r, g, b))
        v = (r * 2 + g * 5 + b) // 8
        grey += bytes((v, v, v))
for name, data in (('picture', picture), ('grey', grey)):
    open(f'{out}/{name}.ppm', 'wb').write(b'P6\n%d %d\n255\n' % (W, H) + bytes(data))
