#version 300 es
// Deblocking (Deblock, FFmpeg's vf_deblock), one of its three passes: uPass 0 filters the edges between block columns
// on the rows the next pass does not reach first, 1 the edges between block rows, 2 the block columns' edges on the
// rest — which comes to FFmpeg's edge after edge, byte for byte. The block grid starts uOrigin pixels in (a crop having
// cut into the picture's own blocks). Each value is filtered as its 8-bit step; a frame holding finer values (half
// floats) keeps them, moved by the same step. The frames hold the picture's top row where v is 1.
precision highp float;
precision highp int;
uniform sampler2D uTex;
uniform ivec2 uSize;
uniform ivec2 uOrigin;
uniform int uPass;
uniform bool uStrong;
// FFmpeg's thresholds, in 8-bit steps: alpha, beta, gamma, delta.
uniform ivec4 uThresholds;
out vec4 fragColor;

vec3 fetch(ivec2 p) { return texelFetch(uTex, ivec2(p.x, uSize.y - 1 - p.y), 0).rgb; }

// C's integer division: towards zero.
float cdiv(float d, float n) { return trunc(d / n); }

// The new 8-bit value at offset i (-3 .. 2) from an edge whose six values around it are v[0] .. v[5] (offsets -3 .. 2).
float filtered(float v[6], int i) {
  vec4 t = vec4(uThresholds);
  if (!uStrong) {
    float d = v[3] - v[2];
    if (abs(d) >= t.x || abs(v[2] - v[1]) >= t.y || abs(v[3] - v[4]) >= t.z) return v[i + 3];
    if (i == -2) return clamp(v[1] + cdiv(d, 8.0), 0.0, 255.0);
    if (i == -1) return clamp(v[2] + cdiv(d, 2.0), 0.0, 255.0);
    if (i == 0) return clamp(v[3] - cdiv(d, 2.0), 0.0, 255.0);
    if (i == 1) return clamp(v[4] - cdiv(d, 8.0), 0.0, 255.0);
    return v[i + 3];
  }
  float d = v[3] - v[2];
  if (abs(d) >= t.x || abs(v[2] - v[1]) >= t.y || abs(v[4] - v[5]) >= t.z || abs(v[3] - v[4]) >= t.w) return v[i + 3];
  if (i == -3) return clamp(v[0] + cdiv(d, 8.0), 0.0, 255.0);
  if (i == -2) return clamp(v[1] + cdiv(d, 4.0), 0.0, 255.0);
  if (i == -1) return clamp(v[2] + cdiv(d, 2.0), 0.0, 255.0);
  if (i == 0) return clamp(v[3] - cdiv(d, 2.0), 0.0, 255.0);
  if (i == 1) return clamp(v[4] - cdiv(d, 4.0), 0.0, 255.0);
  return clamp(v[5] - cdiv(d, 8.0), 0.0, 255.0);
}

void main() {
  ivec2 p = ivec2(int(gl_FragCoord.x), uSize.y - 1 - int(gl_FragCoord.y));
  vec3 own = fetch(p);
  fragColor = vec4(own, 1.0);
  int reach = uStrong ? 3 : 2;
  bool down = uPass == 1;
  // Passes 0 and 2 split the rows: those the edge between block rows reaches below it come last.
  if (!down) {
    bool early = (((p.y - uOrigin.y) % 8) + 8) % 8 < reach;
    if (early != (uPass == 2)) return;
  }
  int at = down ? p.y : p.x;
  int origin = down ? uOrigin.y : uOrigin.x;
  int size = down ? uSize.y : uSize.x;
  int k = (at - origin + reach + 64) / 8 - 8;
  int edge = origin + 8 * k;
  int i = at - edge;
  // Every block boundary past the first block, with room either side; FFmpeg leaves the last band's top edge.
  if (k < 1 || i < -reach || i >= reach || edge - reach < 0 || edge + reach > size) return;
  if (down && edge >= size - 8) return;
  ivec2 step = down ? ivec2(0, 1) : ivec2(1, 0);
  ivec2 base = p - step * i;
  vec3 values[6];
  for (int j = 0; j < 6; j++) {
    values[j] = j - 3 >= -reach && j - 3 < reach ? fetch(base + step * (j - 3)) : vec3(0.0);
  }
  vec3 result = own;
  for (int c = 0; c < 3; c++) {
    float v[6];
    for (int j = 0; j < 6; j++) v[j] = floor(values[j][c] * 255.0 + 0.5);
    result[c] = own[c] + (filtered(v, i) - v[i + 3]) / 255.0;
  }
  fragColor = vec4(result, 1.0);
}
