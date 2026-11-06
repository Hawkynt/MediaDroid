// Motion-compensated temporal smoothing, the averaging (TemporalSmoothing.smooth, after QTGMC's method): the shown
// field's base (uTex) averaged with the bases of the fields before (uEarlier) and after (uLater), each taken at the
// displacement — up to 3 pixels across, 2 down or up, nearest first — whose 3 × 3 patch differs least from the shown
// one's over all three channels, weighted 4 for a perfect match falling to 0 at an average difference of 32 a sample,
// against the shown pixel's 8; whole steps of 0..255, rounded as the reference rounds. Rows count from the top.
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uEarlier;
uniform sampler2D uLater;
uniform vec2 uSize;
varying vec2 vTex;
vec3 px(sampler2D s, float x, float y) {
  vec2 p = clamp(vec2(x, y), vec2(0.0), uSize - 1.0);
  return floor(texture2D(s, vec2(p.x + 0.5, uSize.y - p.y - 0.5) / uSize).rgb * 255.0 + 0.5);
}
float patchDifference(sampler2D s, float x, float y, float dx, float dy) {
  vec3 sad = vec3(0.0);
  for (int v = -1; v <= 1; v++) {
    for (int u = -1; u <= 1; u++) {
      sad += abs(px(s, x + dx + float(u), y + dy + float(v)) - px(uTex, x + float(u), y + float(v)));
    }
  }
  return sad.r + sad.g + sad.b;
}
// The best match's value in [s] and its weight.
vec4 neighbour(sampler2D s, float x, float y) {
  float best = 1.0e9;
  vec3 value = vec3(0.0);
  for (int d = 0; d <= 5; d++) {
    for (int dy = -2; dy <= 2; dy++) {
      for (int dx = -3; dx <= 3; dx++) {
        if (int(abs(float(dx)) + abs(float(dy))) != d) continue;
        float sad = patchDifference(s, x, y, float(dx), float(dy));
        if (sad < best) {
          best = sad;
          value = px(s, x + float(dx), y + float(dy));
        }
      }
    }
  }
  // The reference's single channel scaled to three: 4 - floor(4 sad / (32 × 9 × 3)).
  return vec4(value, max(0.0, 4.0 - floor(4.0 * best / 864.0)));
}
void main() {
  float x = floor(gl_FragCoord.x);
  float y = uSize.y - 1.0 - floor(gl_FragCoord.y);
  vec4 e = neighbour(uEarlier, x, y);
  vec4 l = neighbour(uLater, x, y);
  float count = 8.0 + e.a + l.a;
  vec3 sum = 8.0 * px(uTex, x, y) + e.a * e.rgb + l.a * l.rgb;
  gl_FragColor = vec4(floor((sum + floor(count * 0.5)) / count) / 255.0, 1.0);
}
