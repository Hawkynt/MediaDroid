// Debanding, libplacebo's pl_shader_deband (src/shaders/sampling.c, 7.360, LGPL-2.1-or-later), as core/player's Deband
// converts it: each pixel against the average of four points a quarter turn apart at a random angle and distance; where
// the two differ by less than the threshold the pixel lies on a gradient and takes the average. Each further iteration
// reaches one radius further with the threshold divided by its number. Then grain, faded out towards black.
// The including pass defines FETCH(texel) (a texel by its integer position, read exactly), random() (three numbers in
// 0..1 a call) and seedRandom(column, row) before this part, and FRAG_COLOR.
uniform vec2 uSize;
// The row libplacebo counts from the top: uRows.x + uRows.y * gl_FragCoord.y. The stages hold their frames with the
// top row where v is 1, so (height, -1); a frame held top row first would pass (0, 1).
uniform vec2 uRows;
uniform int uIterations;
// libplacebo's threshold, radius and grain: the threshold and grain in full scale (thousandths divided by 1000).
uniform float uThreshold;
uniform float uRadius;
uniform float uDebandGrain;
// A texel's 8-bit value times the float nearest 1/255 — a hair off an exact division, which decides how an average of
// four landing on a half step rounds. libplacebo's output on Mesa rounds as this does (core/player's Deband reads its
// texels the same way and agrees byte for byte); read as the chip reads it, one value in eight rounded the other way.
// Requantised, every chip reads the same values, whatever its sampler's arithmetic.
vec3 sampleAt(vec2 at) {
  vec3 bytes = floor(FETCH(clamp(floor(at), vec2(0.0), uSize - 1.0)).rgb * 255.0 + 0.5);
  return bytes * (1.0 / 255.0);
}
void main() {
  float row = uRows.x + uRows.y * gl_FragCoord.y;
  seedRandom(gl_FragCoord.x, row);
  // The pixel's centre in libplacebo's coordinates (rows from the top) and the way back to ours.
  vec2 centre = vec2(gl_FragCoord.x, row);
  vec3 res = sampleAt(vec2(gl_FragCoord.x, gl_FragCoord.y));
  for (int i = 1; i <= 4; i++) {
    if (i > uIterations) break;
    vec2 d = random().xy * vec2(float(i) * uRadius, 6.283185);
    d = d.x * vec2(cos(d.y), sin(d.y));
    // Each point found in libplacebo's rows, then read from ours; summed in libplacebo's order.
    vec3 avg = vec3(0.0);
    vec2 p = floor(centre + vec2(d.x, d.y));
    avg += sampleAt(vec2(p.x, uRows.x + uRows.y * (p.y + 0.5)));
    p = floor(centre + vec2(-d.x, d.y));
    avg += sampleAt(vec2(p.x, uRows.x + uRows.y * (p.y + 0.5)));
    p = floor(centre + vec2(-d.x, -d.y));
    avg += sampleAt(vec2(p.x, uRows.x + uRows.y * (p.y + 0.5)));
    p = floor(centre + vec2(d.x, -d.y));
    avg += sampleAt(vec2(p.x, uRows.x + uRows.y * (p.y + 0.5)));
    avg *= 0.25;
    vec3 diff = abs(res - avg);
    vec3 bound = vec3(uThreshold / float(i));
    // libplacebo's mix by a bvec picks one or the other exactly; a float mix(x, y, 1.0) can land a hair off y.
    vec3 keep = vec3(greaterThan(diff, bound));
    res = keep * res + (1.0 - keep) * avg;
  }
  if (uDebandGrain > 0.0) {
    // Black is the neutral value: the grain fades out towards it.
    vec3 strength = min(abs(res), vec3(uDebandGrain));
    res += strength * (random() - vec3(0.5));
  }
  FRAG_COLOR = vec4(res, 1.0);
}
