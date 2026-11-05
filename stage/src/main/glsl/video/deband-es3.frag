#version 300 es
// Debanding (deband.glsl) in GLSL ES 3.00, with libplacebo's own random numbers: its sh_prng, pcg3d (Jarzynski and
// Olano, JCGT 9(3), 2020) over the pixel's column, its row from the top and the frame's 8-bit index — so a picture
// comes out as libplacebo's does, within 1/255 (tools/shader-check). Texels read by texelFetch, exactly.
precision highp float;
precision highp int;
uniform sampler2D uTex;
uniform float uFrame;
out vec4 fragColor;
#define FRAG_COLOR fragColor
#define FETCH(texel) texelFetch(uTex, ivec2(texel), 0)
uvec3 state;
void seedRandom(float column, float row) { state = uvec3(uvec2(vec2(column, row)), uint(uFrame)); }
vec3 random() {
  state = 1664525u * state + uvec3(1013904223u);
  state.x += state.y * state.z;
  state.y += state.z * state.x;
  state.z += state.x * state.y;
  state ^= state >> 16u;
  state.x += state.y * state.z;
  state.y += state.z * state.x;
  state.z += state.x * state.y;
  return vec3(state) * 1.0 / float(0xFFFFFFFFu);
}
#include "deband.glsl"
