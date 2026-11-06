// The video's colour correction, the stage's last pass onto the screen: saturation around grey with the Rec. 709
// luma weights, contrast around mid grey plus brightness, then the true gamma curve — the order and weights of
// PictureAdjustment.shaderUniforms. Each point of the screen first finds the point of the picture it shows
// (ScreenWarp.source): where uWarped is set through the projective map uWarp — a projector's mirror and flip, the
// keystone's trapezoid, the picture's place on the screen — black outside the picture; then, shown panoramic
// (uPanorama above 1), across it the column Panorama.source gives: the middle kept in shape, the sides stretched.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTex;
uniform float uBrightness;
uniform float uContrast;
uniform float uSaturation;
uniform float uInverseGamma;
uniform float uPanorama;
uniform mat3 uWarp;
uniform float uWarped;
varying vec2 vTex;

// Panorama.source: s + c·s³/3 = ratio·(2x − 1) solved for s by Newton's method from the even share, which lands
// within 1e-7 of Cardano's root in three steps for every ratio up to 4/3; the fourth is spare.
float panoramaSource(float x) {
  float c = 3.0 * (uPanorama - 1.0);
  float target = uPanorama * (2.0 * x - 1.0);
  float s = 2.0 * x - 1.0;
  for (int i = 0; i < 4; i++) {
    s -= (s + c * s * s * s / 3.0 - target) / (1.0 + c * s * s);
  }
  return (s + 1.0) / 2.0;
}

void main() {
  vec2 at = vTex;
  if (uWarped > 0.5) {
    vec3 mapped = uWarp * vec3(at, 1.0);
    at = mapped.xy / mapped.z;
    // ScreenWarp.source's tolerance: a point on the picture's edge stays on it however the division rounds.
    if (mapped.z <= 0.0 || any(lessThan(at, vec2(-1e-6))) || any(greaterThan(at, vec2(1.0 + 1e-6)))) {
      gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
      return;
    }
    at = clamp(at, 0.0, 1.0);
  }
  if (uPanorama > 1.0) at.x = panoramaSource(at.x);
  vec3 rgb = texture2D(uTex, at).rgb;
  float luma = dot(rgb, vec3(0.213, 0.715, 0.072));
  rgb = mix(vec3(luma), rgb, uSaturation);
  rgb = (rgb - 0.5) * uContrast + 0.5 + uBrightness;
  rgb = pow(clamp(rgb, 0.0, 1.0), vec3(uInverseGamma));
  gl_FragColor = vec4(rgb, 1.0);
}
