#version 300 es
// Deringing (Cdef, AV1's CDEF as dav1d runs it), first pass: one texel per full 8 x 8 block of the picture — the grid
// starting uOrigin pixels in — holding the block's direction (0 to 7) in red and the primary strength its variance
// leaves (dav1d's adjust_strength) in green, both as 8-bit values; found on the block's brightness,
// (77 R + 150 G + 29 B + 128) >> 8 of its 8-bit values. Block rows counted from the picture's top as texture rows.
precision highp float;
precision highp int;
uniform sampler2D uTex;
uniform ivec2 uSize;
uniform ivec2 uOrigin;
uniform int uPrimary;
out vec4 fragColor;

int log2i(int v) {
  int n = 0;
  for (int i = 1; i < 31; i++) if ((v >> i) > 0) n = i;
  return n;
}

void main() {
  ivec2 block = uOrigin + ivec2(gl_FragCoord.xy) * 8;
  int hv[16];
  int diag[30];
  int alt[44];
  for (int i = 0; i < 16; i++) hv[i] = 0;
  for (int i = 0; i < 30; i++) diag[i] = 0;
  for (int i = 0; i < 44; i++) alt[i] = 0;
  for (int y = 0; y < 8; y++) {
    for (int x = 0; x < 8; x++) {
      vec3 rgb = texelFetch(uTex, ivec2(block.x + x, uSize.y - 1 - (block.y + y)), 0).rgb;
      ivec3 v = ivec3(floor(rgb * 255.0 + 0.5));
      int px = ((77 * v.r + 150 * v.g + 29 * v.b + 128) >> 8) - 128;
      diag[y + x] += px;
      alt[y + (x >> 1)] += px;
      hv[y] += px;
      alt[11 + 3 + y - (x >> 1)] += px;
      diag[15 + 7 + y - x] += px;
      alt[22 + 3 - (y >> 1) + x] += px;
      hv[8 + x] += px;
      alt[33 + (y >> 1) + x] += px;
    }
  }
  int divisors[7] = int[](840, 420, 280, 210, 168, 140, 120);
  int cost[8];
  for (int n = 0; n < 8; n++) cost[n] = 0;
  for (int n = 0; n < 8; n++) {
    cost[2] += hv[n] * hv[n];
    cost[6] += hv[8 + n] * hv[8 + n];
  }
  cost[2] *= 105;
  cost[6] *= 105;
  for (int n = 0; n < 7; n++) {
    int d = divisors[n];
    cost[0] += (diag[n] * diag[n] + diag[14 - n] * diag[14 - n]) * d;
    cost[4] += (diag[15 + n] * diag[15 + n] + diag[15 + 14 - n] * diag[15 + 14 - n]) * d;
  }
  cost[0] += diag[7] * diag[7] * 105;
  cost[4] += diag[15 + 7] * diag[15 + 7] * 105;
  for (int n = 0; n < 4; n++) {
    int c = 0;
    for (int m = 0; m < 5; m++) c += alt[n * 11 + 3 + m] * alt[n * 11 + 3 + m];
    c *= 105;
    for (int m = 0; m < 3; m++) {
      int d = divisors[2 * m + 1];
      c += (alt[n * 11 + m] * alt[n * 11 + m] + alt[n * 11 + 10 - m] * alt[n * 11 + 10 - m]) * d;
    }
    cost[n * 2 + 1] = c;
  }
  int best = 0;
  for (int n = 1; n < 8; n++) if (cost[n] > cost[best]) best = n;
  int variance = (cost[best] - cost[best ^ 4]) >> 10;
  int strength = 0;
  if (variance != 0) {
    int i = (variance >> 6) != 0 ? min(log2i(variance >> 6), 12) : 0;
    strength = (uPrimary * (4 + i) + 8) >> 4;
  }
  // Without a primary strength dav1d filters along direction 0.
  if (uPrimary == 0) best = 0;
  fragColor = vec4(float(best), float(strength), 0.0, 255.0) / 255.0;
}
