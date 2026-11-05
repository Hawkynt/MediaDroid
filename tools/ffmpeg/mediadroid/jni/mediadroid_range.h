// MediaDroid — LGPL-3.0-or-later.
//
// Full-range (JPEG, AVCOL_RANGE_JPEG) 8-bit YUV squeezed into limited (TV) range. The video decoder
// hands its frames on as yuv420p with a colour space but no range — Media3's VideoDecoderOutputBuffer
// has no field for one — and everything after it (the app's stage, the YV12 surface) reads them as
// limited range. A full-range frame, as MJPEG cameras and some H.264 write, would come out with its
// blacks crushed and its whites clipped; squeezed here it is a limited-range frame like every other.
//
// The constants are the 8-bit quantisation of ITU-R BT.601-7 and BT.709-6: luma 0..255 to 16..235
// (219 steps), chroma 0..255 about 128 to 16..240 (224 steps), rounded to the nearest. No
// FFmpeg here, so tools/shader-check runs this very code ahead of the stage's shader.

#ifndef MEDIADROID_RANGE_H_
#define MEDIADROID_RANGE_H_

#include <stddef.h>
#include <stdint.h>

namespace mediadroid {

/** The limited-range value of every full-range luma (chroma when [chroma]) value, rounded. */
struct RangeTable {
  uint8_t value[256];

  explicit RangeTable(bool chroma) {
    for (int v = 0; v < 256; v++) {
      // n / 255 of the full span onto 219 (224) steps from 16 (about 128); + 127 rounds the division.
      value[v] = chroma ? (uint8_t)(128 + ((v - 128) * 224 + (v < 128 ? -127 : 127)) / 255)
                        : (uint8_t)(16 + (v * 219 + 127) / 255);
    }
  }
};

/** Squeezes [size] full-range bytes of one plane in place: luma unless [chroma]. */
inline void fullToLimited(uint8_t* plane, size_t size, bool chroma) {
  static const RangeTable luma(false);
  static const RangeTable colour(true);
  const uint8_t* table = (chroma ? colour : luma).value;
  for (size_t i = 0; i < size; i++) plane[i] = table[plane[i]];
}

}  // namespace mediadroid

#endif  // MEDIADROID_RANGE_H_
