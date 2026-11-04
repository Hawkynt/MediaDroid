// AMD's approximate reciprocals from ffx_a.h (FidelityFX, MIT, see THIRD-PARTY-NOTICE.FidelityFX-FSR.txt), which FSR
// is tuned with: a magic number minus a float's bits, read back as a float. GLSL ES 1.00 cannot read a float's bits,
// so the same integer arithmetic is done on its two fields, each a whole number held exactly in a float: the exponent
// field E (8 bits) and the mantissa field M (23 bits), the subtraction borrowing from E when M runs negative. The
// magic numbers are AMD's, split into their fields: 0x7ef07ebb = (253, 7372475), 0x7ef19fff = (253, 7446527),
// 0x5f347d74 = (190, 3439988). Zero (and anything below the smallest normal float) has the bits 0.

const float FFX_MANTISSA = 8388608.0;  // 2^23

// The bit fields of [a] >= 0.
void ffxBits(float a, out float e, out float m) {
  if (a < 1.17549435e-38) {
    e = 0.0;
    m = 0.0;
    return;
  }
  float x = floor(log2(a));
  float p = exp2(x);
  // log2 may land a hair off next to a power of two: the exponent is set right against the power itself.
  if (a < p) {
    x -= 1.0;
    p *= 0.5;
  } else if (a >= 2.0 * p) {
    x += 1.0;
    p *= 2.0;
  }
  e = x + 127.0;
  m = (a / p - 1.0) * FFX_MANTISSA;
}

// The float of the magic number (magicE, magicM) minus the bits (e, m).
float ffxMagicMinus(float magicE, float magicM, float e, float m) {
  float re = magicE - e;
  float rm = magicM - m;
  if (rm < 0.0) {
    rm += FFX_MANTISSA;
    re -= 1.0;
  }
  return exp2(re - 127.0) * (1.0 + rm / FFX_MANTISSA);
}

// APrxLoRcpF1: 1 / a, about 1 % off.
float APrxLoRcpF1(float a) {
  float e;
  float m;
  ffxBits(a, e, m);
  return ffxMagicMinus(253.0, 7372475.0, e, m);
}

// APrxMedRcpF1: 1 / a, a Newton step closer.
float APrxMedRcpF1(float a) {
  float e;
  float m;
  ffxBits(a, e, m);
  float b = ffxMagicMinus(253.0, 7446527.0, e, m);
  return b * (-b * a + 2.0);
}

// APrxLoRsqF1: 1 / sqrt(a), from the bits shifted right by one — E halved, its odd bit carried into M's top.
float APrxLoRsqF1(float a) {
  float e;
  float m;
  ffxBits(a, e, m);
  float halfE = floor(e / 2.0);
  float halfM = (e - 2.0 * halfE) * (FFX_MANTISSA / 2.0) + floor(m / 2.0);
  return ffxMagicMinus(190.0, 3439988.0, halfE, halfM);
}
