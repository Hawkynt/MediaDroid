// Lanczos-3 (a = 3), 6 × 6 taps around the position; the result is pulled halfway back into the range
// of the four nearest pixels, which holds back most of the ringing Lanczos leaves at hard edges.
// Bringing a picture down (see reduced and Downscaler): uKernel is the Downscaler's ordinal. Below
// Bicubic one sample, the texture's own filtering doing Nearest or Bilinear; from Bicubic on every pixel
// within the kernel's reach along uDir, weighed by Keys' cubic (a = -0.5), Lanczos-3, or by how much of
// it the new pixel covers (Area), the edge pixels repeated beyond the picture.
precision highp float;
uniform sampler2D uTex;
uniform vec2 uInSize;
uniform vec2 uDir;
uniform float uScale;
uniform float uRadius;
uniform int uKernel;
varying vec2 vTex;
const float PI = 3.14159265;
float sinc(float x) {
  if (abs(x) < 1e-4) return 1.0;
  float p = PI * x;
  return sin(p) / p;
}
void main() {
  if (uKernel < 3) {
    gl_FragColor = texture2D(uTex, vTex);
    return;
  }
  float length = dot(uInSize, uDir);
  float along = dot(vTex * uInSize, uDir);
  float reach = uRadius * uScale;
  float first = floor(along - reach);
  vec2 across = vTex * (vec2(1.0) - uDir);
  vec4 sum = vec4(0.0);
  float total = 0.0;
  for (int k = 0; k < 64; k++) {
    float i = first + float(k);
    if (i > along + reach) break;
    float x = (i + 0.5 - along) / uScale;
    float a = abs(x);
    float w;
    if (uKernel == 3) {
      w = a < 1.0
        ? (1.5 * a - 2.5) * a * a + 1.0
        : (a < 2.0 ? ((-0.5 * a + 2.5) * a - 4.0) * a + 2.0 : 0.0);
    } else if (uKernel == 4) {
      w = a < 3.0 ? sinc(x) * sinc(x / 3.0) : 0.0;
    } else {
      w = max(0.0, min(i + 1.0, along + 0.5 * uScale) - max(i, along - 0.5 * uScale));
    }
    float at = clamp(i + 0.5, 0.5, length - 0.5) / length;
    sum += w * texture2D(uTex, across + uDir * at);
    total += w;
  }
  gl_FragColor = sum / total;
}
