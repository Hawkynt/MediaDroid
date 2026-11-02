// Step 3: each output pixel from its picture pixel's colour and the patterns laid over it.
#include "common.glsl"
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
