// Bilateral filter (Tomasi & Manduchi, ICCV 1998): neighbours weigh by distance and by how alike
// they are, so flat areas and block edges smooth while real edges stay.
precision mediump float;
uniform sampler2D uTex;
uniform vec2 uTexel;
uniform float uRadius;
uniform float uSigmaS2;
uniform float uSigmaR2;
varying vec2 vTex;
void main() {
  vec3 center = texture2D(uTex, vTex).rgb;
  vec3 sum = vec3(0.0);
  float weights = 0.0;
  for (int y = -2; y <= 2; y++) {
    for (int x = -2; x <= 2; x++) {
      vec2 offset = vec2(float(x), float(y));
      if (abs(offset.x) > uRadius || abs(offset.y) > uRadius) continue;
      vec3 neighbour = texture2D(uTex, vTex + offset * uTexel).rgb;
      vec3 d = neighbour - center;
      float w = exp(-dot(offset, offset) / uSigmaS2 - dot(d, d) / uSigmaR2);
      sum += neighbour * w;
      weights += w;
    }
  }
  gl_FragColor = vec4(sum / weights, 1.0);
}
