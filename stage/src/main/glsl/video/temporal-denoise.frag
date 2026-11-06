// Temporal denoising (TemporalDenoise, FFmpeg's atadenoise over three frames): each value averaged with the same point
// of the picture in the frame before, where it differs from it by at most threshold A (and B), and then in the frame
// after on the same terms — so grain averages out and what changed is left alone. Each value is judged as its 8-bit
// step; a frame holding finer values (half floats) keeps them, moved by the same step.
//
// Each side is read as uPrevMode / uNextMode says: 0 the same pixel, read at the texel's centre; 1 moved onto this
// frame along its motion (TemporalDenoise.aligned) — the half-vectors of a grid of 16-pixel blocks found at uScale
// times smaller, each pixel taking the up to four blocks whose doubled windows cover it, weighted by uObmc, and reading
// the frame one whole vector along (the frame before) or against (the frame after) it, rounded to its step; 2 not
// taken (a cut lies that way). Rows count from the top for the motion, as the search's luma does.
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uPrev;
uniform sampler2D uNext;
uniform sampler2D uPrevVectors;
uniform sampler2D uNextVectors;
uniform sampler2D uObmc;
// FFmpeg's thresholds in 8-bit steps.
uniform float uA;
uniform float uB;
uniform float uPrevMode;
uniform float uNextMode;
uniform vec2 uSize;
uniform vec2 uPrevGrid;
uniform vec2 uNextGrid;
uniform float uPrevScale;
uniform float uNextScale;
varying vec2 vTex;

vec3 steps(sampler2D s, vec2 p) {
  return floor(texture2D(s, vec2(p.x + 0.5, uSize.y - p.y - 0.5) / uSize).rgb * 255.0 + 0.5);
}

vec3 aligned(sampler2D frame, sampler2D vectors, vec2 grid, float scale, float sign, vec2 pixel) {
  vec2 search = floor(uSize / scale);
  vec2 h = floor(pixel / scale);
  vec2 first = floor((h + 8.0) / 16.0) - 1.0;
  vec3 sum = vec3(0.0);
  float weights = 0.0;
  for (int j = 0; j < 2; j++) {
    for (int i = 0; i < 2; i++) {
      vec2 mb = first + vec2(float(i), float(j));
      if (mb.x < 0.0 || mb.y < 0.0 || mb.x >= grid.x || mb.y >= grid.y) continue;
      vec2 start = mb * 16.0 - 8.0;
      vec2 lo = max(start, vec2(0.0));
      vec2 hi = min(start + 32.0, search);
      if (h.x < lo.x || h.y < lo.y || h.x >= hi.x || h.y >= hi.y) continue;
      float w = floor(texture2D(uObmc, (h - start + 0.5) / 32.0).r * 255.0 + 0.5);
      if (w == 0.0) continue;
      vec2 v = (floor(texture2D(vectors, (mb + 0.5) / grid).rg * 255.0 + 0.5) - 128.0) * 2.0 * sign;
      vec2 o = clamp(v, -h, search - 1.0 - h);
      sum += w * steps(frame, clamp(pixel + scale * o, vec2(0.0), uSize - 1.0));
      weights += w;
    }
  }
  // C's (sum + weights / 2) / weights in integers; weights are even, and the small lift keeps an exact half from a
  // division a hair low.
  return weights > 0.0 ? floor(sum / weights + 0.5 + 0.0001) : steps(frame, pixel);
}

float denoised(float x, float before, float after) {
  float sum = x;
  float count = 1.0;
  bool open = true;
  if (uPrevMode < 1.5) {
    float left = abs(x - before);
    open = left <= uA && left <= uB;
    if (open) {
      sum += before;
      count += 1.0;
    }
  }
  if (open && uNextMode < 1.5) {
    float right = abs(x - after);
    if (right <= uA && right <= uB) {
      sum += after;
      count += 1.0;
    }
  }
  // C's (sum + count / 2) / count in integers; the small lift keeps an exact quotient from a division a hair low.
  return floor((sum + floor(count / 2.0)) / count + 0.0001);
}

void main() {
  vec3 own = texture2D(uTex, vTex).rgb;
  vec3 x = floor(own * 255.0 + 0.5);
  vec2 pixel = vec2(floor(gl_FragCoord.x), uSize.y - 1.0 - floor(gl_FragCoord.y));
  vec3 before = floor(texture2D(uPrev, vTex).rgb * 255.0 + 0.5);
  if (uPrevMode > 0.5 && uPrevMode < 1.5) before = aligned(uPrev, uPrevVectors, uPrevGrid, uPrevScale, 1.0, pixel);
  vec3 after = floor(texture2D(uNext, vTex).rgb * 255.0 + 0.5);
  if (uNextMode > 0.5 && uNextMode < 1.5) after = aligned(uNext, uNextVectors, uNextGrid, uNextScale, -1.0, pixel);
  vec3 result = vec3(
    denoised(x.r, before.r, after.r), denoised(x.g, before.g, after.g), denoised(x.b, before.b, after.b));
  gl_FragColor = vec4(own + (result - x) / 255.0, 1.0);
}
