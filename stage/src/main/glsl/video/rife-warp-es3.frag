#version 300 es
// RIFE's last step at the frames' own size (NeuralInterpolation.handsFlow): its flow and blend mask, worked out on the
// frames drawn smaller (uFlow: towards the earlier frame in xy, the later in zw, in pixels of that size; uMask: the
// mask's logit), read linearly at each pixel and grown by uScale; the earlier frame (uPrevious) and the later (uTex)
// sampled along their flows as RIFE's warp layer samples them (native/src/warp_layer.cpp: bilinear, the weights from
// the corner after clamping) and blended by the mask's sigmoid, the earlier frame's share. Rows as the frames hold
// them, as the network saw them.
precision highp float;
uniform highp sampler2D uTex;
uniform highp sampler2D uPrevious;
uniform highp sampler2D uFlow;
uniform highp sampler2D uMask;
uniform vec2 uSize;
uniform vec2 uScale;
out vec4 fragColor;

vec3 warp(highp sampler2D frame, vec2 at) {
  vec2 corner = floor(at);
  vec2 low = clamp(corner, vec2(0.0), uSize - 1.0);
  vec2 high = clamp(corner + 1.0, vec2(0.0), uSize - 1.0);
  vec2 t = at - low;
  vec3 a = texelFetch(frame, ivec2(low.x, low.y), 0).rgb;
  vec3 b = texelFetch(frame, ivec2(high.x, low.y), 0).rgb;
  vec3 c = texelFetch(frame, ivec2(low.x, high.y), 0).rgb;
  vec3 d = texelFetch(frame, ivec2(high.x, high.y), 0).rgb;
  return mix(a * (1.0 - t.x) + b * t.x, c * (1.0 - t.x) + d * t.x, t.y);
}

void main() {
  vec2 pixel = floor(gl_FragCoord.xy);
  vec2 uv = gl_FragCoord.xy / uSize;
  vec4 flow = texture(uFlow, uv) * vec4(uScale, uScale);
  float share = 1.0 / (1.0 + exp(-texture(uMask, uv).r));
  vec3 earlier = warp(uPrevious, pixel + flow.xy);
  vec3 later = warp(uTex, pixel + flow.zw);
  fragColor = vec4(clamp(earlier * share + later * (1.0 - share), 0.0, 1.0), 1.0);
}
