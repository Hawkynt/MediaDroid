// The chroma planes as an mpv user shader's CHROMA texture (MpvShader): Cb (uTex) in red and Cr (uV) in green, at the
// chroma's own size, rows as the planes hold them — mpv's two-component chroma plane, read as libplacebo merges a
// frame's Cb and Cr planes for its hooks.
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uV;
varying vec2 vTex;
void main() {
  gl_FragColor = vec4(texture2D(uTex, vTex).r, texture2D(uV, vTex).r, 0.0, 1.0);
}
