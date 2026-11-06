# Writes input.yuv: a 64 x 48 yuv420p frame (full range, BT.709) of what chroma upscaling is for — saturated discs and
# bars on grey and on each other, a diagonal edge, thin coloured lines and a gradient — its chroma the average of each
# 2 x 2 block, so colour edges fall inside a chroma sample where the luma still has them sharp. Fixed arithmetic only,
# the same file on every run. The expected files beside it are libplacebo's own renderer on it: make-expected.sh.
import sys

W, H = 64, 48
KR, KB = 0.2126, 0.0722


def rgb(x, y):
    if (x - 18) ** 2 + (y - 16) ** 2 <= 81:
        return 220, 30, 40
    if (x - 44) ** 2 + (y - 30) ** 2 <= 64:
        return 30, 60, 210
    if 26 <= x < 33 and y < 40:
        return 40, 200, 60
    if x + y > 80:
        return 240, 220, 30
    if y in (41, 44) and x < 30:
        return 200, 40, 200
    if x == 5 and y > 25:
        return 20, 200, 200
    return 90 + x, 100 + y, 110


Y = bytearray()
Cb = [[0.0] * W for _ in range(H)]
Cr = [[0.0] * W for _ in range(H)]
for y in range(H):
    for x in range(W):
        r, g, b = rgb(x, y)
        luma = KR * r + (1 - KR - KB) * g + KB * b
        Y.append(max(0, min(255, int(luma + 0.5))))
        Cb[y][x] = (b - luma) / (2 * (1 - KB)) + 128
        Cr[y][x] = (r - luma) / (2 * (1 - KR)) + 128
U = bytearray()
V = bytearray()
for plane, out in ((Cb, U), (Cr, V)):
    for cy in range(H // 2):
        for cx in range(W // 2):
            s = sum(plane[cy * 2 + dy][cx * 2 + dx] for dy in (0, 1) for dx in (0, 1)) / 4
            out.append(max(0, min(255, int(s + 0.5))))
open(sys.argv[1] if len(sys.argv) > 1 else 'input.yuv', 'wb').write(bytes(Y + U + V))
