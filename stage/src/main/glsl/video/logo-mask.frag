// Logo masking (LogoMask): the rectangle uRect (left, top, width, height in pixels, rows from the top) filled from the
// ring of pixels just around it (uMode 0, Blur: FFmpeg's delogo — each side's three ring pixels nearest the row or
// column, the sides weighed by nearness), greyed (1), or held no brighter than Blur would fill it (2, Dim). Pixels
// outside pass as they are. The frames hold the picture's top row where v is 1; read at texel centres, nearest.
precision highp float;
uniform sampler2D uTex;
uniform vec2 uSize;
uniform vec4 uRect;
uniform int uMode;
varying vec2 vTex;

vec3 at(float x, float y) { return texture2D(uTex, vec2(x + 0.5, uSize.y - y - 0.5) / uSize).rgb * 255.0; }

void main() {
  vec3 own = texture2D(uTex, vTex).rgb;
  float x = floor(vTex.x * uSize.x);
  float y = floor((1.0 - vTex.y) * uSize.y);
  if (x < uRect.x || y < uRect.y || x >= uRect.x + uRect.z || y >= uRect.y + uRect.w) {
    gl_FragColor = vec4(own, 1.0);
    return;
  }
  if (uMode == 1) {
    gl_FragColor = vec4(vec3(128.0 / 255.0), 1.0);
    return;
  }
  float x1 = uRect.x - 1.0;
  float x2 = uRect.x + uRect.z;
  float y1 = uRect.y - 1.0;
  float y2 = uRect.y + uRect.w;
  vec3 left = at(x1, y - 1.0) + at(x1, y) + at(x1, y + 1.0);
  vec3 right = at(x2, y - 1.0) + at(x2, y) + at(x2, y + 1.0);
  vec3 top = at(x - 1.0, y1) + at(x, y1) + at(x + 1.0, y1);
  vec3 bottom = at(x - 1.0, y2) + at(x, y2) + at(x + 1.0, y2);
  float across = (x - x1) * (x2 - x);
  float down = (y - y1) * (y2 - y);
  float wLeft = (x2 - x) * down;
  float wRight = (x - x1) * down;
  float wTop = across * (y2 - y);
  float wBottom = across * (y - y1);
  float total = 3.0 * (wLeft + wRight + wTop + wBottom);
  vec3 mean = floor((left * wLeft + right * wRight + top * wTop + bottom * wBottom) / total + 0.5) / 255.0;
  gl_FragColor = vec4(uMode == 2 ? min(own, mean) : mean, 1.0);
}
