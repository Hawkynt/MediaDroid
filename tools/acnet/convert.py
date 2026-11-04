"""convert.py <acnet_f8b4.glsl> <out dir>: ACNetGLSL's mpv hook passes as the filter stage's fragment shaders.

Each convolution pass becomes conv-N.frag: the first input texture (…_TEX_0) is uTex, the second (…_TEX_1) uTex1,
LUMA the picture's luma (uTex for the head, uPicture for the last conv). Weights are kept verbatim; only the vertical
offsets are turned round, as the stage's textures count rows upwards. The pixel shuffle is written by hand.
"""
import re
import sys

src, out = sys.argv[1], sys.argv[2]
text = open(src).read()
passes = re.split(r'\n(?=//!DESC)', text.strip())
convs = [p for p in passes if 'pixelshuff' not in p]

HEADER = """// ACNet F8B4 by TianZer (ACNetGLSL, MIT, see app/THIRD-PARTY-NOTICE.ACNet.txt), pass {n} of its {total}
// convolutions as the filters' stage runs them: {desc}.
// The weights are ACNet's own, copied from its mpv shader acnet_f8b4.glsl; only the vertical offsets are turned
// round, as the stage's textures count rows upwards.
precision highp float;
"""

for n, p in enumerate(convs, 1):
    desc = re.search(r'//!DESC ACNet F8B4 (.*)', p).group(1).strip()
    binds = re.findall(r'//!BIND (\S+)', p)
    body = p[p.index('vec4 hook() {'):]
    tex = [b for b in binds if b != 'LUMA']
    lines = [HEADER.format(n=n, total=len(convs), desc=desc)]
    if not tex:
        lines.append('uniform sampler2D uTex;\n')
        luma_sampler = 'uTex'
    else:
        lines.append('uniform sampler2D uTex;\nuniform sampler2D uTex1;\n')
        luma_sampler = 'uPicture'
        if 'LUMA' in binds:
            lines.append('uniform sampler2D uPicture;\n')
    lines.append('uniform vec2 uTexel;\nvarying vec2 vTex;\n')
    if 'LUMA' in binds:
        lines.append('#include "luma.glsl"\n')
        lines.append('// mpv counts rows downwards, the textures here upwards: a step of y down is a step of v down.\n')
        lines.append(f'float lumaAt(float x, float y) {{ return luma(texture2D({luma_sampler}, '
                     'vTex + vec2(x, -y) * uTexel).rgb); }\n')
    for i, name in enumerate(tex):
        sampler = 'uTex' if i == 0 else 'uTex1'
        lines.append(f'vec4 in{i}(float x, float y) {{ return texture2D({sampler}, vTex + vec2(x, -y) * uTexel); }}\n')
    for i, name in enumerate(tex):
        body = re.sub(name + r'_texOff\(vec2\(([^,]+), ([^)]+)\)\)', rf'in{i}(\1, \2)', body)
    body = re.sub(r'LUMA_texOff\(vec2\(([^,]+), ([^)]+)\)\)\.x', r'lumaAt(\1, \2)', body)
    assert '_texOff' not in body, (n, body[:200])
    body = body.replace('vec4 hook() {', 'void main() {').replace('return result;', 'gl_FragColor = result;')
    body = re.sub(r'(?m)^    ', '  ', body)  # the repository's 2-space indent for shaders

    def rows(m):  # a mat4's 16 values on four rows of four, as the Anime4K passes are laid out
        v = [x.strip() for x in m.group(1).split(',')]
        assert len(v) == 16
        return 'mat4(\n' + ''.join('    ' + ', '.join(v[r * 4:r * 4 + 4]) + ',\n' for r in range(4)).rstrip(',\n') + \
            '\n  )'
    body = re.sub(r'mat4\(([^()]*)\)', rows, body)
    assert all(len(line) <= 120 for line in body.split('\n')), n
    lines.append(body.rstrip() + '\n')
    open(f'{out}/conv-{n}.frag', 'w').write(''.join(lines))
print(len(convs), 'passes')
