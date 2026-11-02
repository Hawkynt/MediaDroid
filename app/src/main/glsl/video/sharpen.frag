// Unsharp masking on the luma: the picture plus its difference from a blurred copy. Sharpness
// uses the eight nearest neighbours; clarity a ring six pixels out, which lifts texture and
// local contrast rather than edges. Rec. 709 luma weights.
precision highp float;
uniform sampler2D uTex;
uniform vec2 uTexel;
uniform float uSharpness;
uniform float uClarity;
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
float luma(vec2 at) { return dot(texture2D(uTex, at).rgb, vec3(0.2126, 0.7152, 0.0722)); }
void main() {
  vec3 color = texture2D(uTex, vTex).rgb;
  float l = dot(color, vec3(0.2126, 0.7152, 0.0722));
  float near = 0.0;
  float far = 0.0;
  for (int i = 0; i < 8; i++) {
    float angle = float(i) * 0.785398;
    vec2 direction = vec2(cos(angle), sin(angle));
    near += luma(vTex + direction * uTexel);
    far += luma(vTex + direction * uTexel * 6.0);
  }
  float boost = uSharpness * 2.0 * (l - near / 8.0) + uClarity * 0.8 * (l - far / 8.0);
  gl_FragColor = vec4(grain(clamp(color + boost, 0.0, 1.0)), 1.0);
}
