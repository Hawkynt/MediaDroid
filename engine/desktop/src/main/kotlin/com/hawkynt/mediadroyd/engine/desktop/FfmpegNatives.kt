package com.hawkynt.mediadroyd.engine.desktop

import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avfilter
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.swresample
import org.bytedeco.ffmpeg.global.swscale

/**
 * FFmpeg's natives, loaded once per JVM before anything uses them. JavaCPP locks its cache folder while it loads a
 * library, and that lock gives up when the loading thread is interrupted (FileLockInterruptionException) — and a
 * class whose loading failed stays unusable for the rest of the JVM. The playback engine stops its reader thread with
 * an interrupt, so a video closed while FFmpeg was still loading on that thread broke every later one (seen on
 * Windows, where loading is slow). Here they load with the interrupt held back, and on the thread that opens a video,
 * before any reader thread exists.
 */
object FfmpegNatives {

    @Volatile
    private var loaded = false

    /** Loads every FFmpeg library the engine uses; the calling thread's interrupt, if any, is kept for afterwards. */
    fun load() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val interrupted = Thread.interrupted()
            try {
                // Touching each one runs its class initializer, which loads its library and those it needs.
                avutil.avutil_version()
                swresample.swresample_version()
                swscale.swscale_version()
                avcodec.avcodec_version()
                avformat.avformat_version()
                avfilter.avfilter_version()
                loaded = true
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }
}
