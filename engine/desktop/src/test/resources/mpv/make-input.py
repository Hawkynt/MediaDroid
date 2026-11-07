# Writes picture.ppm (64 x 48, colour: discs, thin lines at angles and a gradient, what an upscaler is judged on),
# grey.ppm (the same in grey: R = G = B, so a YCbCr picture of it has nothing but its Y) and planes.yuv (the colour
# picture's planes as a decoder hands them over). Fixed arithmetic only.
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

# planes.yuv: picture.ppm as a decoder hands it over — yuv420p, limited range BT.709, each chroma sample the mean of
# its 2 x 2 pixels (sited at their centre).
def ycbcr(r, g, b):
    y = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255
    return 16 + 219 * y, 128 + 224 * (b / 255 - y) / 1.8556, 128 + 224 * (r / 255 - y) / 1.5748


values = [[ycbcr(*rgb(x, y)) for x in range(W)] for y in range(H)]
luma = bytes(round(values[y][x][0]) for y in range(H) for x in range(W))
chroma = [bytes(round(sum(values[2 * y + j][2 * x + i][c] for j in (0, 1) for i in (0, 1)) / 4)
                for y in range(H // 2) for x in range(W // 2)) for c in (1, 2)]
open(f'{out}/planes.yuv', 'wb').write(luma + chroma[0] + chroma[1])
