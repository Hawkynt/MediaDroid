package com.hawkynt.mediadroid.engine.desktop

import java.io.File

/**
 * Opens every video in a folder (the first argument) and decodes its first second: size, length, tracks, how many
 * pictures and how much sound came out, how long it took. `./gradlew :engine:desktop:probe -Pfolder=…`. A check by
 * eye over real files, not a test.
 */
fun main(args: Array<String>) {
    val folder = File(args.firstOrNull() ?: error("folder?"))
    val videos = folder.listFiles().orEmpty().filter { it.extension.lowercase() in VIDEO }.sortedBy { it.name }
    for (file in videos) {
        val started = System.nanoTime()
        val line = runCatching {
            MediaReader(file.path).use { r ->
                var pictures = 0
                var soundMs = 0L
                var last = -1L
                while (true) {
                    val item = r.read() ?: break
                    when (item) {
                        is MediaReader.Picture -> { pictures++; last = item.ptsMs }
                        is MediaReader.Sound -> soundMs += item.pcm.size / 4 * 1000L / MediaReader.SAMPLE_RATE
                    }
                    if (last >= 1000 || soundMs >= 3000 && pictures == 0 && r.width > 0) break
                }
                val kinds = r.tracks.groupingBy { it.kind }.eachCount()
                "${r.width}x${r.height} ${r.durationMs} ms, tracks $kinds, first second: $pictures pictures, " +
                    "$soundMs ms sound"
            }
        }.getOrElse { "FAILED: ${it.message}" }
        println("%-24s %s (%d ms)".format(file.name, line, (System.nanoTime() - started) / 1_000_000))
    }
}

private val VIDEO = setOf("mp4", "mkv", "avi", "mpg", "mpeg", "ts", "m2ts", "wmv", "asf", "rm", "rmvb", "dv", "y4m",
    "mov", "webm", "flv", "ogv", "vob", "3gp", "nut", "mxf")
