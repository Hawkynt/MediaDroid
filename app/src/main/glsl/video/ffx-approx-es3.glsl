// AMD's approximate reciprocals from ffx_a.h (FidelityFX, MIT, see THIRD-PARTY-NOTICE.FidelityFX-FSR.txt) as AMD
// writes them: a magic number minus the float's bits, read back as a float — GLSL ES 3.00 reads the bits directly.
// ffx-approx.glsl is the same arithmetic for GLSL ES 1.00, on the bits' two fields.

float APrxLoRcpF1(float a) { return uintBitsToFloat(0x7ef07ebbu - floatBitsToUint(a)); }

float APrxMedRcpF1(float a) {
  float b = uintBitsToFloat(0x7ef19fffu - floatBitsToUint(a));
  return b * (-b * a + 2.0);
}

float APrxLoRsqF1(float a) { return uintBitsToFloat(0x5f347d74u - (floatBitsToUint(a) >> 1u)); }
