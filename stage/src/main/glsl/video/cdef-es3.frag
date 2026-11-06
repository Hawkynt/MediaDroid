#version 300 es
// Deringing (Cdef, AV1's CDEF as dav1d runs it), second pass: each pixel of a full block blended with the pixels two
// either way along its block's direction (primary, at the strength the first pass left) and across it at 45 degrees
// (secondary), each difference constrained so a real edge counts for nothing — dav1d's cdef_filter_block, every read
// from the unfiltered picture, nothing past its edges. Each value is filtered as its 8-bit step; a frame holding finer
// values (half floats) keeps them, moved by the same step. The frames hold the picture's top row where v is 1.
precision highp float;
precision highp int;
uniform sampler2D uTex;
// The first pass: per block its direction and primary strength (cdef-direction-es3.frag).
uniform sampler2D uDirections;
uniform ivec2 uSize;
uniform ivec2 uOrigin;
uniform ivec2 uBlocks;
uniform int uSecondary;
uniform int uDamping;
out vec4 fragColor;

// dav1d's direction table, per entry the two taps' (dx, dy); direction d's own at d + 2, its neighbours' either side.
const ivec4 DIRECTIONS[12] = ivec4[](
  ivec4(0, 1, 0, 2), ivec4(0, 1, -1, 2), ivec4(1, -1, 2, -2), ivec4(1, 0, 2, -1),
  ivec4(1, 0, 2, 0), ivec4(1, 0, 2, 1), ivec4(1, 1, 2, 2), ivec4(0, 1, 1, 2),
  ivec4(0, 1, 0, 2), ivec4(0, 1, -1, 2), ivec4(1, -1, 2, -2), ivec4(1, 0, 2, -1));

int log2i(int v) {
  int n = 0;
  for (int i = 1; i < 5; i++) if ((v >> i) > 0) n = i;
  return n;
}

ivec3 value(ivec2 p) { return ivec3(floor(texelFetch(uTex, ivec2(p.x, uSize.y - 1 - p.y), 0).rgb * 255.0 + 0.5)); }

bool inside(ivec2 p) { return p.x >= 0 && p.y >= 0 && p.x < uSize.x && p.y < uSize.y; }

ivec3 constrain(ivec3 diff, int threshold, int shift) {
  ivec3 a = abs(diff);
  ivec3 v = min(a, max(ivec3(0), threshold - (a >> shift)));
  return ivec3(diff.x < 0 ? -v.x : v.x, diff.y < 0 ? -v.y : v.y, diff.z < 0 ? -v.z : v.z);
}

void main() {
  ivec2 p = ivec2(int(gl_FragCoord.x), uSize.y - 1 - int(gl_FragCoord.y));
  vec3 own = texelFetch(uTex, ivec2(p.x, uSize.y - 1 - p.y), 0).rgb;
  fragColor = vec4(own, 1.0);
  ivec2 block = p - uOrigin;
  if (block.x < 0 || block.y < 0 || block.x >= uBlocks.x * 8 || block.y >= uBlocks.y * 8) return;
  ivec2 b = ivec2(texelFetch(uDirections, block >> 3, 0).rg * 255.0 + 0.5);
  int dir = b.x;
  int primary = b.y;
  if (primary == 0 && uSecondary == 0) return;
  ivec3 px = value(p);
  int primaryTap = 4 - (primary & 1);
  int primaryShift = primary > 0 ? max(0, uDamping - log2i(primary)) : 0;
  int secondaryShift = uSecondary > 0 ? uDamping - log2i(uSecondary) : 0;
  ivec4 along = DIRECTIONS[dir + 2];
  ivec4 plus = DIRECTIONS[dir + 4];
  ivec4 minus = DIRECTIONS[dir];
  ivec3 sum = ivec3(0);
  ivec3 lo = px;
  ivec3 hi = px;
  int tapK = primaryTap;
  for (int k = 0; k < 2; k++) {
    if (primary > 0) {
      ivec2 off = k == 0 ? along.xy : along.zw;
      for (int s = 0; s < 2; s++) {
        ivec2 q = p + (s == 0 ? off : -off);
        if (!inside(q)) continue;
        ivec3 t = value(q);
        sum += tapK * constrain(t - px, primary, primaryShift);
        lo = min(lo, t);
        hi = max(hi, t);
      }
      tapK = (tapK & 3) | 2;
    }
    if (uSecondary > 0) {
      for (int o = 0; o < 2; o++) {
        ivec4 table = o == 0 ? plus : minus;
        ivec2 off = k == 0 ? table.xy : table.zw;
        for (int s = 0; s < 2; s++) {
          ivec2 q = p + (s == 0 ? off : -off);
          if (!inside(q)) continue;
          ivec3 t = value(q);
          sum += (2 - k) * constrain(t - px, uSecondary, secondaryShift);
          lo = min(lo, t);
          hi = max(hi, t);
        }
      }
    }
  }
  ivec3 negative = ivec3(sum.x < 0 ? 1 : 0, sum.y < 0 ? 1 : 0, sum.z < 0 ? 1 : 0);
  ivec3 v = px + ((sum - negative + 8) >> 4);
  // Only with both strengths does dav1d hold the result within the taps' range.
  if (primary > 0 && uSecondary > 0) v = clamp(v, lo, hi);
  fragColor = vec4(own + vec3(v - px) / 255.0, 1.0);
}
