// An mpv user shader's LUMA put back (MpvShader): the picture (uTex, read bilinearly at the luma's size where the
// passes enlarged it) with its Y replaced by the passes' (uLuma) — every channel moved by the change in Y, which keeps
// its Cb and Cr: a full-range BT.709 YCbCr picture with a new Y plane, its chroma enlarged bilinearly.
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uLuma;
varying vec2 vTex;
void main() {
  vec3 rgb = texture2D(uTex, vTex).rgb;
  float y = texture2D(uLuma, vTex).r;
  gl_FragColor = vec4(rgb + (y - dot(rgb, vec3(0.2126, 0.7152, 0.0722))), 1.0);
}
