import com.hawkynt.mediadroid.ffmpeg.FfmpegDemuxer;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;

/**
 * Prints what FfmpegDemuxer finds in each file — the container, every stream, how many packets and
 * keyframes each holds, and where a seek to the middle lands — for comparison with ffprobe.
 */
public final class DemuxCheck {
  public static void main(String[] args) throws Exception {
    for (String path : args) {
      try (RandomAccessFile file = new RandomAccessFile(path, "r")) {
        FfmpegDemuxer demuxer =
            FfmpegDemuxer.open(
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
        if (demuxer.durationUs() == FfmpegDemuxer.TIME_UNSET) {
          System.out.println("  duration unknown: not seekable");
          demuxer.close();
          continue;
        }
        long middle = demuxer.durationUs() / 2;
        boolean sought = demuxer.seekTo(middle);
        data = demuxer.readPacket(buffer, packet);
        System.out.println(
            "  seek " + middle / 1000 + "ms ok=" + sought + " next="
                + (data == null ? "end" : (packet.timeUs / 1000) + "ms key=" + packet.keyframe));
        demuxer.close();
      }
    }
  }
}
