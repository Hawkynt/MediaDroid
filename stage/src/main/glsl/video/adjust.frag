// The video's colour correction, the stage's last pass onto the screen: saturation around grey with the Rec. 709
// luma weights, contrast around mid grey plus brightness, then the true gamma curve — the order and weights of
// PictureAdjustment.shaderUniforms.
precision mediump float;
uniform sampler2D uTex;
uniform float uBrightness;
uniform float uContrast;
uniform float uSaturation;
uniform float uInverseGamma;
varying vec2 vTex;
void main() {
  vec3 rgb = texture2D(uTex, vTex).rgb;
  float luma = dot(rgb, vec3(0.213, 0.715, 0.072));
  rgb = mix(vec3(luma), rgb, uSaturation);
  rgb = (rgb - 0.5) * uContrast + 0.5 + uBrightness;
  rgb = pow(clamp(rgb, 0.0, 1.0), vec3(uInverseGamma));
  gl_FragColor = vec4(rgb, 1.0);
}
