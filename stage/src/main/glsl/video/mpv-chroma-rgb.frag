// An mpv user shader's CHROMA put back (MpvShader): the Y plane (uTex) and the hooked chroma (uChroma: Cb in red, Cr
// in green) read at the same place — the chroma bilinearly where the passes left it smaller than the picture, its
// samples centred as the stage's planes are read — and made RGB by uColor, as yuv.frag does.
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uChroma;
uniform mat3 uColor;
// The luma's foot: 16/255 for limited range, 0 for full.
uniform float uFoot;
varying vec2 vTex;
void main() {
  vec3 yuv = vec3(texture2D(uTex, vTex).r - uFoot, texture2D(uChroma, vTex).rg - 128.0 / 255.0);
  gl_FragColor = vec4(uColor * yuv, 1.0);
}
