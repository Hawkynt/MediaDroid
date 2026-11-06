// Dithering to the screen's 8 bits (dither.glsl) in GLSL ES 1.00, for graphics chips without ES 3: the picture and the
// blue-noise matrix read at their texels' centres, which sampling returns as they are, linear or nearest.
// The samplers are highp, as in dither-es3.frag.
precision highp float;
uniform highp sampler2D uTex;
uniform highp sampler2D uNoise;
uniform vec2 uSize;
#define FRAG_COLOR gl_FragColor
#define FETCH(texel) texture2D(uTex, ((texel) + 0.5) / uSize)
#define RANK(cell) texture2D(uNoise, ((cell) + 0.5) / 64.0)
#include "dither.glsl"
