# Writes the HDR fixtures: 96 x 64 16-bit RGB (binary PPM, maximum 65535), coded values as an HDR10 or HLG decoder
# hands them over (BT.2020 R'G'B', full range) — a grey ramp over the whole code range, the six BT.2020 primaries and
# secondaries rising in brightness (far outside BT.709), mid colours, a dark corner near black and a highlight block.
# Fixed arithmetic only, the same files on every run. input.ppm is read as PQ and as HLG; with dimmer.ppm (a little
# darker: smoothed over), darker.ppm (a good deal darker: a scene change) and dark.ppm (nearly black) it makes a short
# scene for peak detection. The expected files beside them are libplacebo's own output: make-expected.sh.
import os
import sys

W, H = 96, 64


def picture(scale, dark=False):
    out = bytearray()
    for y in range(H):
        for x in range(W):
            if y < 16:
                r = g = b = x * 65535 // (W - 1)
            elif y < 32:
                level = 20000 + (y - 16) * 2600 + (x % 16) * 400
                mask = [(1, 0, 0), (0, 1, 0), (0, 0, 1), (0, 1, 1), (1, 0, 1), (1, 1, 0)][x // 16]
                r, g, b = (level * m + 9000 * (1 - m) for m in mask)
            elif y < 48:
                r = 30000 + (x * 7 + y * 13) % 9000
                g = 26000 + (x * 11 + y * 5) % 8000
                b = 22000 + (x * 3 + y * 17) % 7000
            elif x < 48:
                r = (x * 37 + y * 11) % 6000
                g = (x * 29 + y * 23) % 5000
                b = (x * 13 + y * 31) % 7000
            else:
                r = g = b = 58000 + (x - 48) * 100
            if dark:
                r, g, b = (v // 16 for v in (r, g, b))
            for v in (r, g, b):
                v = min(65535, v * scale // 100)
                out += bytes([v >> 8, v & 0xFF])
    return b'P6\n%d %d\n65535\n' % (W, H) + bytes(out)


here = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__))
for name, data in (('input', picture(100)), ('dimmer', picture(97)), ('darker', picture(70)),
                   ('dark', picture(100, dark=True))):
    with open(os.path.join(here, name + '.ppm'), 'wb') as f:
        f.write(data)
