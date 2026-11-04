// ACNet F8B4 by TianZer (ACNetGLSL, MIT, see app/THIRD-PARTY-NOTICE.ACNet.txt), pass 2 of its 11
// convolutions as the filters' stage runs them: head conv 1x8x3x3 part 1.
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
  vec4 result = vec4(-0.0010380931, -1.1788054, 0.024233848, -0.00079728756);
  result += vec4(0.033546112, 0.10980652, 0.097957306, 0.21237579) * lumaAt(-1.0, -1.0);
  result += vec4(-0.21189734, -0.12089546, 0.68326527, -4.2253747) * lumaAt(0.0, -1.0);
  result += vec4(0.19883607, 1.0329657, -0.09688856, 0.25435346) * lumaAt(1.0, -1.0);
  result += vec4(-0.15327626, -0.18092306, 0.74079853, -0.17313564) * lumaAt(-1.0, 0.0);
  result += vec4(5.7319126, -1.1648409, -5.8184586, 4.171934) * lumaAt(0.0, 0.0);
  result += vec4(-5.6207337, 0.9392109, 0.99913746, -0.19774051) * lumaAt(1.0, 0.0);
  result += vec4(-0.024911148, 0.10242643, 0.35410124, -0.060014643) * lumaAt(-1.0, 1.0);
  result += vec4(-0.14164221, 0.13694477, 1.27163, 0.06270146) * lumaAt(0.0, 1.0);
  result += vec4(0.18961844, -0.19659716, 0.44041228, -0.043810427) * lumaAt(1.0, 1.0);
  result = max(result, vec4(0.0)) + vec4(-0.91579723, 0.5282313, 0.3303117, -0.9281135) * min(result, vec4(0.0));
  gl_FragColor = result;
}
