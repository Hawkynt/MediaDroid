// HDR's coded values made linear, relative to SDR white (203 nits), as libplacebo's pl_shader_linearize does it
// (src/shaders/colorspace.c, 7.360, LGPL-2.1-or-later) — core/player's ToneMap converts it, and computes every uniform
// here as libplacebo hands it to its shader. Shared by tonemap.frag and peak.frag.
// PQ (SMPTE ST 2084, HDR10): absolute, 10 000 nits at 1. HLG (ITU-R BT.2100): its inverse OETF lifted to the screen's
// black, then the OOTF of a 1000-nit screen (gamma 1.2) on BT.2020's luma.
uniform float uHlg;     // 0 for PQ, 1 for HLG
uniform vec2 uHlgLift;  // HLG: 1 - b and b, the black lift
uniform vec2 uHlgOotf;  // HLG: the screen's peak and its gamma less one
uniform vec3 uLuma;     // BT.2020's luma weights
// ST 2084's and BT.2100's constants as libplacebo prints them into its shaders (six decimals).
const float PQ_M1 = 0.159302;
const float PQ_M2 = 78.843750;
const float PQ_C1 = 0.835938;
const float PQ_C2 = 18.851562;
const float PQ_C3 = 18.687500;
vec3 linearize(vec3 color) {
  color = max(color, 0.0);
  if (uHlg < 0.5) {
    color = pow(color, vec3(1.0 / PQ_M2));
    color = max(color - vec3(PQ_C1), 0.0) / (vec3(PQ_C2) - vec3(PQ_C3) * color);
    return pow(color, vec3(1.0 / PQ_M1)) * vec3(49.261084);
  }
  color = uHlgLift.x * color + vec3(uHlgLift.y);
  // GLSL ES 1.00 has no mix by a bvec: the branch above 0.5 picked by a 0 or 1, exactly (a float mix can land a hair
  // off either value).
  vec3 high = vec3(1.0) - step(color, vec3(0.5));
  color = high * (exp((color - vec3(0.559911)) * vec3(1.0 / 0.178833)) + vec3(0.284669)) +
          (vec3(1.0) - high) * (vec3(4.0) * color * color);
  color *= 1.0 / 12.0;
  return color * (uHlgOotf.x * pow(max(dot(uLuma, color), 0.0), uHlgOotf.y));
}
