// Step 1: the corner strengths at each point where four pixels meet.
#include "common.glsl"
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
