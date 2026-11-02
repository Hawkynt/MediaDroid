precision highp float;
uniform sampler2D uTex;
uniform vec2 uInSize;
uniform float uGrain;
uniform float uSeed;
// Film grain: a different random value at every pixel and frame, the same on all three channels;
// Dave Hoskins' "hash without sine" (MIT), which stays patternless at large coordinates.
vec3 grain(vec3 color) {
  if (uGrain <= 0.0) return color;
  vec3 p3 = fract(vec3(gl_FragCoord.xy, uSeed).xyz * 0.1031);
  p3 += dot(p3, p3.zyx + 31.32);
  float n = fract((p3.x + p3.y) * p3.z) - 0.5;
  return clamp(color + n * 2.0 * uGrain, 0.0, 1.0);
}
varying vec2 vTex;
float lanczos(float x) {
  x = abs(x);
  if (x < 1e-5) return 1.0;
  if (x >= 3.0) return 0.0;
  float px = 3.14159265 * x;
  return 3.0 * sin(px) * sin(px / 3.0) / (px * px);
}
void main() {
  vec2 pos = vTex * uInSize - 0.5;
  vec2 f = fract(pos);
  vec2 base = floor(pos);
  vec3 sum = vec3(0.0);
  float weights = 0.0;
  vec3 lo = vec3(1.0);
  vec3 hi = vec3(0.0);
  for (int j = -2; j <= 3; j++) {
    float wy = lanczos(float(j) - f.y);
    for (int i = -2; i <= 3; i++) {
      vec3 c = texture2D(uTex, (base + vec2(float(i), float(j)) + 0.5) / uInSize).rgb;
      float w = lanczos(float(i) - f.x) * wy;
      sum += c * w;
      weights += w;
      if (i >= 0 && i <= 1 && j >= 0 && j <= 1) {
        lo = min(lo, c);
        hi = max(hi, c);
      }
    }
  }
  vec3 c = sum / weights;
  gl_FragColor = vec4(grain(mix(c, clamp(c, lo, hi), 0.5)), 1.0);
}
