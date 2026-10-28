package androidx.media3.decoder.ffmpeg;

/**
 * Host stand-in for Media3's FfmpegLibrary: loads the host build of the JNI library named by the
 * {@code ffmpeg.lib} system property, so FfmpegDemuxer.java runs unchanged on a desktop JVM.
 */
public final class FfmpegLibrary {
  private static boolean loaded;

  private FfmpegLibrary() {}

  public static synchronized boolean isAvailable() {
    if (!loaded) {
      System.load(System.getProperty("ffmpeg.lib"));
      loaded = true;
    }
    return true;
  }
}
