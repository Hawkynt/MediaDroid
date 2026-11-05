#version 300 es
// FSR 1's EASU (see fsr-easu.frag) in GLSL ES 3.00: AMD's approximate reciprocals on the floats' own bits, as
// ffx_a.h computes them (ffx-approx-es3.glsl) — the same results as the GLSL ES 1.00 emulation, at a fraction of
// its cost.
precision highp float;
uniform sampler2D uTex;
uniform vec2 uInSize;
in vec2 vTex;
out vec4 fragColor;
#define FRAG_COLOR fragColor
#define texture2D texture
#include "ffx-approx-es3.glsl"
#include "fsr-easu.glsl"
