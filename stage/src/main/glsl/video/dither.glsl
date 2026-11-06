// Dithering to the screen's 8 bits, libplacebo's pl_shader_dither (src/shaders/dithering.c, 7.360, LGPL-2.1-or-later)
// with its default blue noise, as core/player's Dither converts it: each value scaled to 0..255, the pixel's threshold
// from the 64 × 64 matrix added, rounded down — the levels on either side mixed in the shares that average to the
// value. The last pass before the screen, drawn at the screen's size, texel for pixel.
// The including pass defines FETCH(texel) (a texel of the picture by its integer position), RANK(cell) (the matrix's
// texel at an integer position in 0..63) and FRAG_COLOR before this part.
// The row libplacebo counts from the top: uRows.x + uRows.y * gl_FragCoord.y. The stages hold their frames with the
// top row where v is 1, so (height, -1); a frame held top row first would pass (0, 1).
uniform vec2 uRows;
void main() {
  vec2 pixel = floor(vec2(gl_FragCoord.x, uRows.x + uRows.y * gl_FragCoord.y));
  // The matrix's rank (0 to 4095) is kept as its high and low byte in red and green: read exactly, whatever the chip.
  vec2 bytes = floor(RANK(pixel - 64.0 * floor(pixel / 64.0)).rg * 255.0 + 0.5);
  float bias = (bytes.r * 256.0 + bytes.g) * (1.0 / 4096.0);
  vec3 color = FETCH(floor(gl_FragCoord.xy)).rgb;
  FRAG_COLOR = vec4(floor(255.0 * color + vec3(bias)) * (1.0 / 255.0), 1.0);
}
