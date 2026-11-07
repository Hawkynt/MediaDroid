import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.util.ArrayList;
import java.util.List;

/** Measures Java Sound's played-position clock the way JavaSoundSink uses it: 48 kHz stereo 16-bit, 1 s buffer. */
public class JavaSoundProbe {
  public static void main(String[] args) throws Exception {
    int rate = 48_000, frameBytes = 4;
    AudioFormat format = new AudioFormat(rate, 16, 2, true, false);
    SourceDataLine line = AudioSystem.getSourceDataLine(format);
    System.out.println("line: " + line.getClass().getName() + " mixer: " + AudioSystem.getMixerInfo()[0]);
    line.open(format, rate * frameBytes);
    System.out.println("buffer bytes: " + line.getBufferSize());
    byte[] chunk = new byte[rate * frameBytes / 100]; // 10 ms of silence
    // Fill the buffer before start, as the engine does.
    while (line.available() >= chunk.length) line.write(chunk, 0, chunk.length);
    Thread writer = new Thread(() -> {
      while (!Thread.currentThread().isInterrupted()) {
        line.write(chunk, 0, chunk.length);
      }
    });
    long t0 = System.nanoTime();
    line.start();
    writer.setDaemon(true);
    writer.start();
    // Time until the position first moves.
    long firstMove = -1;
    List<long[]> samples = new ArrayList<>();
    long last = line.getMicrosecondPosition();
    long end = t0 + 10_000_000_000L;
    while (System.nanoTime() < end) {
      long now = System.nanoTime();
      long pos = line.getMicrosecondPosition();
      if (firstMove < 0 && pos != last) firstMove = now - t0;
      samples.add(new long[]{now - t0, pos});
      last = pos;
      Thread.sleep(1);
    }
    // Steps: how far the position jumps when it moves, and how often.
    List<Long> steps = new ArrayList<>();
    long prev = samples.get(0)[1];
    for (long[] s : samples) {
      if (s[1] != prev) { steps.add(s[1] - prev); prev = s[1]; }
    }
    steps.sort(null);
    System.out.printf(java.util.Locale.ROOT, "first move after %.1f ms%n", firstMove / 1e6);
    System.out.printf(java.util.Locale.ROOT, "position updates: %d in 10 s; step median %d us, p95 %d us, max %d us%n",
      steps.size(), steps.get(steps.size() / 2), steps.get(steps.size() * 95 / 100), steps.get(steps.size() - 1));
    // Linear fit of position against wall time over the last 8 s: rate and residual.
    double n = 0, sx = 0, sy = 0, sxx = 0, sxy = 0;
    for (long[] s : samples) {
      if (s[0] < 2_000_000_000L) continue;
      double x = s[0] / 1e3, y = s[1];
      n++; sx += x; sy += y; sxx += x * x; sxy += x * y;
    }
    double slope = (n * sxy - sx * sy) / (n * sxx - sx * sx);
    double icpt = (sy - slope * sx) / n;
    double maxRes = 0, sumSq = 0;
    for (long[] s : samples) {
      if (s[0] < 2_000_000_000L) continue;
      double r = s[1] - (slope * s[0] / 1e3 + icpt);
      maxRes = Math.max(maxRes, Math.abs(r));
      sumSq += r * r;
    }
    System.out.printf(java.util.Locale.ROOT, "rate against the wall clock: %.5f; residual rms %.2f ms, max %.2f ms%n",
      slope, Math.sqrt(sumSq / n) / 1e3, maxRes / 1e3);
    // Pause: does the position stand still, and flush keep it?
    line.stop();
    long a = line.getMicrosecondPosition();
    Thread.sleep(300);
    long b = line.getMicrosecondPosition();
    System.out.printf(java.util.Locale.ROOT, "paused 300 ms: position moved %.2f ms%n", (b - a) / 1e3);
    writer.interrupt();
    line.flush();
    System.out.printf(java.util.Locale.ROOT, "after flush: position %s%n", line.getMicrosecondPosition() >= b ? "kept (not reset)" : "reset");
    line.close();
  }
}
