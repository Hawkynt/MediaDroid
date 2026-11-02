// AMD FidelityFX Super Resolution 1, EASU (edge-adaptive spatial upsampling), from ffx_fsr1.h
// (MIT, see THIRD-PARTY-NOTICE.FidelityFX-FSR.txt): the 12 texels around 'f' read one by one where
// AMD gathers them, the approximate reciprocals written exactly. Taps, as in the original:
//     b c
//   e f g h
//   i j k l
//     n o
precision highp float;
uniform sampler2D uTex;
uniform vec2 uInSize;
varying vec2 vTex;
vec2 fp;
vec3 tex(float x, float y) { return texture2D(uTex, (fp + vec2(x, y) + 0.5) / uInSize).rgb; }
float luma2(vec3 c) { return c.b * 0.5 + (c.r * 0.5 + c.g); }
void setF(inout vec2 dir, inout float len, float w, float lA, float lB, float lC, float lD, float lE) {
  float dc = lD - lC;
  float cb = lC - lB;
  float lenX = 1.0 / max(max(abs(dc), abs(cb)), 1e-6);
  float dirX = lD - lB;
  dir.x += dirX * w;
  lenX = clamp(abs(dirX) * lenX, 0.0, 1.0);
  lenX *= lenX;
  len += lenX * w;
  float ec = lE - lC;
  float ca = lC - lA;
  float lenY = 1.0 / max(max(abs(ec), abs(ca)), 1e-6);
  float dirY = lE - lA;
  dir.y += dirY * w;
  lenY = clamp(abs(dirY) * lenY, 0.0, 1.0);
  lenY *= lenY;
  len += lenY * w;
}
void tap(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len, float lob, float clp, vec3 c) {
  vec2 v = vec2(off.x * dir.x + off.y * dir.y, off.x * -dir.y + off.y * dir.x) * len;
  float d2 = min(dot(v, v), clp);
  float wB = 0.4 * d2 - 1.0;
  float wA = lob * d2 - 1.0;
  wB *= wB;
  wA *= wA;
  wB = 1.5625 * wB - 0.5625;
  float w = wB * wA;
  aC += c * w;
  aW += w;
}
void main() {
  vec2 pp = vTex * uInSize - 0.5;
  fp = floor(pp);
  pp -= fp;
  vec3 b = tex(0.0, -1.0); vec3 c = tex(1.0, -1.0);
  vec3 e = tex(-1.0, 0.0); vec3 f = tex(0.0, 0.0); vec3 g = tex(1.0, 0.0); vec3 h = tex(2.0, 0.0);
  vec3 i = tex(-1.0, 1.0); vec3 j = tex(0.0, 1.0); vec3 k = tex(1.0, 1.0); vec3 l = tex(2.0, 1.0);
  vec3 n = tex(0.0, 2.0); vec3 o = tex(1.0, 2.0);
  float bL = luma2(b); float cL = luma2(c); float eL = luma2(e); float fL = luma2(f);
  float gL = luma2(g); float hL = luma2(h); float iL = luma2(i); float jL = luma2(j);
  float kL = luma2(k); float lL = luma2(l); float nL = luma2(n); float oL = luma2(o);
  vec2 dir = vec2(0.0);
  float len = 0.0;
  setF(dir, len, (1.0 - pp.x) * (1.0 - pp.y), bL, eL, fL, gL, jL);
  setF(dir, len, pp.x * (1.0 - pp.y), cL, fL, gL, hL, kL);
  setF(dir, len, (1.0 - pp.x) * pp.y, fL, iL, jL, kL, nL);
  setF(dir, len, pp.x * pp.y, gL, jL, kL, lL, oL);
  vec2 dir2 = dir * dir;
  float dirR = dir2.x + dir2.y;
  bool zro = dirR < 1.0 / 32768.0;
  dirR = zro ? 1.0 : inversesqrt(dirR);
  dir.x = zro ? 1.0 : dir.x;
  dir *= dirR;
  len = len * 0.5;
  len *= len;
  float stretch = (dir.x * dir.x + dir.y * dir.y) / max(abs(dir.x), abs(dir.y));
  vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);
  float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;
  float clp = 1.0 / lob;
  vec3 min4 = min(min(f, g), min(j, k));
  vec3 max4 = max(max(f, g), max(j, k));
  vec3 aC = vec3(0.0);
  float aW = 0.0;
  tap(aC, aW, vec2(0.0, -1.0) - pp, dir, len2, lob, clp, b);
  tap(aC, aW, vec2(1.0, -1.0) - pp, dir, len2, lob, clp, c);
  tap(aC, aW, vec2(-1.0, 1.0) - pp, dir, len2, lob, clp, i);
  tap(aC, aW, vec2(0.0, 1.0) - pp, dir, len2, lob, clp, j);
  tap(aC, aW, vec2(0.0, 0.0) - pp, dir, len2, lob, clp, f);
  tap(aC, aW, vec2(-1.0, 0.0) - pp, dir, len2, lob, clp, e);
  tap(aC, aW, vec2(1.0, 1.0) - pp, dir, len2, lob, clp, k);
  tap(aC, aW, vec2(2.0, 1.0) - pp, dir, len2, lob, clp, l);
  tap(aC, aW, vec2(2.0, 0.0) - pp, dir, len2, lob, clp, h);
  tap(aC, aW, vec2(1.0, 0.0) - pp, dir, len2, lob, clp, g);
  tap(aC, aW, vec2(1.0, 2.0) - pp, dir, len2, lob, clp, o);
  tap(aC, aW, vec2(0.0, 2.0) - pp, dir, len2, lob, clp, n);
  gl_FragColor = vec4(min(max4, max(min4, aC / aW)), 1.0);
}
