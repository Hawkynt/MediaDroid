// Motion-compensated interpolation, written from the core's MotionInterpolation.compensate (FFmpeg's
// minterpolate, bilateral OBMC), whose search ran on the picture uScale times smaller (uSearch): each
// pixel takes the up to four 16-pixel blocks (of that grid) whose doubled windows cover it, weighted by
// uObmc, and mixes the previous frame uAlpha/1024 along the block's vector with the current frame the
// rest of the way back; offsets are whole pixels of the search's size, uScale times that at full size.
// Rows count from the top.
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uPrevious;
uniform sampler2D uVectors;
uniform sampler2D uObmc;
uniform vec2 uSize;
uniform vec2 uGrid;
uniform vec2 uSearch;
uniform float uScale;
uniform float uAlpha;
varying vec2 vTex;
vec3 at(sampler2D s, vec2 p) {
  p = clamp(p, vec2(0.0), uSize - 1.0);
  return texture2D(s, vec2(p.x + 0.5, uSize.y - p.y - 0.5) / uSize).rgb;
}
vec2 truncate(vec2 v) { return sign(v) * floor(abs(v)); }
void main() {
  vec2 pixel = vec2(floor(gl_FragCoord.x), uSize.y - 1.0 - floor(gl_FragCoord.y));
  vec2 h = floor(pixel / uScale);
  vec2 first = floor((h + 8.0) / 16.0) - 1.0;
  vec3 sum = vec3(0.0);
  float weights = 0.0;
  for (int j = 0; j < 2; j++) {
    for (int i = 0; i < 2; i++) {
      vec2 mb = first + vec2(float(i), float(j));
      if (mb.x < 0.0 || mb.y < 0.0 || mb.x >= uGrid.x || mb.y >= uGrid.y) continue;
      vec2 start = mb * 16.0 - 8.0;
      vec2 lo = max(start, vec2(0.0));
      vec2 hi = min(start + 32.0, uSearch - 1.0);
      if (h.x < lo.x || h.y < lo.y || h.x >= hi.x || h.y >= hi.y) continue;
      float w = floor(texture2D(uObmc, (h - start + 0.5) / 32.0).r * 255.0 + 0.5);
      if (w == 0.0) continue;
      vec2 v = (floor(texture2D(uVectors, (mb + 0.5) / uGrid).rg * 255.0 + 0.5) - 128.0) * 2.0;
      vec2 o1 = clamp(truncate(v * uAlpha / 1024.0), -h, uSearch - 1.0 - h);
      vec2 o2 = clamp(truncate(-v * (1024.0 - uAlpha) / 1024.0), -h, uSearch - 1.0 - h);
      float w1 = w * (1024.0 - uAlpha);
      float w2 = w * uAlpha;
      sum += w1 * at(uPrevious, pixel + uScale * o1) + w2 * at(uTex, pixel + uScale * o2);
      weights += w1 + w2;
    }
  }
  vec3 c = weights > 0.0 ? sum / weights : mix(at(uPrevious, pixel), at(uTex, pixel), uAlpha / 1024.0);
  gl_FragColor = vec4(c, 1.0);
}
