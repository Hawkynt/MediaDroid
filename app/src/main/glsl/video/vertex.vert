attribute vec4 aPosition;
attribute vec4 aTexCoord;
uniform mat4 uTexMatrix;
varying vec2 vTex;
void main() {
  gl_Position = aPosition;
  vTex = (uTexMatrix * aTexCoord).xy;
}
