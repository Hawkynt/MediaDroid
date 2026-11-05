"""xbrz-table.py <Xbrz.kt> <out.bin>: xBRZ's patterns read from the app's own Xbrz.kt, as the 36 × 25 RGBA texture
VideoFilterStage.xbrzTable() builds from them — a row per factor (2..6) and pattern (in Pattern's order), a texel per
cell of the block, m in red and n in green, 0 / 1 where the pattern leaves the cell alone."""
import re
import sys

source = open(sys.argv[1]).read()
order = re.search(r'enum class Pattern \{([^}]*)\}', source).group(1).replace(' ', '').split(',')
table = source[source.index('private val patterns'):source.index('/** The factors xBRZ scales by. */')]
width = 36
out = bytearray()
for factor in range(2, 7):
    block = re.search(r'\b%d to mapOf\((.*?)\n        \),' % factor, table, re.S).group(1)
    for pattern in order:
        numbers = re.search(r'Pattern\.%s to cells\(([^)]*)\)' % pattern, block).group(1)
        values = [int(v) for v in numbers.replace('\n', ' ').split(',') if v.strip()]
        cells = {}
        for r, c, m, n in zip(*[iter(values)] * 4):
            cells[r * factor + c] = (m, n)
        for cell in range(width):
            m, n = cells.get(cell, (0, 1)) if cell < factor * factor else (0, 1)
            out += bytes((m, n, 0, 0))
open(sys.argv[2], 'wb').write(bytes(out))
