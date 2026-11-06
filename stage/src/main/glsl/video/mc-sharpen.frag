// Motion-compensated temporal smoothing, the last step (TemporalSmoothing.sharpen, after QTGMC's method): the averaged
// picture (uTex) contra-sharpened — its difference from its own [1 2 1] blur given back at most as far as the averaging
// moved it from the base (uBase), and only in that direction — held within the lowest and highest of the base's 3 × 3
// (QTGMC's repair), and the rows the camera took — rows uParity, uParity + 2, … of the frame as it came (uSource) — put
// back as they were. Whole steps of 0..255; rows count from the top.
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uBase;
uniform sampler2D uSource;
uniform vec2 uSize;
uniform float uParity;
varying vec2 vTex;
vec3 px(sampler2D s, float x, float y) {
  vec2 p = clamp(vec2(x, y), vec2(0.0), uSize - 1.0);
  return floor(texture2D(s, vec2(p.x + 0.5, uSize.y - p.y - 0.5) / uSize).rgb * 255.0 + 0.5);
}
void main() {
  float x = floor(gl_FragCoord.x);
  float y = uSize.y - 1.0 - floor(gl_FragCoord.y);
  if (abs(mod(y, 2.0) - uParity) < 0.5) {
    gl_FragColor = vec4(px(uSource, x, y) / 255.0, 1.0);
    return;
  }
  vec3 blur = vec3(0.0);
  vec3 low = vec3(255.0);
  vec3 high = vec3(0.0);
  for (int v = -1; v <= 1; v++) {
    for (int u = -1; u <= 1; u++) {
      float weight = (2.0 - abs(float(u))) * (2.0 - abs(float(v)));
      blur += weight * px(uTex, x + float(u), y + float(v));
      vec3 b = px(uBase, x + float(u), y + float(v));
      low = min(low, b);
      high = max(high, b);
    }
  }
  vec3 s = px(uTex, x, y);
  vec3 detail = s - floor((blur + 8.0) / 16.0);
  vec3 taken = px(uBase, x, y) - s;
  vec3 same = vec3(greaterThan(detail, vec3(0.0))) * vec3(greaterThan(taken, vec3(0.0))) +
    vec3(lessThanEqual(detail, vec3(0.0))) * vec3(lessThanEqual(taken, vec3(0.0)));
  vec3 back = same * min(abs(detail), abs(taken)) * sign(taken);
  gl_FragColor = vec4(clamp(s + back, low, high) / 255.0, 1.0);
}
