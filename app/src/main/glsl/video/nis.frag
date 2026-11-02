// NVIDIA Image Scaling's NVScaler (MIT, see Nis, which it is transcribed from): per output pixel the 6 × 6
// source luma, four directional edge strengths from the 3 × 3 around the nearest four pixels, a 6-tap
// scaler blended with directional 6-tap filters that sharpen by luma and hold back ringing; the luma
// change is added to a bilinear tap of the colour. Rows count from the top, as in NIS. Every value is
// its own variable (pIJ is the luma of row I, column J) rather than an array: mobile graphics chips keep
// local arrays in memory, not registers, which made the pass about 18 times slower.
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uCoef;
uniform vec2 uInSize;
uniform vec2 uOutSize;
uniform float uDetectRatio;
uniform float uDetectThres;
uniform float uMinContrastRatio;
uniform float uRatioNorm;
uniform float uSharpStartY;
uniform float uSharpScaleY;
uniform float uSharpStrengthMin;
uniform float uSharpStrengthScale;
uniform float uSharpLimitMin;
uniform float uSharpLimitScale;
varying vec2 vTex;
// Both taps of a phase in one fetch: the scaling tap in x, the sharpening tap in y.
vec2 coef(float phase, float tap) {
  vec4 t = texture2D(uCoef, vec2((tap + 0.5) / 6.0, (phase + 0.5) / 64.0));
  return (vec2(t.r, t.b) * 255.0 * 256.0 + vec2(t.g, t.a) * 255.0) / 65535.0 * 2.5 - 1.0;
}
float lumaOf(vec3 c) { return 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b; }
float lumaAt(float column, float row) {
  return lumaOf(texture2D(uTex, vec2(column + 0.5, uInSize.y - row - 0.5) / uInSize).rgb);
}
vec4 edgeMap(float q0, float q1, float q2, float q3, float q4, float q5, float q6, float q7, float q8) {
  float g0 = abs(q0 + q1 + q2 - q6 - q7 - q8);
  float g45 = abs(q3 + q0 + q1 - q7 - q8 - q5);
  float g90 = abs(q0 + q3 + q6 - q2 - q5 - q8);
  float g135 = abs(q3 + q6 + q7 - q1 - q2 - q5);
  float a = max(g0, g90);
  float aMin = min(g0, g90);
  float b = max(g45, g135);
  float bMin = min(g45, g135);
  if (a + b == 0.0) return vec4(0.0);
  float e090 = min(a / (a + b), 1.0);
  float e45135 = 1.0 - e090;
  bool c090 = a > aMin * uDetectRatio && a > uDetectThres && a > bMin;
  bool c45135 = b > bMin * uDetectRatio && b > uDetectThres && b > aMin;
  bool cG0 = a == g0;
  bool cG45 = b == g45;
  float f090 = (c090 && c45135) ? e090 : 1.0;
  float f45135 = (c090 && c45135) ? e45135 : 1.0;
  return vec4(
    (c090 && cG0) ? f090 : 0.0,
    (c090 && !cG0) ? f090 : 0.0,
    (c45135 && cG45) ? f45135 : 0.0,
    (c45135 && !cG45) ? f45135 : 0.0);
}
float evalPoly6(float x0, float x1, float x2, float x3, float x4, float x5, float phase) {
  vec2 c0 = coef(phase, 0.0);
  vec2 c1 = coef(phase, 1.0);
  vec2 c2 = coef(phase, 2.0);
  vec2 c3 = coef(phase, 3.0);
  vec2 c4 = coef(phase, 4.0);
  vec2 c5 = coef(phase, 5.0);
  float y = c0.x * x0 + c1.x * x1 + c2.x * x2 + c3.x * x3 + c4.x * x4 + c5.x * x5;
  float usm = c0.y * x0 + c1.y * x1 + c2.y * x2 + c3.y * x3 + c4.y * x4 + c5.y * x5;
  float yScale = 1.0 - clamp((y - uSharpStartY) * uSharpScaleY, 0.0, 1.0);
  usm *= yScale * uSharpStrengthScale + uSharpStrengthMin;
  float limit = (yScale * uSharpLimitScale + uSharpLimitMin) * y;
  usm = min(limit, max(-limit, usm));
  bool selector = phase <= 32.0;
  float sel = selector ? x0 : x3;
  float aCont = max(max(x1, x2), sel) - min(min(x1, x2), sel);
  sel = selector ? x2 : x5;
  float bCont = max(max(x3, x4), sel) - min(min(x3, x4), sel);
  float ratio = max(aCont, bCont) / (min(aCont, bCont) + 1.0 / 255.0);
  usm *= 1.0 - clamp((ratio - uMinContrastRatio) * uRatioNorm, 0.0, 1.0);
  return y + usm;
}
void main() {
  float dstX = floor(gl_FragCoord.x);
  float dstY = uOutSize.y - 1.0 - floor(gl_FragCoord.y);
  vec2 scale = uInSize / uOutSize;
  float srcX = (0.5 + dstX) * scale.x - 0.5;
  float srcY = (0.5 + dstY) * scale.y - 0.5;
  float fx = srcX - floor(srcX);
  float fy = srcY - floor(srcY);
  float phaseX = floor(fx * 64.0);
  float phaseY = floor(fy * 64.0);
  float bx = floor(srcX) - 2.0;
  float by = floor(srcY) - 2.0;
  float p00 = lumaAt(bx + 0.0, by + 0.0);
  float p01 = lumaAt(bx + 1.0, by + 0.0);
  float p02 = lumaAt(bx + 2.0, by + 0.0);
  float p03 = lumaAt(bx + 3.0, by + 0.0);
  float p04 = lumaAt(bx + 4.0, by + 0.0);
  float p05 = lumaAt(bx + 5.0, by + 0.0);
  float p10 = lumaAt(bx + 0.0, by + 1.0);
  float p11 = lumaAt(bx + 1.0, by + 1.0);
  float p12 = lumaAt(bx + 2.0, by + 1.0);
  float p13 = lumaAt(bx + 3.0, by + 1.0);
  float p14 = lumaAt(bx + 4.0, by + 1.0);
  float p15 = lumaAt(bx + 5.0, by + 1.0);
  float p20 = lumaAt(bx + 0.0, by + 2.0);
  float p21 = lumaAt(bx + 1.0, by + 2.0);
  float p22 = lumaAt(bx + 2.0, by + 2.0);
  float p23 = lumaAt(bx + 3.0, by + 2.0);
  float p24 = lumaAt(bx + 4.0, by + 2.0);
  float p25 = lumaAt(bx + 5.0, by + 2.0);
  float p30 = lumaAt(bx + 0.0, by + 3.0);
  float p31 = lumaAt(bx + 1.0, by + 3.0);
  float p32 = lumaAt(bx + 2.0, by + 3.0);
  float p33 = lumaAt(bx + 3.0, by + 3.0);
  float p34 = lumaAt(bx + 4.0, by + 3.0);
  float p35 = lumaAt(bx + 5.0, by + 3.0);
  float p40 = lumaAt(bx + 0.0, by + 4.0);
  float p41 = lumaAt(bx + 1.0, by + 4.0);
  float p42 = lumaAt(bx + 2.0, by + 4.0);
  float p43 = lumaAt(bx + 3.0, by + 4.0);
  float p44 = lumaAt(bx + 4.0, by + 4.0);
  float p45 = lumaAt(bx + 5.0, by + 4.0);
  float p50 = lumaAt(bx + 0.0, by + 5.0);
  float p51 = lumaAt(bx + 1.0, by + 5.0);
  float p52 = lumaAt(bx + 2.0, by + 5.0);
  float p53 = lumaAt(bx + 3.0, by + 5.0);
  float p54 = lumaAt(bx + 4.0, by + 5.0);
  float p55 = lumaAt(bx + 5.0, by + 5.0);
  vec4 e00 = edgeMap(p11, p12, p13, p21, p22, p23, p31, p32, p33);
  vec4 e01 = edgeMap(p12, p13, p14, p22, p23, p24, p32, p33, p34);
  vec4 e10 = edgeMap(p21, p22, p23, p31, p32, p33, p41, p42, p43);
  vec4 e11 = edgeMap(p22, p23, p24, p32, p33, p34, p42, p43, p44);
  vec4 w = mix(mix(e00, e01, fx), mix(e10, e11, fx), fy);
  float baseWeight = 1.0 - w.x - w.y - w.z - w.w;
  // The scaling taps once per pixel, not once per use.
  float cy0 = coef(phaseY, 0.0).x;
  float cx0 = coef(phaseX, 0.0).x;
  float cy1 = coef(phaseY, 1.0).x;
  float cx1 = coef(phaseX, 1.0).x;
  float cy2 = coef(phaseY, 2.0).x;
  float cx2 = coef(phaseX, 2.0).x;
  float cy3 = coef(phaseY, 3.0).x;
  float cx3 = coef(phaseX, 3.0).x;
  float cy4 = coef(phaseY, 4.0).x;
  float cx4 = coef(phaseX, 4.0).x;
  float cy5 = coef(phaseY, 5.0).x;
  float cx5 = coef(phaseX, 5.0).x;
  float v0 = p00 * cy0 + p10 * cy1 + p20 * cy2 + p30 * cy3 + p40 * cy4 + p50 * cy5;
  float v1 = p01 * cy0 + p11 * cy1 + p21 * cy2 + p31 * cy3 + p41 * cy4 + p51 * cy5;
  float v2 = p02 * cy0 + p12 * cy1 + p22 * cy2 + p32 * cy3 + p42 * cy4 + p52 * cy5;
  float v3 = p03 * cy0 + p13 * cy1 + p23 * cy2 + p33 * cy3 + p43 * cy4 + p53 * cy5;
  float v4 = p04 * cy0 + p14 * cy1 + p24 * cy2 + p34 * cy3 + p44 * cy4 + p54 * cy5;
  float v5 = p05 * cy0 + p15 * cy1 + p25 * cy2 + p35 * cy3 + p45 * cy4 + p55 * cy5;
  float normal = v0 * cx0 + v1 * cx1 + v2 * cx2 + v3 * cx3 + v4 * cx4 + v5 * cx5;
  float opY = normal * baseWeight;
  if (w.x > 0.0) {
    opY += evalPoly6(
      mix(p02, p03, fx), mix(p12, p13, fx), mix(p22, p23, fx),
      mix(p32, p33, fx), mix(p42, p43, fx), mix(p52, p53, fx),
      phaseY) * w.x;
  }
  if (w.y > 0.0) {
    opY += evalPoly6(
      mix(p20, p30, fy), mix(p21, p31, fy), mix(p22, p32, fy),
      mix(p23, p33, fy), mix(p24, p34, fy), mix(p25, p35, fy),
      phaseX) * w.y;
  }
  if (w.z > 0.0) {
    float b45 = 0.5 + 0.5 * (fx - fy);
    float t1 = mix(p21, p12, b45);
    float t3 = mix(p32, p23, b45);
    float t5 = mix(p43, p34, b45);
    b45 -= 0.5;
    bool up = b45 >= 0.0;
    float k = abs(b45);
    float t0 = mix(p11, up ? p02 : p20, k);
    float t2 = mix(p22, up ? p13 : p31, k);
    float t4 = mix(p33, up ? p24 : p42, k);
    float t6 = mix(p44, up ? p35 : p53, k);
    float p45 = fx + fy;
    bool shift = p45 >= 1.0;
    if (shift) p45 -= 1.0;
    opY += (shift
      ? evalPoly6(t1, t2, t3, t4, t5, t6, floor(p45 * 64.0))
      : evalPoly6(t0, t1, t2, t3, t4, t5, floor(p45 * 64.0))) * w.z;
  }
  if (w.w > 0.0) {
    float b135 = 0.5 * (fx + fy);
    float t1 = mix(p31, p42, b135);
    float t3 = mix(p22, p33, b135);
    float t5 = mix(p13, p24, b135);
    b135 -= 0.5;
    bool up = b135 >= 0.0;
    float k = abs(b135);
    float t0 = mix(p41, up ? p52 : p30, k);
    float t2 = mix(p32, up ? p43 : p21, k);
    float t4 = mix(p23, up ? p34 : p12, k);
    float t6 = mix(p14, up ? p25 : p03, k);
    float p135 = 1.0 + (fx - fy);
    bool shift = p135 >= 1.0;
    if (shift) p135 -= 1.0;
    opY += (shift
      ? evalPoly6(t1, t2, t3, t4, t5, t6, floor(p135 * 64.0))
      : evalPoly6(t0, t1, t2, t3, t4, t5, floor(p135 * 64.0))) * w.w;
  }
  vec3 c = texture2D(uTex, vec2(srcX + 0.5, uInSize.y - srcY - 0.5) / uInSize).rgb;
  gl_FragColor = vec4(clamp(c + (opY - lumaOf(c)), 0.0, 1.0), 1.0);
}
