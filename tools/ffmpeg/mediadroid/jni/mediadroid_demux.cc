// MediaDroid — LGPL-3.0-or-later.
//
// FFmpeg's demuxers for the containers Media3 cannot read (ASF/WMV, RealMedia, DV, NUT, MXF, …),
// behind the JNI of com.hawkynt.mediadroid.ffmpeg.FfmpegDemuxer. The file is read through a custom
// AVIOContext whose read and seek callbacks call the Java Input the demuxer was opened with, the
// pattern of FFmpeg's own doc/examples/avio_read_callback.c. FFmpeg calls back only from inside the
// JNI calls below, on the calling thread, so the JNIEnv of the current call is the one to use.

#include <jni.h>
#include <stdint.h>
#include <string.h>

#include <vector>

extern "C" {
#define __STDC_CONSTANT_MACROS
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/display.h>
#include <libavutil/mathematics.h>
#include <libavutil/pixdesc.h>
#include <libswscale/swscale.h>
}

#include "mediadroid_codecpar.h"

namespace {

const int kIoBufferSize = 64 * 1024;

// LINT.IfChange — the results and stream kinds FfmpegDemuxer.java reads.
const jint kResultEndOfInput = -1;
const jint kResultError = -2;
const jint kResultBufferTooSmall = -3;
const jint kKindVideo = 0;
const jint kKindAudio = 1;
const jint kKindSubtitle = 2;
const jint kKindOther = 3;
// LINT.ThenChange(../java/com/hawkynt/mediadroid/ffmpeg/FfmpegDemuxer.java)

struct Demuxer {
  JNIEnv* env = nullptr;
  jobject input = nullptr;
  jmethodID readMethod = nullptr;
  jmethodID seekMethod = nullptr;
  jmethodID lengthMethod = nullptr;
  jbyteArray scratch = nullptr;
  int64_t position = 0;
  AVIOContext* io = nullptr;
  AVFormatContext* format = nullptr;
  AVPacket* packet = nullptr;
  bool packetPending = false;
};

int readCallback(void* opaque, uint8_t* buffer, int size) {
  Demuxer* d = static_cast<Demuxer*>(opaque);
  JNIEnv* env = d->env;
  const int wanted = size < kIoBufferSize ? size : kIoBufferSize;
  const jint read = env->CallIntMethod(d->input, d->readMethod, d->scratch, 0, wanted);
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    return AVERROR(EIO);
  }
  if (read <= 0) return AVERROR_EOF;
  env->GetByteArrayRegion(d->scratch, 0, read, reinterpret_cast<jbyte*>(buffer));
  d->position += read;
  return read;
}

int64_t seekCallback(void* opaque, int64_t offset, int whence) {
  Demuxer* d = static_cast<Demuxer*>(opaque);
  JNIEnv* env = d->env;
  const jlong length = env->CallLongMethod(d->input, d->lengthMethod);
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    return -1;
  }
  if (whence & AVSEEK_SIZE) return length >= 0 ? length : -1;
  int64_t target;
  switch (whence & ~AVSEEK_FORCE) {
    case SEEK_SET:
      target = offset;
      break;
    case SEEK_CUR:
      target = d->position + offset;
      break;
    case SEEK_END:
      if (length < 0) return -1;
      target = length + offset;
      break;
    default:
      return -1;
  }
  const jlong reached = env->CallLongMethod(d->input, d->seekMethod, (jlong)target);
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    return -1;
  }
  if (reached < 0) return -1;
  d->position = reached;
  return reached;
}

void release(JNIEnv* env, Demuxer* d) {
  if (d->packet) av_packet_free(&d->packet);
  if (d->format) avformat_close_input(&d->format);
  if (d->io) {
    av_freep(&d->io->buffer);
    avio_context_free(&d->io);
  }
  if (d->scratch) env->DeleteGlobalRef(d->scratch);
  if (d->input) env->DeleteGlobalRef(d->input);
  delete d;
}

