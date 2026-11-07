// Dot crawl and rainbows on a PAL capture (DotCrawl.applyPal): a temporal comb filter. PAL's subcarrier comes back in
// phase only every four frames; the frame two before (uEarlier) carries the opposite residue to the frame shown, the
// frames either side (uPrev, uNext) the opposite to each other. Where earlier + shown and before + after agree — half
// their difference is motion, the largest over the channels and the neighbours left and right — the mean
// (earlier + shown) / 2 cancels the residue; taken in full up to uThreshold (8-bit steps) of motion, in part up to
// twice it. Read at texel centres, nearest.
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uEarlier;
uniform sampler2D uPrev;
uniform sampler2D uNext;
uniform vec2 uTexel;
uniform float uThreshold;
varying vec2 vTex;

float motionAt(vec2 at) {
  vec3 d = abs(texture2D(uEarlier, at).rgb + texture2D(uTex, at).rgb - texture2D(uPrev, at).rgb
    - texture2D(uNext, at).rgb) * 127.5;
  return max(d.r, max(d.g, d.b));
}

void main() {
  float motion = max(motionAt(vTex), max(motionAt(vTex - vec2(uTexel.x, 0.0)), motionAt(vTex + vec2(uTexel.x, 0.0))));
  float share = clamp(2.0 - motion / uThreshold, 0.0, 1.0);
  vec3 shown = texture2D(uTex, vTex).rgb;
  vec3 combed = (texture2D(uEarlier, vTex).rgb + shown) / 2.0;
  gl_FragColor = vec4(mix(shown, combed, share), 1.0);
}
