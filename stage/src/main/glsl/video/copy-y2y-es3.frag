#version 300 es
// An HDR frame from the device's decoder, taken as the decoder wrote it: GL_EXT_YUV_target hands over the YUV itself
// (10-bit, limited range, BT.2020), which this turns into the coded R'G'B' the tone mapping reads (tonemap.frag) —
// what samplerExternalOES would make of a 10-bit HDR buffer is left to each driver, as Media3 notes for its own HDR
// path (DefaultShaderProgram, Apache-2.0), whose offsets this takes. The matrix is YuvMatrix.toRgbTenBit's.
#extension GL_OES_EGL_image_external_essl3 : require
#extension GL_EXT_YUV_target : require
precision highp float;
uniform __samplerExternal2DY2YEXT uTex;
uniform mat3 uColor;
in vec2 vTex;
out vec4 fragColor;
void main() {
  vec3 yuv = texture(uTex, vTex).xyz - vec3(64.0 / 1024.0, 512.0 / 1024.0, 512.0 / 1024.0);
  fragColor = vec4(uColor * yuv, 1.0);
}
