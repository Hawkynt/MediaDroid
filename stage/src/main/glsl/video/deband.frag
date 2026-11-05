// Debanding (deband.glsl) in GLSL ES 1.00, for graphics chips without ES 3: no unsigned integers for libplacebo's pcg3d,
// so the random numbers are Dave Hoskins' hash without sine (hash33, MIT) over the pixel, the frame and the call —
// the same filter with other dice, so not libplacebo's picture to the byte; deband-es3.frag is, where it can run.
// Texels read at their centres, which the stage's linear sampling returns as they are.
precision highp float;
uniform sampler2D uTex;
uniform float uFrame;
#define FRAG_COLOR gl_FragColor
#define FETCH(texel) texture2D(uTex, ((texel) + 0.5) / uSize)
vec3 state;
void seedRandom(float column, float row) { state = vec3(column, row, uFrame); }
vec3 random() {
  state.z += 256.0;
  vec3 p3 = fract(state * vec3(0.1031, 0.1030, 0.0973));
  p3 += dot(p3, p3.yxz + 33.33);
  return fract((p3.xxy + p3.yxx) * p3.zyx);
}
#include "deband.glsl"
