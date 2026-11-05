// Frame blending: the current frame over the previous one by the weight.
precision mediump float;
uniform sampler2D uTex;
uniform sampler2D uPrevious;
uniform float uWeight;
varying vec2 vTex;
void main() {
  gl_FragColor = vec4(mix(texture2D(uPrevious, vTex).rgb, texture2D(uTex, vTex).rgb, uWeight), 1.0);
}
