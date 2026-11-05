// AMD FidelityFX Super Resolution 1, RCAS (robust contrast-adaptive sharpening), from ffx_fsr1.h
// (MIT): the strongest negative lobe that cannot clip, limited to FSR_RCAS_LIMIT = 0.25 - 1/16,
// times the strength, resolved with AMD's own approximate reciprocal (ffx-approx.glsl).
// GLSL ES 1.00, for graphics chips without ES 3; fsr-rcas-es3.frag reads the floats' bits directly where it can.
precision highp float;
uniform sampler2D uTex;
uniform vec2 uTexel;
uniform float uSharp;
uniform float uGrain;
uniform float uSeed;
varying vec2 vTex;
#define FRAG_COLOR gl_FragColor
#include "ffx-approx.glsl"
#include "fsr-rcas.glsl"
