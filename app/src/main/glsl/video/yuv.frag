// YUV planes to RGB, as Media3's VideoDecoderGLSurfaceView does (Apache-2.0): luma less its 16/256
// foot, chroma about its middle, through the colour space's matrix (BT601, BT709, BT2020 below).
precision mediump float;
uniform sampler2D uTex;
uniform sampler2D uU;
uniform sampler2D uV;
uniform mat3 uColor;
uniform float uChroma;
varying vec2 vTex;
void main() {
  vec2 c = vec2(vTex.x * uChroma, vTex.y);
  vec3 yuv = vec3(
    texture2D(uTex, vTex).r - 0.0625,
    texture2D(uU, c).r - 0.5,
    texture2D(uV, c).r - 0.5
  );
  gl_FragColor = vec4(uColor * yuv, 1.0);
}
