#version 300 es
// FSR 1's RCAS (see fsr-rcas.frag) in GLSL ES 3.00: AMD's approximate reciprocal on the floats' own bits.
precision highp float;
uniform sampler2D uTex;
uniform vec2 uTexel;
uniform float uSharp;
uniform float uGrain;
uniform float uSeed;
in vec2 vTex;
out vec4 fragColor;
#define FRAG_COLOR fragColor
#define texture2D texture
#include "ffx-approx-es3.glsl"
#include "fsr-rcas.glsl"
