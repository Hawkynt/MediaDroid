package com.hawkynt.mediadroid.player

/**
 * xBRZ on the graphics chip: the three steps of [com.hawkynt.mediadroid.imaging.Xbrz], whose rules and numbers
 * they follow, as three passes. Pixels are addressed top-down as there, the picture's own top row being the one at
 * the top of its texture; the two textures in between are stored with their rows in that order too.
 *
 * 1. [CORNERS] at (width + 1) × (height + 1): per point where four pixels meet, the strength for E's bottom right,
 *    F's bottom left, H's top right and I's top left corner, as 0, ½ or 1 in red, green, blue and alpha.
 * 2. [DECISIONS] at the picture's size: per pixel, for each corner turned to the bottom right in turn, what to draw
 *    there — pattern × 2, plus 1 when the colour comes from below rather than from the right — over 255.
 * 3. [SCALE] at n times the size: the pixel's colour, then the four patterns' shares from the table mixed in.
 */
internal object XbrzShaders {

    private const val COMMON = """
        precision highp float;
        uniform sampler2D uTex;
        uniform vec2 uSize;
        varying vec2 vTex;
        vec3 px(float x, float y) {
            vec2 at = vec2((x + 0.5) / uSize.x, 1.0 - (y + 0.5) / uSize.y);
            return floor(texture2D(uTex, at).rgb * 255.0 + 0.5);
        }
        float dist(vec3 p, vec3 q) {
            vec3 d = p - q;
            d = sign(d) * floor(abs(d) * 0.5) * 2.0;
            float y = 0.2627 * d.r + (1.0 - 0.0593 - 0.2627) * d.g + 0.0593 * d.b;
            float cb = 0.5 / (1.0 - 0.0593) * (d.b - y);
            float cr = 0.5 / (1.0 - 0.2627) * (d.r - y);
            return sqrt(y * y + cb * cb + cr * cr);
        }
        bool same(vec3 p, vec3 q) { return dist(p, q) < 30.0; }
        bool equal3(vec3 p, vec3 q) { return all(equal(p, q)); }
    """

    /** Step 1: the corner strengths at each point where four pixels meet. */
    const val CORNERS = COMMON + """
        void main() {
            vec2 p = floor(gl_FragCoord.xy);
            float x = p.x - 1.0;
            float y = p.y - 1.0;
            vec3 e = px(x, y);
            vec3 f = px(x + 1.0, y);
            vec3 h = px(x, y + 1.0);
            vec3 i = px(x + 1.0, y + 1.0);
            vec4 result = vec4(0.0);
            if (!((equal3(e, f) && equal3(h, i)) || (equal3(e, h) && equal3(f, i)))) {
                float hf = dist(px(x - 1.0, y + 1.0), e) + dist(e, px(x + 1.0, y - 1.0))
                    + dist(px(x, y + 2.0), i) + dist(i, px(x + 2.0, y)) + 4.0 * dist(h, f);
                float ei = dist(px(x - 1.0, y), h) + dist(h, px(x + 1.0, y + 2.0))
                    + dist(px(x, y - 1.0), f) + dist(f, px(x + 2.0, y + 1.0)) + 4.0 * dist(e, i);
                if (hf < ei) {
                    float s = 3.6 * hf < ei ? 2.0 : 1.0;
                    if (!equal3(e, f) && !equal3(e, h)) result.r = s;
                    if (!equal3(i, h) && !equal3(i, f)) result.a = s;
                } else if (ei < hf) {
                    float s = 3.6 * ei < hf ? 2.0 : 1.0;
                    if (!equal3(h, e) && !equal3(h, i)) result.b = s;
                    if (!equal3(f, e) && !equal3(f, i)) result.g = s;
                }
            }
            gl_FragColor = result * 0.5;
        }
    """

