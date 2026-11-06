"""planes.py split <out.yuv> <c> <in.ppm>...: colour channel c (0 R, 1 G, 2 B) of each picture as the luma of a Y444
picture with flat chroma, one after the other — how GStreamer's deinterlace element is fed one channel at a time.
planes.py join <out.ppm> <index> <width> <height> <r.yuv> <g.yuv> <b.yuv>: picture <index> of three such streams put
back together as one RGB picture."""
import sys


def ppm(path):
    d = open(path, 'rb').read()
    parts = d.split(maxsplit=4)
    w, h = int(parts[1]), int(parts[2])
    return w, h, parts[4][-w * h * 3:]


if sys.argv[1] == 'split':
    c = int(sys.argv[3])
    out = bytearray()
    for path in sys.argv[4:]:
        w, h, px = ppm(path)
        out += px[c::3] + bytes([128]) * (2 * w * h)
    open(sys.argv[2], 'wb').write(out)
else:
    index, w, h = int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5])
    n = w * h
    planes = [open(p, 'rb').read()[index * 3 * n:index * 3 * n + n] for p in sys.argv[6:9]]
    rgb = bytearray(3 * n)
    for c in range(3):
        rgb[c::3] = planes[c]
    open(sys.argv[2], 'wb').write(b'P6\n%d %d\n255\n' % (w, h) + bytes(rgb))
