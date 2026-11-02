// Film's weave: the matched frame with the rows uRows (0 even, 1 odd, from the top; -1 none) taken
// from the frame its other field comes from.
precision mediump float;
uniform sampler2D uTex;
uniform sampler2D uOther;
uniform vec2 uSize;
uniform float uRows;
varying vec2 vTex;
void main() {
  vec2 p = gl_FragCoord.xy / uSize;
  float y = uSize.y - 1.0 - floor(gl_FragCoord.y);
  bool other = abs(mod(y, 2.0) - uRows) < 0.5;
  gl_FragColor = vec4((other ? texture2D(uOther, p) : texture2D(uTex, p)).rgb, 1.0);
}
