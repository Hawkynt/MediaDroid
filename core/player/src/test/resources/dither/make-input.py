# Writes input.ppm: 160 x 96 16-bit RGB (big-endian, as PPM has it) of what dithering is for — gradients finer than 8
# bits: red rising four 8-bit levels across the whole width, green two levels down the height, blue a slow diagonal, a
# corner near black, a strip of exact 8-bit levels (which dithering has to leave as they are) and one of full white.
# Fixed arithmetic only, the same file on every run. The expected file beside it is libplacebo's own for it:
# make-expected.sh.
import sys

W, H = 160, 96
out = bytearray()
for y in range(H):
    for x in range(W):
        r = 40 * 257 + x * 4 * 257 // W
        g = 120 * 257 + y * 2 * 257 // H
        b = 200 * 257 + (x + y) * 3 * 257 // (W + H)
        if x < 32 and y >= 64:
            r, g, b = x * 3 * 257 // 32, (y - 64) * 257 // 32, 257 // 2
        if y < 8:
            r = g = b = (x // 10) * 17 * 257
        if y >= 92:
            r = g = b = 65535
        out += bytes([r >> 8, r & 255, g >> 8, g & 255, b >> 8, b & 255])
open(sys.argv[1] if len(sys.argv) > 1 else 'input.ppm', 'wb').write(b'P6\n%d %d\n65535\n' % (W, H) + out)
