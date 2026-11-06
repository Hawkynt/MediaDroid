// The temporal deinterlacers by way of the core's Deinterlacer, which is held to their originals byte for byte; uMethod
// picks one: 0 YADIF and 1 BWDIF, converted from FFmpeg's vf_yadif.c (Michael Niedermayer) and vf_bwdif.c (Thomas
// Mundt), LGPL-2.1-or-later; 2 Greedy High Motion, converted from GStreamer's greedyh.c (tvtime's, Tom Barry after
// DScaler, LGPL); 3 Greedy 2-Frame, written anew from DScaler's (GPL-2.0, behaviour only); 4 none — the frame woven as
// it is (Adaptive on a still picture). The same arithmetic on each colour channel, in whole steps of 0..255, but for
// Greedy 2-Frame's choice, which all three channels take together. Rows count from the top; uParity picks the field
// rebuilt (see Deinterlacer.rebuiltParity): with uSecond the earlier one, from the shown frame and the next, otherwise
// the later one, from the previous frame and the shown one.
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uPrev;
uniform sampler2D uNext;
uniform vec2 uSize;
uniform bool uSecond;
uniform float uParity;
uniform int uMethod;
varying vec2 vTex;
vec3 px(sampler2D s, float x, float y) {
  return floor(texture2D(s, vec2(x + 0.5, uSize.y - y - 0.5) / uSize).rgb * 255.0 + 0.5);
}
vec3 cur(float x, float y) { return px(uTex, x, y); }
// The two pictures either side of the rebuilt field's moment.
vec3 before(float x, float y) { return uSecond ? px(uTex, x, y) : px(uPrev, x, y); }
vec3 after(float x, float y) { return uSecond ? px(uNext, x, y) : px(uTex, x, y); }
vec3 score(float x, float y, float m, float p, float j) {
  return abs(cur(x - 1.0 + j, y + m) - cur(x - 1.0 - j, y + p)) +
    abs(cur(x + j, y + m) - cur(x - j, y + p)) +
    abs(cur(x + 1.0 + j, y + m) - cur(x + 1.0 - j, y + p));
}
vec3 along(float x, float y, float m, float p, float j) {
  return floor((cur(x + j, y + m) + cur(x - j, y + p)) * 0.5);
}
vec3 yadif(float x, float y) {
  float m = y > 0.0 ? -1.0 : 1.0;
  float p = y + 1.0 < uSize.y ? 1.0 : -1.0;
  vec3 c = cur(x, y + m);
  vec3 e = cur(x, y + p);
  vec3 b0 = before(x, y);
  vec3 a0 = after(x, y);
  vec3 d = floor((b0 + a0) * 0.5);
  vec3 t0 = abs(b0 - a0);
  vec3 t1 = floor((abs(px(uPrev, x, y + m) - c) + abs(px(uPrev, x, y + p) - e)) * 0.5);
  vec3 t2 = floor((abs(px(uNext, x, y + m) - c) + abs(px(uNext, x, y + p) - e)) * 0.5);
  vec3 diff = max(max(floor(t0 * 0.5), t1), t2);
  vec3 pred = floor((c + e) * 0.5);
  if (x >= 3.0 && x < uSize.x - 3.0) {
    vec3 best = abs(cur(x - 1.0, y + m) - cur(x - 1.0, y + p)) + abs(c - e) +
      abs(cur(x + 1.0, y + m) - cur(x + 1.0, y + p)) - 1.0;
    for (int side = 0; side < 2; side++) {
      float j = side == 0 ? -1.0 : 1.0;
      vec3 s = score(x, y, m, p, j);
      vec3 take = vec3(lessThan(s, best));
      best = mix(best, s, take);
      pred = mix(pred, along(x, y, m, p, j), take);
      vec3 s2 = score(x, y, m, p, 2.0 * j);
      vec3 take2 = take * vec3(lessThan(s2, best));
      best = mix(best, s2, take2);
      pred = mix(pred, along(x, y, m, p, 2.0 * j), take2);
    }
  }
  if (y != 1.0 && y + 2.0 != uSize.y) {
    vec3 b = floor((before(x, y + 2.0 * m) + after(x, y + 2.0 * m)) * 0.5);
    vec3 f = floor((before(x, y + 2.0 * p) + after(x, y + 2.0 * p)) * 0.5);
    vec3 most = max(max(d - e, d - c), min(b - c, f - e));
    vec3 least = min(min(d - e, d - c), max(b - c, f - e));
    diff = max(max(diff, least), -most);
  }
  return clamp(pred, d - diff, d + diff);
}
vec3 both(float x, float y) { return before(x, y) + after(x, y); }
vec3 bwdif(float x, float y) {
  bool edge = y < 4.0 || y + 5.0 > uSize.y;
  float m = (!edge || y > 0.0) ? -1.0 : 1.0;
  float p = (!edge || y + 1.0 < uSize.y) ? 1.0 : -1.0;
  vec3 c = cur(x, y + m);
  vec3 e = cur(x, y + p);
  vec3 b0 = before(x, y);
  vec3 a0 = after(x, y);
  vec3 d = floor((b0 + a0) * 0.5);
  vec3 t0 = abs(b0 - a0);
  vec3 t1 = floor((abs(px(uPrev, x, y + m) - c) + abs(px(uPrev, x, y + p) - e)) * 0.5);
  vec3 t2 = floor((abs(px(uNext, x, y + m) - c) + abs(px(uNext, x, y + p) - e)) * 0.5);
  vec3 still = max(max(floor(t0 * 0.5), t1), t2);
  vec3 diff = still;
  if (!edge || (y >= 2.0 && y + 3.0 <= uSize.y)) {
    vec3 b = floor(both(x, y - 2.0) * 0.5) - c;
    vec3 f = floor(both(x, y + 2.0) * 0.5) - e;
    vec3 most = max(max(d - e, d - c), min(b, f));
    vec3 least = min(min(d - e, d - c), max(b, f));
    diff = max(max(diff, least), -most);
  }
  vec3 interpol = floor((c + e) * 0.5);
  if (!edge) {
    vec3 outer = cur(x, y - 3.0) + cur(x, y + 3.0);
    vec3 hf = floor((5570.0 * both(x, y) - 3801.0 * (both(x, y - 2.0) + both(x, y + 2.0)) +
      1016.0 * (both(x, y - 4.0) + both(x, y + 4.0))) / 4.0);
    vec3 high = floor((hf + 4309.0 * (c + e) - 213.0 * outer) / 8192.0);
    vec3 spatial = floor((5077.0 * (c + e) - 981.0 * outer) / 8192.0);
    interpol = mix(spatial, high, vec3(greaterThan(abs(c - e), t0)));
  }
  vec3 result = clamp(clamp(interpol, d - diff, d + diff), 0.0, 255.0);
  return mix(result, d, vec3(equal(still, vec3(0.0))));
}
// Greedy High Motion (Deinterlacer.greedyHm): the field before's or after's line, whichever lies nearer the average
// of the lines around it smoothed along the row, held within 5 of those lines and blended towards that average by
// how much the two differ beyond 25.
vec3 around(float x, float y) { return floor((cur(x, y - 1.0) + cur(x, y + 1.0)) * 0.5); }
vec3 greedyHm(float x, float y) {
  if (y == 0.0) return cur(x, 1.0);
  if (y + 1.0 == uSize.y) return before(x, y);
  vec3 l1 = cur(x, y - 1.0);
  vec3 l3 = cur(x, y + 1.0);
  vec3 avg = floor((l1 + l3) * 0.5);
  vec3 left = x == 0.0 ? avg : around(x - 1.0, y);
  vec3 right = x + 1.0 == uSize.x ? avg : around(x + 1.0, y);
  vec3 sc = floor((avg + floor((left + right) * 0.5)) * 0.5);
  vec3 l2 = before(x, y);
  vec3 lp2 = after(x, y);
  vec3 best = mix(l2, lp2, vec3(greaterThan(abs(l2 - sc), abs(lp2 - sc))));
  vec3 clipped = clamp(best, max(min(l1, l3) - 5.0, 0.0), min(max(l1, l3) + 5.0, 255.0));
  vec3 motion = min(max(abs(l2 - lp2) - 25.0, 0.0) * 30.0, 256.0);
  return floor((clipped * (256.0 - motion) + sc * motion) / 256.0);
}
// Greedy 2-Frame (Deinterlacer.greedy2Frame): two pixels at a time, half of each difference at most 4 is still.
bool still(sampler2D a, sampler2D b, float x, float y) {
  vec3 d0 = floor(abs(px(a, x, y) - px(b, x, y)) * 0.5);
  vec3 d1 = floor(abs(px(a, min(x + 1.0, uSize.x - 1.0), y) - px(b, min(x + 1.0, uSize.x - 1.0), y)) * 0.5);
  return max(max(max(d0.r, d0.g), d0.b), max(max(d1.r, d1.g), d1.b)) <= 4.0;
}
vec3 greedy2Frame(float x, float y) {
  vec3 newest = after(x, y);
  if (y == 0.0 || y + 1.0 == uSize.y) return newest;
  float pair = x - mod(x, 2.0);
  bool moved = uSecond ? !still(uNext, uTex, pair, y) : !still(uTex, uPrev, pair, y);
  bool weave = !moved && (still(uTex, uPrev, pair, y - 1.0) || still(uTex, uPrev, pair, y + 1.0));
  return weave ? floor((newest + before(x, y) + 1.0) * 0.5) : floor((cur(x, y - 1.0) + cur(x, y + 1.0) + 1.0) * 0.5);
}
void main() {
  float x = floor(gl_FragCoord.x);
  float y = uSize.y - 1.0 - floor(gl_FragCoord.y);
  vec3 v;
  if (mod(y + uParity, 2.0) < 0.5) {
    v = cur(x, y);
  } else {
    if (uMethod == 0) v = yadif(x, y);
    else if (uMethod == 1) v = bwdif(x, y);
    else if (uMethod == 2) v = greedyHm(x, y);
    else if (uMethod == 3) v = greedy2Frame(x, y);
    else v = cur(x, y);
  }
  gl_FragColor = vec4(v / 255.0, 1.0);
}
