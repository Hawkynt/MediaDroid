// Temporal denoising (TemporalDenoise, FFmpeg's atadenoise over three frames): each value averaged with the same pixel
// in the frame before, where it differs from it by at most threshold A (and B), and then in the frame after on the same
// terms — so still grain averages out and motion is left alone. Each value is judged as its 8-bit step; a frame
// holding finer values (half floats) keeps them, moved by the same step. All three frames are read at the texel's
// centre, the same pixel in each.
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uPrev;
uniform sampler2D uNext;
// FFmpeg's thresholds in 8-bit steps.
uniform float uA;
uniform float uB;
varying vec2 vTex;

float denoised(float x, float before, float after) {
  float sum = x;
  float count = 1.0;
  float left = abs(x - before);
  if (left <= uA && left <= uB) {
    sum += before;
    count += 1.0;
    float right = abs(x - after);
    if (right <= uA && right <= uB) {
      sum += after;
      count += 1.0;
    }
  }
  // C's (sum + count / 2) / count in integers; the small lift keeps an exact quotient from a division a hair low.
  return floor((sum + floor(count / 2.0)) / count + 0.0001);
}

void main() {
  vec3 own = texture2D(uTex, vTex).rgb;
  vec3 x = floor(own * 255.0 + 0.5);
  vec3 before = floor(texture2D(uPrev, vTex).rgb * 255.0 + 0.5);
  vec3 after = floor(texture2D(uNext, vTex).rgb * 255.0 + 0.5);
  vec3 result = vec3(
    denoised(x.r, before.r, after.r), denoised(x.g, before.g, after.g), denoised(x.b, before.b, after.b));
  gl_FragColor = vec4(own + (result - x) / 255.0, 1.0);
}
