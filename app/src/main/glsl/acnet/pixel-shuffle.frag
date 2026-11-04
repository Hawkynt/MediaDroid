// ACNet's last step (its pixel shuffle, MIT, see conv-1.frag): each of the four channels of the last convolution is
// the luma of one of the four pixels a picture pixel becomes, clamped to 0..1; the colour is the picture's bilinear
// doubling, its own luma swapped for the network's. mpv counts rows downwards: its upper pair of the four (row 0) is
// the upper half here, where v is larger.
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uLast;
uniform vec2 uLastSize;
varying vec2 vTex;
#include "luma.glsl"
void main() {
  vec2 cell = vTex * uLastSize;
  vec2 f = fract(cell);
  vec4 c = texture2D(uLast, (floor(cell) + 0.5) / uLastSize);
  float ix = f.x < 0.5 ? 0.0 : 1.0;
  float iy = f.y < 0.5 ? 1.0 : 0.0;
  float index = iy * 2.0 + ix;
  vec4 pick = vec4(
    index == 0.0 ? 1.0 : 0.0,
    index == 1.0 ? 1.0 : 0.0,
    index == 2.0 ? 1.0 : 0.0,
    index == 3.0 ? 1.0 : 0.0
  );
  float y = clamp(dot(c, pick), 0.0, 1.0);
  vec3 rgb = texture2D(uTex, vTex).rgb;
  gl_FragColor = vec4(clamp(rgb + (y - luma(rgb)), 0.0, 1.0), 1.0);
}