Demuxer* demuxerOf(JNIEnv* env, jlong handle) {
  Demuxer* d = reinterpret_cast<Demuxer*>(handle);
  if (d) d->env = env;
  return d;
}

AVStream* streamOf(Demuxer* d, jint index) {
  if (!d || index < 0 || (unsigned)index >= d->format->nb_streams) return nullptr;
  return d->format->streams[index];
}

int64_t toUs(int64_t time, AVRational base) {
  return time == AV_NOPTS_VALUE ? INT64_MIN : av_rescale_q(time, base, AV_TIME_BASE_Q);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_hawkynt_mediadroid_ffmpeg_FfmpegDemuxer_nOpen(
    JNIEnv* env, jclass, jobject input) {
  Demuxer* d = new Demuxer();
  d->env = env;
  d->input = env->NewGlobalRef(input);
  jclass inputClass = env->GetObjectClass(input);
  d->readMethod = env->GetMethodID(inputClass, "read", "([BII)I");
  d->seekMethod = env->GetMethodID(inputClass, "seek", "(J)J");
  d->lengthMethod = env->GetMethodID(inputClass, "length", "()J");
  if (!d->readMethod || !d->seekMethod || !d->lengthMethod) {
    env->ExceptionClear();
    release(env, d);
    return 0;
  }
  d->scratch = static_cast<jbyteArray>(env->NewGlobalRef(env->NewByteArray(kIoBufferSize)));
  uint8_t* buffer = static_cast<uint8_t*>(av_malloc(kIoBufferSize));
  d->io = avio_alloc_context(buffer, kIoBufferSize, 0, d, readCallback, nullptr, seekCallback);
  d->format = avformat_alloc_context();
  d->packet = av_packet_alloc();
  if (!buffer || !d->io || !d->format || !d->packet) {
    if (buffer && !d->io) av_free(buffer);
    release(env, d);
    return 0;
  }
  d->format->pb = d->io;
  d->format->flags |= AVFMT_FLAG_CUSTOM_IO;
  if (avformat_open_input(&d->format, nullptr, nullptr, nullptr) < 0) {
    // avformat_open_input frees the context on failure.
    d->format = nullptr;
    release(env, d);
    return 0;
  }
  if (avformat_find_stream_info(d->format, nullptr) < 0) {
    release(env, d);
    return 0;
  }
  return reinterpret_cast<jlong>(d);
}

JNIEXPORT jstring JNICALL Java_com_hawkynt_mediadroid_ffmpeg_FfmpegDemuxer_nFormatName(
    JNIEnv* env, jclass, jlong handle) {
  Demuxer* d = demuxerOf(env, handle);
  return d && d->format->iformat ? env->NewStringUTF(d->format->iformat->name) : nullptr;
}

JNIEXPORT jlong JNICALL Java_com_hawkynt_mediadroid_ffmpeg_FfmpegDemuxer_nDurationUs(
    JNIEnv* env, jclass, jlong handle) {
  Demuxer* d = demuxerOf(env, handle);
  return d && d->format->duration != AV_NOPTS_VALUE ? d->format->duration : INT64_MIN;
}

JNIEXPORT jint JNICALL Java_com_hawkynt_mediadroid_ffmpeg_FfmpegDemuxer_nStreamCount(
    JNIEnv* env, jclass, jlong handle) {
  Demuxer* d = demuxerOf(env, handle);
  return d ? (jint)d->format->nb_streams : 0;
}

// numbers: kind, sampleRate, channels, width, height, durationUs, rotationDegrees, bitRate,
// frameRateNum, frameRateDen, default (1/0), sampleAspectNum, sampleAspectDen. Returns the codec name.
JNIEXPORT jstring JNICALL Java_com_hawkynt_mediadroid_ffmpeg_FfmpegDemuxer_nStreamInfo(
    JNIEnv* env, jclass, jlong handle, jint index, jlongArray numbers) {
  Demuxer* d = demuxerOf(env, handle);
  AVStream* stream = streamOf(d, index);
  if (!stream) return nullptr;
  const AVCodecParameters* par = stream->codecpar;
  jint kind;
  switch (par->codec_type) {
    case AVMEDIA_TYPE_VIDEO:
      kind = (stream->disposition & AV_DISPOSITION_ATTACHED_PIC) ? kKindOther : kKindVideo;
      break;
    case AVMEDIA_TYPE_AUDIO:
      kind = kKindAudio;
      break;
    case AVMEDIA_TYPE_SUBTITLE:
      kind = kKindSubtitle;
      break;
    default:
      kind = kKindOther;
  }
  jlong rotation = 0;
  const uint8_t* matrix = av_stream_get_side_data(stream, AV_PKT_DATA_DISPLAYMATRIX, nullptr);
  if (matrix) {
    // av_display_rotation_get counts counter-clockwise; Media3 wants clockwise degrees.
    const double degrees = -av_display_rotation_get(reinterpret_cast<const int32_t*>(matrix));
    rotation = ((jlong)(degrees + (degrees < 0 ? -0.5 : 0.5)) % 360 + 360) % 360;
  }
  const AVRational rate = stream->avg_frame_rate.num > 0 ? stream->avg_frame_rate : stream->r_frame_rate;
  // The shape of a pixel: PAL DV is 720x576 drawn 4:3, anamorphic MXF and ASF video likewise.
  const AVRational aspect = av_guess_sample_aspect_ratio(d->format, stream, nullptr);
  const jlong values[13] = {
      kind,
      par->sample_rate,
      par->ch_layout.nb_channels,
      par->width,
      par->height,
      toUs(stream->duration, stream->time_base),
      rotation,
      par->bit_rate,
      rate.num,
      rate.den,
      (stream->disposition & AV_DISPOSITION_DEFAULT) ? 1 : 0,
      aspect.num,
      aspect.den,
  };
  env->SetLongArrayRegion(numbers, 0, 13, values);
  const AVCodecDescriptor* descriptor = avcodec_descriptor_get(par->codec_id);
  return descriptor ? env->NewStringUTF(descriptor->name) : nullptr;
}

// The name of the decoder this build has for the stream (avcodec_find_decoder_by_name finds it by
// this name, which is not always the codec's: DTS is decoded by "dca"); null when there is none.
JNIEXPORT jstring JNICALL Java_com_hawkynt_mediadroid_ffmpeg_FfmpegDemuxer_nStreamDecoder(
    JNIEnv* env, jclass, jlong handle, jint index) {
  AVStream* stream = streamOf(demuxerOf(env, handle), index);
  if (!stream) return nullptr;
  const AVCodec* decoder = avcodec_find_decoder(stream->codecpar->codec_id);
  return decoder ? env->NewStringUTF(decoder->name) : nullptr;
}

// The stream's pixel format (video, as yuv420p or yuv422p10le) or sample format (audio, as fltp or
// s16), as FFmpeg names it; null when unknown.
JNIEXPORT jstring JNICALL Java_com_hawkynt_mediadroid_ffmpeg_FfmpegDemuxer_nStreamFormat(
    JNIEnv* env, jclass, jlong handle, jint index) {
  AVStream* stream = streamOf(demuxerOf(env, handle), index);
  if (!stream || stream->codecpar->format < 0) return nullptr;
  const char* name = nullptr;
  if (stream->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
    name = av_get_pix_fmt_name((AVPixelFormat)stream->codecpar->format);
  } else if (stream->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
    name = av_get_sample_fmt_name((AVSampleFormat)stream->codecpar->format);
  }
  return name ? env->NewStringUTF(name) : nullptr;
}

JNIEXPORT jstring JNICALL Java_com_hawkynt_mediadroid_ffmpeg_FfmpegDemuxer_nStreamLanguage(
    JNIEnv* env, jclass, jlong handle, jint index) {
  AVStream* stream = streamOf(demuxerOf(env, handle), index);
  if (!stream) return nullptr;
  const AVDictionaryEntry* language = av_dict_get(stream->metadata, "language", nullptr, 0);
  return language && language->value[0] ? env->NewStringUTF(language->value) : nullptr;
}

JNIEXPORT jbyteArray JNICALL Java_com_hawkynt_mediadroid_ffmpeg_FfmpegDemuxer_nStreamParameters(
    JNIEnv* env, jclass, jlong handle, jint index) {
  AVStream* stream = streamOf(demuxerOf(env, handle), index);
  if (!stream) return nullptr;
  const std::vector<uint8_t> blob = mediadroid::packCodecParameters(stream->codecpar);
  jbyteArray out = env->NewByteArray((jsize)blob.size());
  env->SetByteArrayRegion(out, 0, (jsize)blob.size(), reinterpret_cast<const jbyte*>(blob.data()));
  return out;
}

// Reads the next packet into [buffer] (a direct ByteBuffer). Returns its stream index, or one of the
// results above; out: timeUs, keyframe (1/0), size, byte position in the file after it.
JNIEXPORT jint JNICALL Java_com_hawkynt_mediadroid_ffmpeg_FfmpegDemuxer_nReadPacket(
    JNIEnv* env, jclass, jlong handle, jobject buffer, jlongArray out) {
  Demuxer* d = demuxerOf(env, handle);
  if (!d) return kResultError;
  if (!d->packetPending) {
    const int result = av_read_frame(d->format, d->packet);
    if (result == AVERROR_EOF) return kResultEndOfInput;
    if (result < 0) return d->io->eof_reached ? kResultEndOfInput : kResultError;
    d->packetPending = true;
  }
  AVPacket* packet = d->packet;
  const AVStream* stream = d->format->streams[packet->stream_index];
  const int64_t time = packet->pts != AV_NOPTS_VALUE ? packet->pts : packet->dts;
  int64_t timeUs = toUs(time, stream->time_base);
  if (timeUs != INT64_MIN && stream->start_time != AV_NOPTS_VALUE) {
    timeUs -= toUs(stream->start_time, stream->time_base);
  }
  const jlong values[4] = {
      timeUs,
      (packet->flags & AV_PKT_FLAG_KEY) ? 1 : 0,
      packet->size,
      avio_tell(d->io),
  };
  env->SetLongArrayRegion(out, 0, 4, values);
  const jlong capacity = env->GetDirectBufferCapacity(buffer);
  if (capacity < packet->size) return kResultBufferTooSmall;
  uint8_t* target = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
  if (!target) return kResultError;
  memcpy(target, packet->data, packet->size);
  const jint index = packet->stream_index;
  av_packet_unref(packet);
  d->packetPending = false;
  return index;
}

JNIEXPORT jboolean JNICALL Java_com_hawkynt_mediadroid_ffmpeg_FfmpegDemuxer_nSeek(
    JNIEnv* env, jclass, jlong handle, jlong timeUs) {
  Demuxer* d = demuxerOf(env, handle);
  if (!d) return JNI_FALSE;
  if (d->packetPending) {
    av_packet_unref(d->packet);
    d->packetPending = false;
  }
  int64_t target = timeUs;
  if (d->format->start_time != AV_NOPTS_VALUE) target += d->format->start_time;
  // The keyframe at or before the target, else whatever the container can reach.
  if (av_seek_frame(d->format, -1, target, AVSEEK_FLAG_BACKWARD) >= 0) return JNI_TRUE;
  return avformat_seek_file(d->format, -1, INT64_MIN, target, INT64_MAX, 0) >= 0 ? JNI_TRUE : JNI_FALSE;
}

// The first picture of the main video stream from the keyframe at or before timeUs, scaled to at
// most maxWidth wide with the pixel aspect applied, as BGRA — in memory the ARGB_8888 ints Android's
// Bitmap takes. size receives width and height. Null without a picture.
JNIEXPORT jintArray JNICALL Java_com_hawkynt_mediadroid_ffmpeg_FfmpegDemuxer_nFrame(
    JNIEnv* env, jclass clazz, jlong handle, jlong timeUs, jint maxWidth, jintArray size) {
  Demuxer* d = demuxerOf(env, handle);
  if (!d || maxWidth <= 0) return nullptr;
  const int index = av_find_best_stream(d->format, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
  if (index < 0) return nullptr;
  AVStream* stream = d->format->streams[index];
  const AVCodec* codec = avcodec_find_decoder(stream->codecpar->codec_id);
  if (!codec) return nullptr;
  AVCodecContext* context = avcodec_alloc_context3(codec);
  AVFrame* frame = av_frame_alloc();
  AVPacket* packet = av_packet_alloc();
  jintArray pixels = nullptr;
  bool decoded = false;
  if (context && frame && packet && avcodec_parameters_to_context(context, stream->codecpar) >= 0 &&
      avcodec_open2(context, codec, nullptr) >= 0) {
    if (timeUs > 0) {
      int64_t target = timeUs;
      if (d->format->start_time != AV_NOPTS_VALUE) target += d->format->start_time;
      // The keyframe closest before the target, as ffmpeg's own -ss seeks; an index-only seek
      // (nSeek's) can land far earlier in ASF and WTV.
      if (avformat_seek_file(d->format, -1, INT64_MIN, target, target, 0) < 0) {
        Java_com_hawkynt_mediadroid_ffmpeg_FfmpegDemuxer_nSeek(env, clazz, handle, timeUs);
      }
    }
    // A keyframe-only seek can land a long way before the picture: give up after this many packets.
    for (int packets = 0; !decoded && packets < 4096; packets++) {
      const int read = av_read_frame(d->format, packet);
      if (read < 0) {
        avcodec_send_packet(context, nullptr);
        decoded = avcodec_receive_frame(context, frame) == 0;
        break;
      }
      if (packet->stream_index == index && avcodec_send_packet(context, packet) >= 0) {
        decoded = avcodec_receive_frame(context, frame) == 0;
      }
      av_packet_unref(packet);
    }
  }
  if (decoded && frame->width > 0 && frame->height > 0) {
    const AVRational aspect = av_guess_sample_aspect_ratio(d->format, stream, frame);
    double shownWidth = frame->width;
    if (aspect.num > 0 && aspect.den > 0) shownWidth = frame->width * av_q2d(aspect);
    const int width = shownWidth > maxWidth ? maxWidth : (int)(shownWidth + 0.5);
    int height = (int)(frame->height * (double)width / shownWidth + 0.5);
    if (height < 1) height = 1;
    SwsContext* scaler = sws_getContext(frame->width, frame->height, (AVPixelFormat)frame->format, width,
                                        height, AV_PIX_FMT_BGRA, SWS_BILINEAR, nullptr, nullptr, nullptr);
    if (scaler) {
      pixels = env->NewIntArray(width * height);
      jint* out = pixels ? env->GetIntArrayElements(pixels, nullptr) : nullptr;
      if (out) {
        uint8_t* planes[4] = {reinterpret_cast<uint8_t*>(out), nullptr, nullptr, nullptr};
        int strides[4] = {width * 4, 0, 0, 0};
        sws_scale(scaler, frame->data, frame->linesize, 0, frame->height, planes, strides);
        env->ReleaseIntArrayElements(pixels, out, 0);
        const jint dimensions[2] = {width, height};
        env->SetIntArrayRegion(size, 0, 2, dimensions);
      } else {
        pixels = nullptr;
      }
      sws_freeContext(scaler);
    }
  }
  av_packet_free(&packet);
  av_frame_free(&frame);
  avcodec_free_context(&context);
  return pixels;
}

JNIEXPORT void JNICALL Java_com_hawkynt_mediadroid_ffmpeg_FfmpegDemuxer_nClose(
    JNIEnv* env, jclass, jlong handle) {
  Demuxer* d = demuxerOf(env, handle);
  if (d) release(env, d);
}

}  // extern "C"
