import com.hawkynt.mediadroid.ffmpeg.FfmpegDemuxer;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;

/**
 * Prints what FfmpegDemuxer finds in each file — the container, every stream, how many packets and
 * keyframes each holds, where a seek to the middle lands, and the thumbnail picture there — for
 * comparison with ffprobe and with FFmpeg's own decode.
 */
public final class DemuxCheck {
  private static FfmpegDemuxer open(RandomAccessFile file) throws java.io.IOException {
    file.seek(0);
    return FfmpegDemuxer.open(
        new FfmpegDemuxer.Input() {
          @Override
          public int read(byte[] buffer, int offset, int length) throws java.io.IOException {
            return file.read(buffer, offset, length);
          }

          @Override
          public long seek(long position) throws java.io.IOException {
            file.seek(position);
            return position;
          }

          @Override
          public long length() throws java.io.IOException {
            return file.length();
          }
        });
  }

  public static void main(String[] args) throws Exception {
    for (String path : args) {
      try (RandomAccessFile file = new RandomAccessFile(path, "r")) {
        FfmpegDemuxer demuxer = open(file);
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (demuxer == null) {
          System.out.println(name + " NOT OPENED");
          continue;
        }
        FfmpegDemuxer.Stream[] streams = demuxer.streams();
        System.out.println(name + " format=" + demuxer.formatName() + " streams=" + streams.length);
        for (FfmpegDemuxer.Stream s : streams) {
          System.out.println(
              "  #" + s.index + " kind=" + s.kind + " codec=" + s.codecName + " decoder=" + s.decoderName
                  + " format=" + s.rawFormat
                  + " " + s.width + "x" + s.height + " " + s.sampleRate + "Hz " + s.channels + "ch"
                  + " params=" + s.parameters.length + "B"
                  + (s.pixelAspectRatio != 1f ? String.format(" pixel=%.4f", s.pixelAspectRatio) : ""));
        }
        long[] packets = new long[streams.length];
        long[] keys = new long[streams.length];
        ByteBuffer buffer = ByteBuffer.allocateDirect(4096);
        FfmpegDemuxer.Packet packet = new FfmpegDemuxer.Packet();
        ByteBuffer data;
        while ((data = demuxer.readPacket(buffer, packet)) != null) {
          buffer = data;
          packets[packet.streamIndex]++;
          if (packet.keyframe) keys[packet.streamIndex]++;
        }
        for (int i = 0; i < streams.length; i++) {
          System.out.println("  #" + i + " packets=" + packets[i] + " keyframes=" + keys[i]);
        }
        long middle = 0;
        if (demuxer.durationUs() == FfmpegDemuxer.TIME_UNSET) {
          System.out.println("  duration unknown: not seekable");
        } else {
          middle = demuxer.durationUs() / 2;
          boolean sought = demuxer.seekTo(middle);
          data = demuxer.readPacket(buffer, packet);
          System.out.println(
              "  seek " + middle / 1000 + "ms ok=" + sought + " next="
                  + (data == null ? "end" : (packet.timeUs / 1000) + "ms key=" + packet.keyframe));
        }
        demuxer.close();
        // The thumbnail, from a demuxer opened just for it as the app does: the picture from the
        // keyframe before the middle, written raw for the oracle.
        demuxer = open(file);
        int[] size = new int[2];
        int[] pixels = demuxer.frame(middle, 160, size);
        if (pixels == null) {
          System.out.println("  frame none");
        } else {
          System.out.println("  frame " + size[0] + "x" + size[1] + " at " + middle + "us");
          ByteBuffer raw = ByteBuffer.allocate(pixels.length * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
          raw.asIntBuffer().put(pixels);
          java.nio.file.Files.write(
              java.nio.file.Paths.get(System.getProperty("frames.dir"), name + ".bgra"), raw.array());
        }
        demuxer.close();
      }
    }
  }
}
