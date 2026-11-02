// Luma for reading back, four values to a texel: texel (u, v) holds columns 4u .. 4u + 3 (in steps of
// uStep.x) of row v (in steps of uStep.y), rows counted from the top, with the weights the decoder's
// colour went through (BT.601 up to 576 lines, BT.709 above).
precision highp float;
uniform sampler2D uTex;
uniform vec2 uSize;
uniform vec2 uStep;
uniform bool uSd;
varying vec2 vTex;
float luma(float x, float y) {
  vec3 c = texture2D(uTex, vec2(x + 0.5, uSize.y - y - 0.5) / uSize).rgb;
  return uSd ? dot(c, vec3(0.299, 0.587, 0.114)) : dot(c, vec3(0.2126, 0.7152, 0.0722));
}
void main() {
  float x = floor(gl_FragCoord.x) * 4.0 * uStep.x;
  float y = floor(gl_FragCoord.y) * uStep.y;
  gl_FragColor = vec4(
    luma(x, y), luma(x + uStep.x, y), luma(x + 2.0 * uStep.x, y), luma(x + 3.0 * uStep.x, y));
}
