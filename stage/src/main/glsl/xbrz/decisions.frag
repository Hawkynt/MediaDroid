// Step 2: per pixel and turn, the pattern to draw and where its colour comes from.
#include "common.glsl"
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