    /** Step 2: per pixel and turn, the pattern to draw and where its colour comes from. */
    const val DECISIONS = COMMON + """
        uniform sampler2D uCorners;
        vec4 point(float x, float y) {
            vec2 at = vec2((x + 0.5) / (uSize.x + 1.0), (y + 0.5) / (uSize.y + 1.0));
            return floor(texture2D(uCorners, at) * 2.0 + 0.5);
        }
        float decide(float x, float y, vec2 ax, vec2 ay, float strength, float topRight, float bottomLeft) {
            if (strength < 0.5) return 0.0;
            vec3 b = px(x - ay.x, y - ay.y);
            vec3 c = px(x + ax.x - ay.x, y + ax.y - ay.y);
            vec3 d = px(x - ax.x, y - ax.y);
            vec3 e = px(x, y);
            vec3 f = px(x + ax.x, y + ax.y);
            vec3 g = px(x - ax.x + ay.x, y - ax.y + ay.y);
            vec3 h = px(x + ay.x, y + ay.y);
            vec3 i = px(x + ax.x + ay.x, y + ax.y + ay.y);
            bool line = strength > 1.5 || !(
                (topRight > 0.5 && !same(e, g)) ||
                (bottomLeft > 0.5 && !same(e, c)) ||
                (!same(e, i) && same(g, h) && same(h, i) && same(i, f) && same(f, c)));
            float below = dist(e, f) <= dist(e, h) ? 0.0 : 1.0;
            float pattern = 5.0;
            if (line) {
                float fg = dist(f, g);
                float hc = dist(h, c);
                bool shallow = 2.4 * fg <= hc && !equal3(e, g) && !equal3(d, g);
                bool steep = 2.4 * hc <= fg && !equal3(e, c) && !equal3(b, c);
                pattern = shallow ? (steep ? 3.0 : 1.0) : (steep ? 2.0 : 4.0);
            }
            return pattern * 2.0 + below;
        }
        void main() {
            vec2 p = floor(gl_FragCoord.xy);
            float x = p.x;
            float y = p.y;
            float br = point(x + 1.0, y + 1.0).r;
            float tr = point(x + 1.0, y).b;
            float tl = point(x, y).a;
            float bl = point(x, y + 1.0).g;
            gl_FragColor = vec4(
                decide(x, y, vec2(1.0, 0.0), vec2(0.0, 1.0), br, tr, bl),
                decide(x, y, vec2(0.0, -1.0), vec2(1.0, 0.0), tr, tl, br),
                decide(x, y, vec2(-1.0, 0.0), vec2(0.0, -1.0), tl, bl, tr),
                decide(x, y, vec2(0.0, 1.0), vec2(-1.0, 0.0), bl, br, tl)
            ) / 255.0;
        }
    """

    /** Width of the pattern table: one texel a cell of the largest block. */
    const val TABLE_WIDTH = 36

    /** Step 3: each output pixel from its picture pixel's colour and the patterns laid over it. */
    const val SCALE = COMMON + """
        uniform sampler2D uDecisions;
        uniform sampler2D uTable;
        uniform float uFactor;
        vec3 apply(vec3 colour, float code, float x, float y, vec2 ax, vec2 ay, float c, float r) {
            if (code < 0.5) return colour;
            float pattern = floor(code * 0.5);
            vec2 from = code - pattern * 2.0 < 0.5 ? ax : ay;
            float index = r * uFactor + c;
            float row = (uFactor - 2.0) * 5.0 + pattern - 1.0;
            vec2 w = floor(texture2D(uTable, vec2((index + 0.5) / 36.0, (row + 0.5) / 25.0)).rg * 255.0 + 0.5);
            if (w.x < 0.5) return colour;
            vec3 front = px(x + from.x, y + from.y);
            if (w.x == w.y) return front;
            return floor((front * w.x + colour * (w.y - w.x)) / w.y + 0.5);
        }
        void main() {
            vec2 o = floor(gl_FragCoord.xy);
            float n = uFactor;
            float oy = n * uSize.y - 1.0 - o.y;
            float x = floor(o.x / n);
            float y = floor(oy / n);
            float col = o.x - x * n;
            float row = oy - y * n;
            vec4 code = floor(texture2D(uDecisions, vec2((x + 0.5) / uSize.x, (y + 0.5) / uSize.y)) * 255.0 + 0.5);
            vec3 colour = px(x, y);
            colour = apply(colour, code.r, x, y, vec2(1.0, 0.0), vec2(0.0, 1.0), col, row);
            colour = apply(colour, code.g, x, y, vec2(0.0, -1.0), vec2(1.0, 0.0), n - 1.0 - row, col);
            colour = apply(colour, code.b, x, y, vec2(-1.0, 0.0), vec2(0.0, -1.0), n - 1.0 - col, n - 1.0 - row);
            colour = apply(colour, code.a, x, y, vec2(0.0, 1.0), vec2(-1.0, 0.0), row, n - 1.0 - col);
            gl_FragColor = vec4(colour / 255.0, 1.0);
        }
    """
}
