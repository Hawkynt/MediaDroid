// YUV planes to RGB, as Media3's VideoDecoderGLSurfaceView does (Apache-2.0): luma less its 16/255
// foot, chroma about its middle, through the colour space's matrix (YuvMatrix.toRgb).
// highp: the chroma coordinate is computed here, and half floats would put it up to a pixel off at 1920 wide.
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uU;
uniform sampler2D uV;
uniform mat3 uColor;
uniform float uChroma;
varying vec2 vTex;
void main() {
  vec2 c = vec2(vTex.x * uChroma, vTex.y);
  vec3 yuv = vec3(
    // The planes hold n / 255: the foot is 16 / 255 and the middle 128 / 255 — not 16 / 256 and 0.5, which tinted
    // every grey a step towards blue.
    texture2D(uTex, vTex).r - 16.0 / 255.0,
    texture2D(uU, c).r - 128.0 / 255.0,
    texture2D(uV, c).r - 128.0 / 255.0
  );
  gl_FragColor = vec4(uColor * yuv, 1.0);
}
