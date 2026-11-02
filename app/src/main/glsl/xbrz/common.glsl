// xBRZ on the graphics chip: the three steps of :core:imaging's Xbrz, whose rules and numbers they follow, as
// three passes. Pixels are addressed top-down as there, the picture's own top row being the one at the top of its
// texture; the two textures in between are stored with their rows in that order too.
//
// 1. corners.frag at (width + 1) x (height + 1): per point where four pixels meet, the strength for E's bottom
//    right, F's bottom left, H's top right and I's top left corner, as 0, 1/2 or 1 in red, green, blue and alpha.
// 2. decisions.frag at the picture's size: per pixel, for each corner turned to the bottom right in turn, what to
//    draw there — pattern x 2, plus 1 when the colour comes from below rather than from the right — over 255.
// 3. scale.frag at n times the size: the pixel's colour, then the four patterns' shares from the table mixed in;
//    the table is 36 texels wide (a cell of the largest block each) and 25 rows (factor 2..6 x pattern).
//
// What the three xBRZ passes share: the picture read top-down by pixel, xBRZ's colour distance.
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
