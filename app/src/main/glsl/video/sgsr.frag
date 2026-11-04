// Snapdragon Game Super Resolution 1 (Qualcomm, BSD-3-Clause, see THIRD-PARTY-NOTICE.Snapdragon-GSR.txt), its mobile
// shader in RGBA mode: one pass to the output's size — the picture sampled smoothly, and where the green channel shows
// an edge around the output pixel, a Lanczos-2 estimate along it from the 12 texels around, its sharpened change added
// to all three colours. Each textureGather of the original is the four texels it returns, read one by one (the GLSL
// specification's order: (i0, j1), (i1, j1), (i1, j0), (i0, j0)), so it runs without OpenGL ES 3.1.
precision highp float;
uniform sampler2D uTex;
uniform vec2 uInSize;
uniform float uGrain;
uniform float uSeed;
// Film grain: a different random value at every pixel and frame, the same on all three channels;
// Dave Hoskins' "hash without sine" (MIT), which stays patternless at large coordinates.
vec3 grain(vec3 color) {
  if (uGrain <= 0.0) return color;
  vec3 p3 = fract(vec3(gl_FragCoord.xy, uSeed).xyz * 0.1031);
  p3 += dot(p3, p3.zyx + 31.32);
  float n = fract((p3.x + p3.y) * p3.z) - 0.5;
  return clamp(color + n * 2.0 * uGrain, 0.0, 1.0);
}
varying vec2 vTex;

const float EDGE_THRESHOLD = 8.0 / 255.0;
const float EDGE_SHARPNESS = 2.0;

// The green value of texel (i, j), j along the texture's v.
float g(float i, float j) { return texture2D(uTex, (vec2(i, j) + 0.5) / uInSize).g; }

// What textureGather(green) returns at the texel corner (i, j): the 2 x 2 texels i - 1 .. i, j - 1 .. j.
vec4 gather(float i, float j) { return vec4(g(i - 1.0, j), g(i, j), g(i, j - 1.0), g(i - 1.0, j - 1.0)); }

float fastLanczos2(float x) {
  float wA = x - 4.0;
  float wB = x * wA - wA;
  wA *= wA;
  return wB * wA;
}

vec2 weightY(float dx, float dy, float c, float std) {
  float x = (dx * dx + dy * dy) * 0.55 + clamp(abs(c) * std, 0.0, 1.0);
  float w = fastLanczos2(x);
  return vec2(w, w * c);
}

void main() {
  vec4 color = vec4(texture2D(uTex, vTex).rgb, 0.0);
  vec2 imgCoord = vTex * uInSize + vec2(-0.5, 0.5);
  vec2 corner = floor(imgCoord);
  vec2 pl = imgCoord - corner;
  vec4 left = gather(corner.x, corner.y);
  float edgeVote = abs(left.z - left.y) + abs(color.g - left.y) + abs(color.g - left.z);
  if (edgeVote > EDGE_THRESHOLD) {
    float cx = corner.x + 1.0;
    vec4 right = gather(cx + 1.0, corner.y);
    vec4 upDown;
    upDown.xy = gather(cx, corner.y - 1.0).wz;
    upDown.zw = gather(cx, corner.y + 1.0).yx;

    float mean = (left.y + left.z + right.x + right.w) * 0.25;
    left -= vec4(mean);
    right -= vec4(mean);
    upDown -= vec4(mean);
    color.w = color.g - mean;

    float sum = abs(left.x) + abs(left.y) + abs(left.z) + abs(left.w)
      + abs(right.x) + abs(right.y) + abs(right.z) + abs(right.w)
      + abs(upDown.x) + abs(upDown.y) + abs(upDown.z) + abs(upDown.w);
    float std = 2.181818 / sum;

    vec2 aWY = weightY(pl.x, pl.y + 1.0, upDown.x, std);
    aWY += weightY(pl.x - 1.0, pl.y + 1.0, upDown.y, std);
    aWY += weightY(pl.x - 1.0, pl.y - 2.0, upDown.z, std);
    aWY += weightY(pl.x, pl.y - 2.0, upDown.w, std);
    aWY += weightY(pl.x + 1.0, pl.y - 1.0, left.x, std);
    aWY += weightY(pl.x, pl.y - 1.0, left.y, std);
    aWY += weightY(pl.x, pl.y, left.z, std);
    aWY += weightY(pl.x + 1.0, pl.y, left.w, std);
    aWY += weightY(pl.x - 1.0, pl.y - 1.0, right.x, std);
    aWY += weightY(pl.x - 2.0, pl.y - 1.0, right.y, std);
    aWY += weightY(pl.x - 2.0, pl.y, right.z, std);
    aWY += weightY(pl.x - 1.0, pl.y, right.w, std);

    float finalY = aWY.y / aWY.x;
    float maxY = max(max(left.y, left.z), max(right.x, right.w));
    float minY = min(min(left.y, left.z), min(right.x, right.w));
    finalY = clamp(EDGE_SHARPNESS * finalY, minY, maxY);
    // Smooth high-contrast input.
    float deltaY = clamp(finalY - color.w, -23.0 / 255.0, 23.0 / 255.0);
    color.rgb = clamp(color.rgb + deltaY, 0.0, 1.0);
  }
  gl_FragColor = vec4(grain(color.rgb), 1.0);
}
