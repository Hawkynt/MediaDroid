// EWA Lanczos-sharp (see Ewa): the source pixels in a circle of uRadius around the position weigh by
// their distance, read from uTable; the result is pulled halfway into the four nearest pixels' range,
// as the Lanczos pass does.
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uTable;
uniform vec2 uInSize;
uniform float uRadius;
varying vec2 vTex;
float weight(float d) {
  if (d >= uRadius) return 0.0;
  float i = floor(d / uRadius * 511.0 + 0.5);
  vec4 t = texture2D(uTable, vec2((i + 0.5) / 512.0, 0.5));
  return (t.r * 255.0 * 256.0 + t.g * 255.0) / 65535.0 * 1.25 - 0.25;
}
void main() {
  vec2 pos = vTex * uInSize - 0.5;
  vec2 f = fract(pos);
  vec2 base = floor(pos);
  vec3 sum = vec3(0.0);
  float weights = 0.0;
  vec3 lo = vec3(1.0);
  vec3 hi = vec3(0.0);
  for (int j = -3; j <= 4; j++) {
    for (int i = -3; i <= 4; i++) {
      vec2 offset = vec2(float(i), float(j));
      float w = weight(length(offset - f));
      if (w == 0.0) continue;
      vec3 c = texture2D(uTex, (base + offset + 0.5) / uInSize).rgb;
      sum += c * w;
      weights += w;
      if (i >= 0 && i <= 1 && j >= 0 && j <= 1) {
        lo = min(lo, c);
        hi = max(hi, c);
      }
    }
  }
  vec3 c = sum / weights;
  gl_FragColor = vec4(mix(c, clamp(c, lo, hi), 0.5), 1.0);
}
