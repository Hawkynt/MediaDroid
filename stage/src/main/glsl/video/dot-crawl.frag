// Dot crawl and rainbows (DotCrawl): a temporal comb filter for composite captures. Where the frames before and
// after (uPrev, uNext: the same subcarrier phase, opposite to the frame shown) agree, the mean (before + 2 x shown +
// after) / 4 cancels the residue that flips sign every frame; the comb is taken in full up to uThreshold (8-bit steps)
// of motion, in part up to twice it. Motion is the largest difference over the channels and the neighbours left and
// right. Read at texel centres, nearest.
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uPrev;
uniform sampler2D uNext;
uniform vec2 uTexel;
uniform float uThreshold;
varying vec2 vTex;

float motionAt(vec2 at) {
  vec3 d = abs(texture2D(uNext, at).rgb - texture2D(uPrev, at).rgb) * 255.0;
  return max(d.r, max(d.g, d.b));
}

void main() {
  float motion = max(motionAt(vTex), max(motionAt(vTex - vec2(uTexel.x, 0.0)), motionAt(vTex + vec2(uTexel.x, 0.0))));
  float share = clamp(2.0 - motion / uThreshold, 0.0, 1.0);
  vec3 shown = texture2D(uTex, vTex).rgb;
  vec3 combed = (texture2D(uPrev, vTex).rgb + 2.0 * shown + texture2D(uNext, vTex).rgb) / 4.0;
  gl_FragColor = vec4(mix(shown, combed, share), 1.0);
}
