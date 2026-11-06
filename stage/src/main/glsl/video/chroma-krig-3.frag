#version 300 es
// KrigBilateral (Shiandow, LGPL-3.0-or-later; ChromaUpscaling.krig), last pass: each pixel's chroma kriged from the
// nine chroma samples around it — their covariance from how alike the luma under them is (the second pass's mean and
// variance) and how far apart they lie, the pixel's own luma deciding which of them it belongs with — then YUV to RGB
// as yuv.frag does. vTex is the luma plane's texture position, rows counted from the top; the second pass's rows run
// from the bottom (gl_FragCoord in the passes before).
precision highp float;
precision highp int;
uniform sampler2D uTex;
uniform sampler2D uU;
uniform sampler2D uV;
uniform sampler2D uLow;
uniform mat3 uColor;
// The luma plane's texture width (the stride) and the picture's size, and the chroma's.
uniform float uLumaStride;
uniform vec2 uLumaSize;
uniform vec2 uChromaSize;
uniform float uFoot;
in vec2 vTex;
out vec4 fragColor;

const int N = 8;
const float NOISE = 256.0 / (255.0 * 255.0);
const float RADIUS = 1.5;

void main() {
  ivec2 pixel = ivec2(int(vTex.x * uLumaStride), int(vTex.y * uLumaSize.y));
  ivec2 last = ivec2(uChromaSize) - 1;
  vec2 at = (vec2(pixel) + 0.5) * uChromaSize / uLumaSize - 0.5;
  vec2 centre = floor(at + 0.5);
  vec2 offset = at - centre;
  vec2 coords[N + 1] = vec2[](vec2(-1, -1), vec2(-1, 0), vec2(-1, 1), vec2(0, -1), vec2(0, 1), vec2(1, -1),
    vec2(1, 0), vec2(1, 1), vec2(0, 0));
  vec2 luma[N + 1];
  vec2 chroma[N + 1];
  vec4 total = vec4(0.0);
  for (int i = 0; i <= N; i++) {
    ivec2 s = clamp(ivec2(centre + coords[i]), ivec2(0), last);
    luma[i] = texelFetch(uLow, ivec2(s.x, last.y - s.y), 0).rg;
    chroma[i] = vec2(texelFetch(uU, s, 0).r, texelFetch(uV, s, 0).r);
    vec2 w = clamp(1.5 - abs(coords[i]), 0.0, 1.0);
    total += w.x * w.y * vec4(luma[i].x, luma[i].x * luma[i].x, luma[i].y, 1.0);
  }
  total.xyz /= total.w;
  float localVar = abs(total.y - total.x * total.x) + NOISE;
  float var = localVar + total.z;
  float y = texelFetch(uTex, pixel, 0).r;
  float cov[(N + 1) * (N + 1)];
  for (int i = 0; i <= N; i++) {
    for (int j = i; j <= N; j++) {
      vec2 d = (coords[i] - coords[j]) / RADIUS;
      float dm = luma[i].x - luma[j].x;
      float c = inversesqrt(1.0 + (luma[i].y + luma[j].y) / var) *
        exp(-0.5 * (dm * dm / (localVar + luma[i].y + luma[j].y) + dot(d, d)));
      cov[i * (N + 1) + j] = c;
      cov[j * (N + 1) + i] = c;
    }
  }
  float toPixel[N + 1];
  for (int i = 0; i <= N; i++) {
    vec2 d = (coords[i] - offset) / RADIUS;
    float dm = luma[i].x - y;
    toPixel[i] = inversesqrt(1.0 + luma[i].y / var) * exp(-0.5 * (dm * dm / (localVar + luma[i].y) + dot(d, d)));
  }
  // The system relative to the centre sample, solved by elimination without pivoting, as KrigBilateral solves it.
  float m[N * N];
  float b[N];
  float cc = cov[N * (N + 1) + N];
  for (int i = 0; i < N; i++) {
    b[i] = toPixel[i] - toPixel[N] - cov[i * (N + 1) + N] + cc;
    for (int j = i; j < N; j++) m[i * N + j] = cov[i * (N + 1) + j] - cov[j * (N + 1) + N] - cov[i * (N + 1) + N] + cc;
  }
  for (int k = 0; k < N; k++) {
    for (int i = k + 1; i < N; i++) {
      float f = m[k * N + i] / m[k * N + k];
      b[i] -= b[k] * f;
      for (int j = i; j < N; j++) m[i * N + j] -= m[k * N + j] * f;
    }
  }
  vec2 uv = chroma[N];
  for (int i = N - 1; i >= 0; i--) {
    for (int j = i + 1; j < N; j++) b[i] -= m[i * N + j] * b[j];
    b[i] /= m[i * N + i];
    uv += b[i] * (chroma[i] - chroma[N]);
  }
  vec3 yuv = vec3(y - uFoot, uv - 128.0 / 255.0);
  fragColor = vec4(uColor * yuv, 1.0);
}
