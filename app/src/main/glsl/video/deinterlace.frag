// Deinterlacing; uMode is Deinterlace's ordinal. Blend: every line with half of each neighbour. Bob:
// the odd lines — the second field — rebuilt from the lines above and below. Auto: the same, but
// only where a pixel combs — it differs from both neighbours in the same direction (their
// luma-weighted product is positive beyond noise), the motion artefact of two fields woven
// together; the first field always stays, or the comb would only be turned over.
precision highp float;
uniform sampler2D uTex;
uniform vec2 uTexel;
uniform int uMode;
varying vec2 vTex;
void main() {
  vec3 c = texture2D(uTex, vTex).rgb;
  vec3 up = texture2D(uTex, vTex + vec2(0.0, uTexel.y)).rgb;
  vec3 down = texture2D(uTex, vTex - vec2(0.0, uTexel.y)).rgb;
  vec3 rebuilt = (up + down) * 0.5;
  if (uMode == 2) {
    gl_FragColor = vec4((2.0 * c + up + down) * 0.25, 1.0);
  } else {
    bool second = mod(floor(gl_FragCoord.y), 2.0) >= 1.0;
    float comb = dot((c - up) * (c - down), vec3(0.2126, 0.7152, 0.0722));
    bool rebuild = second && (uMode == 3 || comb > 0.003);
    gl_FragColor = vec4(rebuild ? rebuilt : c, 1.0);
  }
}
