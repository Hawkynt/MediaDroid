#version 300 es
// KrigBilateral (Shiandow, LGPL-3.0-or-later; ChromaUpscaling.lowResolutionLuma), second pass: the first pass's means
// brought down to the chroma's width the same way, their variance across the columns added to the first pass's own
// variance read between its two nearest columns. Drawn at the chroma's size into a half-float frame, its rows those
// of the first pass (gl_FragCoord, so both keep one orientation).
precision highp float;
precision highp int;
uniform sampler2D uTex;
uniform float uLumaWidth;
uniform float uChromaWidth;
out vec4 fragColor;

float window(float x) {
  return dot(vec3(0.42659, -0.49656, 0.076849), cos(vec3(0.0, 1.0, 2.0) * 3.14159265 * (x + 1.0)));
}

vec2 first(int x, int row) {
  return texelFetch(uTex, ivec2(clamp(x, 0, int(uLumaWidth) - 1), row), 0).rg;
}

void main() {
  int row = int(gl_FragCoord.y);
  float pos = gl_FragCoord.x / uChromaWidth;
  int low = int(ceil((pos - 1.0 / uChromaWidth) * uLumaWidth - 0.5));
  int high = int(floor((pos + 1.0 / uChromaWidth) * uLumaWidth - 0.5));
  float total = 0.0;
  vec2 sum = vec2(0.0);
  for (int k = low; k <= high; k++) {
    float w = window(((float(k) + 0.5) / uLumaWidth - pos) * uChromaWidth);
    float m = first(k, row).x;
    sum += w * vec2(m, m * m);
    total += w;
  }
  sum /= total;
  float at = pos * uLumaWidth - 0.5;
  int left = int(floor(at));
  float variance = mix(first(left, row).y, first(left + 1, row).y, at - float(left));
  fragColor = vec4(sum.x, abs(sum.y - sum.x * sum.x) + variance, 0.0, 1.0);
}
