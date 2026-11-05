"""fields.py <picture.ppm> <out prefix>: three interlaced frames from a picture — its right half moving 3 px a field
across and 1 px a field down, its left half still — as <prefix>0.ppm .. <prefix>2.ppm."""
import sys
d = open(sys.argv[1], 'rb').read(); parts = d.split(maxsplit=4); W, H = int(parts[1]), int(parts[2])
px = parts[4][-W*H*3:]
def at(x, y):
    x = min(max(x, 0), W - 1); y = min(max(y, 0), H - 1); i = (y * W + x) * 3; return px[i:i+3]
for k in range(3):
    rows = []
    for y in range(H):
        field = 2 * k + (y & 1)
        row = bytearray()
        for x in range(W):
            row += at(x, y) if x < W // 2 else at(x - 3 * field, y - field)
        rows.append(bytes(row))
    open(f'{sys.argv[2]}{k}.ppm', 'wb').write(b'P6\n%d %d\n255\n' % (W, H) + b''.join(rows))
