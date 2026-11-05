// HDR peak detection's measurement, as libplacebo's pl_shader_detect_peak takes it (src/shaders/colorspace.c, 7.360,
// LGPL-2.1-or-later) in fragments instead of compute work groups: one output pixel a block of 16 × 16 of the picture,
// each pixel's luminance in 14-bit PQ (BT.2020's luma of the linear light, darker than 1 % faded out, black left out),
// the block's brightest written as two bytes in red and green, its average (black pixels not counted) in blue and
// alpha. core/player's PeakDetect reads them back and smooths over frames as libplacebo's processor side does.
// Blocks at the edges hold what is left of the picture.
precision highp float;
uniform sampler2D uTex;
uniform vec2 uSize;  // the picture's size in pixels
#include "hdr.glsl"
float luminance(vec3 coded) {
  vec3 color = linearize(coded);
  float y = dot(uLuma, color) * (203.0 / 10000.0);
  y = pow(clamp(y, 0.0, 1.0), PQ_M1);
  y = (PQ_C1 + PQ_C2 * y) / (1.0 + PQ_C3 * y);
  y = pow(y, PQ_M2);
  y *= smoothstep(0.0, 0.01, y);
  return floor(16383.0 * y);
}
void main() {
  vec2 corner = floor(gl_FragCoord.xy) * 16.0;
  float sum = 0.0;
  float peak = 0.0;
  float lit = 0.0;
  for (int j = 0; j < 16; j++) {
    for (int i = 0; i < 16; i++) {
      vec2 at = corner + vec2(float(i), float(j));
      if (at.x < uSize.x && at.y < uSize.y) {
        float y = luminance(texture2D(uTex, (at + 0.5) / uSize).rgb);
        sum += y;
        peak = max(peak, y);
        lit += step(0.5, y);
      }
    }
  }
  // The average as a whole number, as libplacebo's unsigned division: a quotient a float division left a hair off its
  // integer is put right.
  float average = 0.0;
  if (lit > 0.0) {
    average = floor(sum / lit);
    if ((average + 1.0) * lit <= sum) average += 1.0;
    if (average * lit > sum) average -= 1.0;
  }
  gl_FragColor = vec4(floor(peak / 256.0), mod(peak, 256.0), floor(average / 256.0), mod(average, 256.0)) / 255.0;
}
