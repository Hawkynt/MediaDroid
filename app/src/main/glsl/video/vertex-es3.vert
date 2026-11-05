#version 300 es
// vertex.vert in GLSL ES 3.00, for the GLSL ES 3.00 fragment passes (which it has to match).
in vec4 aPosition;
in vec4 aTexCoord;
uniform mat4 uTexMatrix;
out vec2 vTex;
void main() {
  gl_Position = aPosition;
  vTex = (uTexMatrix * aTexCoord).xy;
}
