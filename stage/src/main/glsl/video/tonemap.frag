// HDR on a screen without HDR: libplacebo's pl_shader_color_map_ex (src/shaders/colorspace.c, 7.360,
// LGPL-2.1-or-later) with its BT.2390 curve (src/tone_mapping.c) and the hard clip into BT.709, as core/player's ToneMap
// converts it. The coded PQ or HLG BT.2020 values are made linear (hdr.glsl), taken into IPT with the intensity in PQ,
// the intensity put through ITU-R BT.2390's EETF from the source's peak (measured: PeakDetect) to the screen's, colour
// held back where the intensity drops, and the result coded for BT.709 by BT.1886. libplacebo reads its curve from a
// 256-entry table; this computes it — within 1/255 of libplacebo (tools/shader-check). The uniforms are ToneMap.Pass's.
precision highp float;
uniform sampler2D uTex;
varying vec2 vTex;
#include "hdr.glsl"
uniform mat3 uRgbToLms;  // BT.2020 RGB to IPT's LMS
uniform mat3 uLmsToIpt;  // Ebner and Fairchild's
uniform mat3 uIptToLms;
uniform mat3 uLmsToRgb;  // LMS to BT.709 RGB
uniform vec4 uCurve;     // the source's black and peak (PQ); the screen's, relative to the source's range
uniform vec3 uKnee;      // where the roll-off starts (relative), the black lift's power, the gain after it
uniform vec2 uOutput;    // the screen's black and peak (PQ)
uniform vec2 uBt1886;    // BT.1886's 1/a and b
float bt2390(float pq) {
  float x = (clamp(pq, uCurve.x, uCurve.y) - uCurve.x) / (uCurve.y - uCurve.x);
  float knee = uKnee.x;
  if (knee < 1.0) {
    float tb = (x - knee) / (1.0 - knee);
    float tb2 = tb * tb;
    float tb3 = tb2 * tb;
    float pb = (2.0 * tb3 - 3.0 * tb2 + 1.0) * knee + (tb3 - 2.0 * tb2 + tb) * (1.0 - knee) +
               (-2.0 * tb3 + 3.0 * tb2) * uCurve.w;
    x = x < knee ? x : pb;
  }
  if (x < 1.0) {
    x += uCurve.z * pow(1.0 - x, uKnee.y);
    x = uKnee.z * (x - uCurve.z) + uCurve.z;
  }
  return clamp(x * (uCurve.y - uCurve.x) + uCurve.x, uOutput.x, uOutput.y);
}
void main() {
  vec3 color = linearize(texture2D(uTex, vTex).rgb);
  vec3 lms = uRgbToLms * color;
  vec3 lmspq = 0.020300 * lms;
  lmspq = pow(max(lmspq, 0.0), vec3(PQ_M1));
  lmspq = (vec3(PQ_C1) + PQ_C2 * lmspq) / (vec3(1.0) + PQ_C3 * lmspq);
  lmspq = pow(lmspq, vec3(PQ_M2));
  vec3 ipt = uLmsToIpt * lmspq;
  float original = ipt.x;
  ipt.x = bt2390(ipt.x);
  // Colour held back where the intensity drops, the more the further down (libplacebo's hull).
  vec2 hull = vec2(original, ipt.x);
  hull = ((hull - 6.0) * hull + 9.0) * hull;
  ipt.yz *= min(original / ipt.x, hull.y / hull.x);
  lmspq = uIptToLms * ipt;
  lms = pow(max(lmspq, 0.0), vec3(1.0 / PQ_M2));
  lms = max(lms - vec3(PQ_C1), 0.0) / (vec3(PQ_C2) - PQ_C3 * lms);
  lms = pow(lms, vec3(1.0 / PQ_M1));
  lms *= 49.261086;
  // Outside BT.709 is clipped where the frame stores the result.
  color = max(uLmsToRgb * lms, 0.0);
  color = pow(uBt1886.x * color, vec3(1.0 / 2.4)) - vec3(uBt1886.y);
  gl_FragColor = vec4(color, 1.0);
}
