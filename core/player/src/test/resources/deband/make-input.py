# Writes input.ppm: 96 x 64 8-bit RGB with the steps debanding is for — red rising one level every 12 pixels across,
# green one level every 5 rows down, blue a slow diagonal — a dark corner near black (where libplacebo's grain holds
# back), and on the right a hard checkerboard and a disc, detail that has to stay. Fixed arithmetic only, the same file
# on every run. The expected files beside it are libplacebo's own pl_shader_deband for it: make-expected.sh.
import sys

W, H = 96, 64
out = bytearray()
for y in range(H):
    for x in range(W):
        r = 40 + x // 12
        g = 60 + y // 5
        b = 100 + (x + y) * 20 // (W + H)
        if x < 16 and y >= 48:
            r, g, b = 1 + x // 8, 2, 1 + y // 12 - 4
        if x >= 70:
            r = g = b = 200 if (x // 3 + y // 3) % 2 else 30
        if (x - 40) ** 2 + (y - 24) ** 2 <= 64:
            r, g, b = 220, 90, 40
        out += bytes([r, g, b])
open(sys.argv[1] if len(sys.argv) > 1 else 'input.ppm', 'wb').write(b'P6\n%d %d\n255\n' % (W, H) + out)
