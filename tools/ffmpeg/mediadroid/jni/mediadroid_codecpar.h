// MediaDroid — LGPL-3.0-or-later.
//
// The codec parameters of a stream FFmpeg demuxed, carried to FFmpeg's decoders through Media3's
// Format.initializationData. A container such as ASF or RealMedia knows things the decoder cannot
// guess — WMA's block align and bit rate, Cook's flavour in the extradata, a codec tag — and Media3's
// Format has no field for most of them. The blob is:
//
//   "MDCP"  version  codec_tag  bit_rate(8)  block_align  bits_per_coded_sample
//   sample_rate  channels  width  height  extradata_size  extradata…
//
// every number little-endian, four bytes unless marked. The decoders recognise the magic, apply the
// fields to their AVCodecContext and keep only the real extradata.

#ifndef MEDIADROID_CODECPAR_H_
#define MEDIADROID_CODECPAR_H_

#include <stdint.h>
#include <string.h>

#include <vector>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/mem.h>
}

namespace mediadroid {

static const uint8_t kCodecParMagic[4] = {'M', 'D', 'C', 'P'};
static const int32_t kCodecParVersion = 1;
static const int kCodecParHeaderSize = 4 + 4 + 4 + 8 + 4 * 6 + 4;

inline void putInt32(std::vector<uint8_t>& out, int32_t value) {
  for (int i = 0; i < 4; i++) out.push_back((uint8_t)((uint32_t)value >> (8 * i)));
}

inline void putInt64(std::vector<uint8_t>& out, int64_t value) {
  for (int i = 0; i < 8; i++) out.push_back((uint8_t)((uint64_t)value >> (8 * i)));
}

inline int32_t getInt32(const uint8_t* in) {
  return (int32_t)((uint32_t)in[0] | ((uint32_t)in[1] << 8) | ((uint32_t)in[2] << 16) |
                   ((uint32_t)in[3] << 24));
}

inline int64_t getInt64(const uint8_t* in) {
  return (int64_t)((uint64_t)(uint32_t)getInt32(in) | ((uint64_t)(uint32_t)getInt32(in + 4) << 32));
}

/** The blob for a demuxed stream's parameters. */
inline std::vector<uint8_t> packCodecParameters(const AVCodecParameters* par) {
  std::vector<uint8_t> out(kCodecParMagic, kCodecParMagic + 4);
  putInt32(out, kCodecParVersion);
  putInt32(out, (int32_t)par->codec_tag);
  putInt64(out, par->bit_rate);
  putInt32(out, par->block_align);
  putInt32(out, par->bits_per_coded_sample);
  putInt32(out, par->sample_rate);
  putInt32(out, par->ch_layout.nb_channels);
  putInt32(out, par->width);
  putInt32(out, par->height);
  putInt32(out, par->extradata_size);
  if (par->extradata_size > 0) out.insert(out.end(), par->extradata, par->extradata + par->extradata_size);
  return out;
}

/**
 * When [context]'s extradata is a blob from packCodecParameters, applies its fields to [context] and
 * replaces the extradata by the stream's own. Returns whether it was one.
 */
inline bool applyCodecParameters(AVCodecContext* context) {
  const uint8_t* in = context->extradata;
  const int size = context->extradata_size;
  if (!in || size < kCodecParHeaderSize || memcmp(in, kCodecParMagic, 4) != 0 ||
      getInt32(in + 4) != kCodecParVersion) {
    return false;
  }
  const int32_t extraSize = getInt32(in + 44);
  if (extraSize < 0 || extraSize > size - kCodecParHeaderSize) return false;
  context->codec_tag = (unsigned int)getInt32(in + 8);
  context->bit_rate = getInt64(in + 12);
  context->block_align = getInt32(in + 20);
  context->bits_per_coded_sample = getInt32(in + 24);
  const int32_t sampleRate = getInt32(in + 28);
  const int32_t channels = getInt32(in + 32);
  const int32_t width = getInt32(in + 36);
  const int32_t height = getInt32(in + 40);
  if (sampleRate > 0) context->sample_rate = sampleRate;
  if (channels > 0) {
    av_channel_layout_uninit(&context->ch_layout);
    av_channel_layout_default(&context->ch_layout, channels);
  }
  if (width > 0 && height > 0) {
    context->width = width;
    context->height = height;
  }
  uint8_t* extradata = nullptr;
  if (extraSize > 0) {
    extradata = (uint8_t*)av_mallocz(extraSize + AV_INPUT_BUFFER_PADDING_SIZE);
    if (!extradata) return false;
    memcpy(extradata, in + kCodecParHeaderSize, extraSize);
  }
  av_free(context->extradata);
  context->extradata = extradata;
  context->extradata_size = extraSize;
  return true;
}

}  // namespace mediadroid

#endif  // MEDIADROID_CODECPAR_H_
