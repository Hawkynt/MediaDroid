// YUV planes to RGB as yuv.frag does, the chroma enlarged by libplacebo's spline36 over six by six samples instead of
// the texture's bilinear filtering (ChromaUpscaling.spline36, mpv's --cscale=spline36). The chroma samples sit centred
// between the luma pixels; columns past the picture's width (the stride's padding) are clamped to its last.
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uU;
uniform sampler2D uV;
uniform mat3 uColor;
uniform float uChroma;
// The chroma planes' texture size in texels (the stride wide), and the picture's chroma width within it.
uniform vec2 uChromaSize;
uniform float uChromaWidth;
// The luma's foot: 16/255 for limited range, 0 for full.
uniform float uFoot;
varying vec2 vTex;

float spline36(float d) {
  float x = abs(d);
  if (x < 1.0) return ((13.0 / 11.0 * x - 453.0 / 209.0) * x - 3.0 / 209.0) * x + 1.0;
  if (x < 2.0) return ((-6.0 / 11.0 * (x - 1.0) + 270.0 / 209.0) * (x - 1.0) - 156.0 / 209.0) * (x - 1.0);
  if (x < 3.0) return ((1.0 / 11.0 * (x - 2.0) - 45.0 / 209.0) * (x - 2.0) + 26.0 / 209.0) * (x - 2.0);
  return 0.0;
}

void main() {
  vec2 at = vec2(vTex.x * uChroma, vTex.y) * uChromaSize - 0.5;
  vec2 base = floor(at);
  vec2 f = at - base;
  vec2 chroma = vec2(0.0);
  float total = 0.0;
  for (int j = -2; j <= 3; j++) {
    float wy = spline36(float(j) - f.y);
    float row = (base.y + float(j) + 0.5) / uChromaSize.y;
    for (int i = -2; i <= 3; i++) {
      float w = wy * spline36(float(i) - f.x);
      float column = (clamp(base.x + float(i), 0.0, uChromaWidth - 1.0) + 0.5) / uChromaSize.x;
      chroma += w * vec2(texture2D(uU, vec2(column, row)).r, texture2D(uV, vec2(column, row)).r);
      total += w;
    }
  }
  vec3 yuv = vec3(texture2D(uTex, vTex).r - uFoot, chroma / total - 128.0 / 255.0);
  gl_FragColor = vec4(uColor * yuv, 1.0);
}
