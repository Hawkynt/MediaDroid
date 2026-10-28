// Decodes every stream twice — once from the codec parameters FFmpeg's demuxer filled in, once from
// MediaDroid's codec-parameter blob alone, as the app's decoders receive it — and prints both frame
// counts and formats. They must match: the blob is all a decoder gets from FfmpegExtractor.
#include <stdio.h>

extern "C" {
#define __STDC_CONSTANT_MACROS
#include <libavformat/avformat.h>
}

#include "mediadroid_codecpar.h"

static AVCodecContext* openFrom(const AVStream* stream, bool viaBlob) {
  const AVCodec* codec = avcodec_find_decoder(stream->codecpar->codec_id);
  if (!codec) return nullptr;
  AVCodecContext* context = avcodec_alloc_context3(codec);
  if (viaBlob) {
    const std::vector<uint8_t> blob = mediadroid::packCodecParameters(stream->codecpar);
    context->extradata = (uint8_t*)av_mallocz(blob.size() + AV_INPUT_BUFFER_PADDING_SIZE);
    memcpy(context->extradata, blob.data(), blob.size());
    context->extradata_size = (int)blob.size();
    if (!mediadroid::applyCodecParameters(context)) return nullptr;
  } else {
    avcodec_parameters_to_context(context, stream->codecpar);
  }
  if (avcodec_open2(context, codec, nullptr) < 0) return nullptr;
  return context;
}

int main(int argc, char** argv) {
  for (int a = 1; a < argc; a++) {
    for (int pass = 0; pass < 2; pass++) {
      AVFormatContext* format = nullptr;
      if (avformat_open_input(&format, argv[a], nullptr, nullptr) < 0) continue;
      avformat_find_stream_info(format, nullptr);
      std::vector<AVCodecContext*> contexts(format->nb_streams);
      std::vector<long> frames(format->nb_streams, 0);
      for (unsigned i = 0; i < format->nb_streams; i++) contexts[i] = openFrom(format->streams[i], pass == 1);
      AVPacket* packet = av_packet_alloc();
      AVFrame* frame = av_frame_alloc();
      while (av_read_frame(format, packet) >= 0) {
        AVCodecContext* c = contexts[packet->stream_index];
        if (c && avcodec_send_packet(c, packet) >= 0) {
          while (avcodec_receive_frame(c, frame) >= 0) frames[packet->stream_index]++;
        }
        av_packet_unref(packet);
      }
      const char* name = strrchr(argv[a], '/') ? strrchr(argv[a], '/') + 1 : argv[a];
      for (unsigned i = 0; i < format->nb_streams; i++) {
        AVCodecContext* c = contexts[i];
        if (c) avcodec_send_packet(c, nullptr);
        while (c && avcodec_receive_frame(c, frame) >= 0) frames[i]++;
        printf("%s #%u %s %s frames=%ld %dx%d %dHz %dch\n", name, i, pass ? "blob" : "ffmpeg",
               avcodec_get_name(format->streams[i]->codecpar->codec_id), c ? frames[i] : -1L,
               c ? c->width : 0, c ? c->height : 0, c ? c->sample_rate : 0, c ? c->ch_layout.nb_channels : 0);
        avcodec_free_context(&c);
      }
      av_frame_free(&frame);
      av_packet_free(&packet);
      avformat_close_input(&format);
    }
  }
  return 0;
}
