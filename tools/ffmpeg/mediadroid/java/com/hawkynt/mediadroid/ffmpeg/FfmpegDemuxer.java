// MediaDroid — LGPL-3.0-or-later.
package com.hawkynt.mediadroid.ffmpeg;

import androidx.annotation.Nullable;
import androidx.media3.decoder.ffmpeg.FfmpegLibrary;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * FFmpeg's demuxers over a random-access {@link Input}, for the containers Media3 has no extractor
 * for. A thin binding of {@code mediadroid_demux.cc} in the FFmpeg extension's native library; one
 * instance is used from one thread at a time.
 */
public final class FfmpegDemuxer implements AutoCloseable {

  /** The file a demuxer reads, with random access. */
  public interface Input {
    /** Reads up to {@code length} bytes into {@code buffer}; returns how many, or -1 at the end. */
    int read(byte[] buffer, int offset, int length) throws IOException;

    /** Moves to {@code position}; returns the position reached, or -1 when it cannot. */
    long seek(long position) throws IOException;

    /** The length in bytes, or -1 when unknown. */
    long length() throws IOException;
  }

  // LINT.IfChange
  public static final int RESULT_END_OF_INPUT = -1;
  public static final int RESULT_ERROR = -2;
  private static final int RESULT_BUFFER_TOO_SMALL = -3;
  public static final int KIND_VIDEO = 0;
  public static final int KIND_AUDIO = 1;
  public static final int KIND_SUBTITLE = 2;
  public static final int KIND_OTHER = 3;
  // LINT.ThenChange(../../../../../jni/mediadroid_demux.cc)

  /** No time known, as FFmpeg reports {@code AV_NOPTS_VALUE}. */
  public static final long TIME_UNSET = Long.MIN_VALUE;

  /** One stream of the file as FFmpeg found it. */
  public static final class Stream {
    public final int index;
    public final int kind;
    /** FFmpeg's codec name — {@code wmav2}, {@code rv40}, {@code dts}; null when unknown. */
    @Nullable public final String codecName;
    /** The decoder this build has for it — {@code dca} for DTS; null when there is none. */
    @Nullable public final String decoderName;
    /**
     * The pixel format (video: {@code yuv420p}, {@code yuv411p}, {@code yuv422p10le}) or sample format
     * (audio: {@code fltp}, {@code s16}) as FFmpeg names it; null when unknown.
     */
    @Nullable public final String rawFormat;
    @Nullable public final String language;
    public final int sampleRate;
    public final int channels;
    public final int width;
    public final int height;
    public final long durationUs;
    public final int rotationDegrees;
    public final long bitRate;
    /** Frames per second, or 0 when unknown. */
    public final float frameRate;
    public final boolean isDefault;
    /** Width over height of one pixel; 1 for square pixels or when unknown. */
    public final float pixelAspectRatio;
    /** The codec parameters for FFmpeg's decoders; see {@code mediadroid_codecpar.h}. */
    public final byte[] parameters;

    Stream(
        int index,
        long[] n,
        @Nullable String codecName,
        @Nullable String decoderName,
        @Nullable String rawFormat,
        @Nullable String language,
        byte[] parameters) {
      this.index = index;
      this.kind = (int) n[0];
      this.codecName = codecName;
      this.decoderName = decoderName;
      this.rawFormat = rawFormat;
      this.language = language;
      this.sampleRate = (int) n[1];
      this.channels = (int) n[2];
      this.width = (int) n[3];
      this.height = (int) n[4];
      this.durationUs = n[5];
      this.rotationDegrees = (int) n[6];
      this.bitRate = n[7];
      this.frameRate = n[8] > 0 && n[9] > 0 ? (float) n[8] / n[9] : 0f;
      this.isDefault = n[10] != 0;
      this.pixelAspectRatio = n[11] > 0 && n[12] > 0 ? (float) n[11] / n[12] : 1f;
      this.parameters = parameters;
    }
  }

  /** What {@link #readPacket} read: the stream, time, keyframe flag, size and file position after it. */
  public static final class Packet {
    public int streamIndex;
    public long timeUs;
    public boolean keyframe;
    public int size;
    public long position;
  }

