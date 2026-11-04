// ACNet F8B4 by TianZer (ACNetGLSL, MIT, see app/THIRD-PARTY-NOTICE.ACNet.txt), pass 1 of its 11
// convolutions as the filters' stage runs them: head conv 1x8x3x3 part 0.
// The weights are ACNet's own, copied from its mpv shader acnet_f8b4.glsl; only the vertical offsets are turned
// round, as the stage's textures count rows upwards.
precision highp float;
uniform sampler2D uTex;
uniform vec2 uTexel;
varying vec2 vTex;
#include "luma.glsl"
// mpv counts rows downwards, the textures here upwards: a step of y down is a step of v down.
float lumaAt(float x, float y) { return luma(texture2D(uTex, vTex + vec2(x, -y) * uTexel).rgb); }
void main() {
  vec4 result = vec4(-0.9520906, 0.013774246, -0.4940886, 0.13375327);
  result += vec4(-0.0014878281, -0.7632004, 0.19272894, 0.46537745) * lumaAt(-1.0, -1.0);
  result += vec4(-1.3460438, 0.18894365, -0.0490235, -0.9890594) * lumaAt(0.0, -1.0);
  result += vec4(0.32281575, 1.4070463, 0.37536892, -0.37987557) * lumaAt(1.0, -1.0);
  result += vec4(-0.65934813, 3.253703, -0.10119856, -1.7713721) * lumaAt(-1.0, 0.0);
  result += vec4(5.563184, -5.9529715, -2.4931717, 7.4821305) * lumaAt(0.0, 0.0);
  result += vec4(-2.1096349, 0.7543952, -0.3000822, -2.676932) * lumaAt(1.0, 0.0);
  result += vec4(-0.11039619, -0.27639085, 0.14380127, -0.094028816) * lumaAt(-1.0, 1.0);
  result += vec4(-1.2408149, 1.8090338, 0.122120835, -1.6043427) * lumaAt(0.0, 1.0);
  result += vec4(0.53610414, -0.3941251, 0.46432167, -0.23128629) * lumaAt(1.0, 1.0);
  result = max(result, vec4(0.0)) + vec4(0.25083545, -0.85913664, 0.38589597, 0.73232096) * min(result, vec4(0.0));
  gl_FragColor = result;
}
