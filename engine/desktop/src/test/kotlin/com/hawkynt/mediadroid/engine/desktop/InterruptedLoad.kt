package com.hawkynt.mediadroid.engine.desktop

/** The child JVM of [FfmpegNativesTest]. */
fun main() {
    Thread.currentThread().interrupt()
    FfmpegNatives.load()
    val interrupted = Thread.interrupted()
    check(org.bytedeco.ffmpeg.global.swscale.swscale_version() > 0)
    check(org.bytedeco.ffmpeg.global.avfilter.avfilter_version() > 0)
    println(if (interrupted) "loaded, still interrupted" else "loaded, interrupt lost")
}
