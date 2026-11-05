// AMD FidelityFX Super Resolution 1, EASU (edge-adaptive spatial upsampling), from ffx_fsr1.h
// (MIT, see THIRD-PARTY-NOTICE.FidelityFX-FSR.txt): the 12 texels around 'f' read one by one where
// AMD gathers them, with AMD's own approximate reciprocals (ffx-approx.glsl). Taps, as in the original:
//     b c
//   e f g h
//   i j k l
//     n o
// GLSL ES 1.00, for graphics chips without ES 3; fsr-easu-es3.frag reads the floats' bits directly where it can.
precision highp float;
uniform sampler2D uTex;
uniform vec2 uInSize;
varying vec2 vTex;
#define FRAG_COLOR gl_FragColor
#include "ffx-approx.glsl"
#include "fsr-easu.glsl"
