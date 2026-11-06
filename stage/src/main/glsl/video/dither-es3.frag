#version 300 es
// Dithering to the screen's 8 bits (dither.glsl) in GLSL ES 3.00: the picture and the blue-noise matrix read by
// texelFetch, exactly. The samplers are highp: by default ES gives them lowp, and Mesa then reads a float frame in
// half floats, which put a 16-bit source a level off where it sits on one (tools/shader-check).
precision highp float;
uniform highp sampler2D uTex;
uniform highp sampler2D uNoise;
out vec4 fragColor;
#define FRAG_COLOR fragColor
#define FETCH(texel) texelFetch(uTex, ivec2(texel), 0)
#define RANK(cell) texelFetch(uNoise, ivec2(cell), 0)
#include "dither.glsl"
