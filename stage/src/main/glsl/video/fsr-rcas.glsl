// FSR 1's RCAS, the shared body of fsr-rcas.frag (GLSL ES 1.00) and fsr-rcas-es3.frag (GLSL ES 3.00); see there.
// The wrapper declares the uniforms and vTex, brings in the approximate reciprocals and defines FRAG_COLOR.
// Film grain: a different random value at every pixel and frame, the same on all three channels;
// Dave Hoskins' "hash without sine" (MIT), which stays patternless at large coordinates.
vec3 grain(vec3 color) {
  if (uGrain <= 0.0) return color;
  vec3 p3 = fract(vec3(gl_FragCoord.xy, uSeed).xyz * 0.1031);
  p3 += dot(p3, p3.zyx + 31.32);
  float n = fract((p3.x + p3.y) * p3.z) - 0.5;
  return clamp(color + n * 2.0 * uGrain, 0.0, 1.0);
}
void main() {
  vec3 b = texture2D(uTex, vTex - vec2(0.0, uTexel.y)).rgb;
  vec3 d = texture2D(uTex, vTex - vec2(uTexel.x, 0.0)).rgb;
  vec3 e = texture2D(uTex, vTex).rgb;
  vec3 f = texture2D(uTex, vTex + vec2(uTexel.x, 0.0)).rgb;
  vec3 h = texture2D(uTex, vTex + vec2(0.0, uTexel.y)).rgb;
  vec3 mn4 = min(min(b, d), min(f, h));
  vec3 mx4 = max(max(b, d), max(f, h));
  vec3 hitMin = min(mn4, e) / max(4.0 * mx4, vec3(1e-30));
  vec3 hitMax = (1.0 - max(mx4, e)) / min(4.0 * mn4 - 4.0, vec3(-1e-30));
  // Where the neighbours of a colour are all 0, AMD's hitMin is 0 times an infinite reciprocal — not a number — and
  // the max below takes the other side, as GPUs' max does with a NaN; likewise hitMax where they are all 1. Said
  // outright here, so no NaN is made at all.
  vec3 dark = step(mx4, vec3(0.0));
  vec3 bright = step(1.0, mn4);
  vec3 lobeRGB = max(mix(-hitMin, vec3(-1e30), dark), mix(hitMax, vec3(-1e30), bright));
  float lobe = max(-0.1875, min(max(lobeRGB.r, max(lobeRGB.g, lobeRGB.b)), 0.0)) * uSharp;
  vec3 c = (lobe * b + lobe * d + lobe * h + lobe * f + e) * APrxMedRcpF1(4.0 * lobe + 1.0);
  FRAG_COLOR = vec4(grain(c), 1.0);
}