  private final long handle;
  private final Stream[] streams;
  private final long[] packetInfo = new long[4];

  private FfmpegDemuxer(long handle) {
    this.handle = handle;
    int count = nStreamCount(handle);
    streams = new Stream[count];
    long[] numbers = new long[13];
    for (int i = 0; i < count; i++) {
      String codec = nStreamInfo(handle, i, numbers);
      streams[i] =
          new Stream(
              i,
              numbers.clone(),
              codec,
              nStreamDecoder(handle, i),
              nStreamFormat(handle, i),
              nStreamLanguage(handle, i),
              nStreamParameters(handle, i));
    }
  }

  /** Whether the native library is there to demux with. */
  public static boolean isAvailable() {
    return FfmpegLibrary.isAvailable();
  }

  /** Opens [input] when one of FFmpeg's demuxers recognises it; null otherwise. */
  @Nullable
  public static FfmpegDemuxer open(Input input) {
    if (!isAvailable()) return null;
    long handle = nOpen(input);
    return handle == 0 ? null : new FfmpegDemuxer(handle);
  }

  /** The demuxer FFmpeg chose, such as {@code asf} or {@code rm}. */
  @Nullable
  public String formatName() {
    return nFormatName(handle);
  }

  /** The whole file's duration, or {@link #TIME_UNSET}. */
  public long durationUs() {
    return nDurationUs(handle);
  }

  public Stream[] streams() {
    return streams.clone();
  }

  /**
   * Reads the next packet into {@code buffer} (direct, positioned at 0) and describes it in {@code
   * packet}. Returns the buffer the data is in — {@code buffer}, or a larger one when it did not
   * fit — or null at the end of the file.
   */
  @Nullable
  public ByteBuffer readPacket(ByteBuffer buffer, Packet packet) throws IOException {
    int result = nReadPacket(handle, buffer, packetInfo);
    if (result == RESULT_BUFFER_TOO_SMALL) {
      buffer = ByteBuffer.allocateDirect((int) packetInfo[2] * 3 / 2);
      result = nReadPacket(handle, buffer, packetInfo);
    }
    if (result == RESULT_END_OF_INPUT) return null;
    if (result < 0) throw new IOException("FFmpeg could not read the next packet.");
    packet.streamIndex = result;
    packet.timeUs = packetInfo[0];
    packet.keyframe = packetInfo[1] != 0;
    packet.size = (int) packetInfo[2];
    packet.position = packetInfo[3];
    buffer.limit(packet.size);
    buffer.position(0);
    return buffer;
  }

  /** Moves to the keyframe at or before {@code timeUs}; returns whether the container could. */
  public boolean seekTo(long timeUs) {
    return nSeek(handle, timeUs);
  }

  /**
   * The picture at about {@code timeUs} — the first one decoded from the keyframe at or before it —
   * at most {@code maxWidth} wide with the pixel aspect applied, as ARGB_8888 pixels; {@code size}
   * receives its width and height. Null when there is no picture. Moves the read position: meant for a
   * demuxer opened just for this.
   */
  @Nullable
  public int[] frame(long timeUs, int maxWidth, int[] size) {
    return nFrame(handle, timeUs, maxWidth, size);
  }

  @Override
  public void close() {
    nClose(handle);
  }

  private static native long nOpen(Input input);

  private static native String nFormatName(long handle);

  private static native long nDurationUs(long handle);

  private static native int nStreamCount(long handle);

  private static native String nStreamInfo(long handle, int index, long[] numbers);

  private static native String nStreamDecoder(long handle, int index);

  private static native String nStreamFormat(long handle, int index);

  private static native String nStreamLanguage(long handle, int index);

  private static native byte[] nStreamParameters(long handle, int index);

  private static native int nReadPacket(long handle, ByteBuffer buffer, long[] out);

  private static native boolean nSeek(long handle, long timeUs);

  private static native int[] nFrame(long handle, long timeUs, int maxWidth, int[] size);

  private static native void nClose(long handle);
}
