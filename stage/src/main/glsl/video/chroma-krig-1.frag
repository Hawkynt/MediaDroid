#version 300 es
// KrigBilateral (Shiandow, LGPL-3.0-or-later; ChromaUpscaling.lowResolutionLuma), first pass: the luma brought down to
// the chroma's height — under each chroma row, the mean and variance of the luma rows within one chroma row either
// way, weighed by KrigBilateral's window. Drawn at the luma's width and the chroma's height into a half-float frame;
// vTex is the luma plane's texture position (as yuv.frag's), rows counted from the top.
precision highp float;
precision highp int;
uniform sampler2D uTex;
// The luma plane: its texture width (the stride) and the picture's height.
uniform vec2 uLumaSize;
uniform float uChromaHeight;
in vec2 vTex;
out vec4 fragColor;

float window(float x) {
  return dot(vec3(0.42659, -0.49656, 0.076849), cos(vec3(0.0, 1.0, 2.0) * 3.14159265 * (x + 1.0)));
}

void main() {
  int column = int(vTex.x * uLumaSize.x);
  float pos = vTex.y;
  int low = int(ceil((pos - 1.0 / uChromaHeight) * uLumaSize.y - 0.5));
  int high = int(floor((pos + 1.0 / uChromaHeight) * uLumaSize.y - 0.5));
  float total = 0.0;
  vec2 sum = vec2(0.0);
  for (int k = low; k <= high; k++) {
    float w = window(((float(k) + 0.5) / uLumaSize.y - pos) * uChromaHeight);
    float y = texelFetch(uTex, ivec2(column, clamp(k, 0, int(uLumaSize.y) - 1)), 0).r;
    sum += w * vec2(y, y * y);
    total += w;
  }
  sum /= total;
  fragColor = vec4(sum.x, abs(sum.y - sum.x * sum.x), 0.0, 1.0);
}
