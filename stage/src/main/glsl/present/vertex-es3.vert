#version 300 es
// The quad the picture window (the desktop's PictureWindow) draws a texture on: four corners from gl_VertexID, no
// buffers, filling the viewport; vTex counted from the bottom left as OpenGL counts a texture's rows.
out vec2 vTex;
void main() {
  vec2 corner = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
  vTex = corner;
  gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
}
