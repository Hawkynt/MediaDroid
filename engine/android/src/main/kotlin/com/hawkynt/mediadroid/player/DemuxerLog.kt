package com.hawkynt.mediadroid.player

import java.util.concurrent.ConcurrentHashMap

/**
 * Which demuxer reads each file, for the player's info sheet: the class name of the Media3 extractor
 * that took it, or `ffmpeg:<format>` when FFmpeg's demuxer did. Keyed by the location as played.
 */
object DemuxerLog {
    private val chosen = ConcurrentHashMap<String, String>()

    fun record(location: String, demuxer: String) {
        chosen[location] = demuxer
    }

    fun of(location: String): String? = chosen[location]

    private val formats = ConcurrentHashMap<String, String>()

    /** FFmpeg's pixel or sample format of the [kind] (`video`, `audio`) stream it demuxed from [location]. */
    fun recordFormat(location: String, kind: String, format: String) {
        formats.putIfAbsent("$kind@$location", format)
    }

    fun formatOf(location: String, kind: String): String? = formats["$kind@$location"]
}
