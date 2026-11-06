// Colour under a black-and-white picture (Colorizing): each pixel keeps its own lightness — Lab's L of the picture —
// and takes the a and b the network gave at 256 x 256 (uAb, red and green), enlarged by the texture's linear filter;
// back to sRGB as OpenCV's float Lab2RGB does it.
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uAb;
varying vec2 vTex;

float toLinear(float c) {
  return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4);
}

float toEncoded(float c) {
  return clamp(c <= 0.0031308 ? 12.92 * c : 1.055 * pow(c, 1.0 / 2.4) - 0.055, 0.0, 1.0);
}

float cube(float t) {
  return t > 0.206893 ? t * t * t : (t - 16.0 / 116.0) / 7.787;
}

void main() {
  vec4 picture = texture2D(uTex, vTex);
  vec3 lin = vec3(toLinear(picture.r), toLinear(picture.g), toLinear(picture.b));
  float y = dot(lin, vec3(0.212671, 0.715160, 0.072169));
  float fy = y > 0.008856 ? pow(y, 1.0 / 3.0) : 7.787 * y + 16.0 / 116.0;
  vec2 ab = texture2D(uAb, vTex).rg;
  float x = cube(fy + ab.x / 500.0) * 0.950456;
  float z = cube(fy - ab.y / 200.0) * 1.088754;
  vec3 rgb = vec3(
    3.240479 * x - 1.53715 * y - 0.498535 * z,
    -0.969256 * x + 1.875991 * y + 0.041556 * z,
    0.055648 * x - 0.204043 * y + 1.057311 * z);
  gl_FragColor = vec4(toEncoded(rgb.r), toEncoded(rgb.g), toEncoded(rgb.b), picture.a);
}
