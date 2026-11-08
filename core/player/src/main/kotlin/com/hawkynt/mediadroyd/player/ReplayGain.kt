package com.hawkynt.mediadroyd.player

import kotlin.math.log10

/**
 * A file's ReplayGain: how far to turn it to the ReplayGain 2.0 reference of -18 LUFS, per track and per album, and
 * the peaks that gain must not push past full scale. Read from the tags taggers write (the ReplayGain 2.0
 * specification on the Hydrogenaudio wiki): `REPLAYGAIN_TRACK_GAIN` "-6.48 dB", `REPLAYGAIN_TRACK_PEAK` "0.988769"
 * and the album pair, in Vorbis comments, ID3 TXXX frames, APE tags and iTunes freeform atoms alike — and Opus's
 * `R128_TRACK_GAIN` / `R128_ALBUM_GAIN` (RFC 7845 5.2.1), a Q7.8 number of dB to -23 LUFS.
 */
data class ReplayGain(
    val trackGainDb: Float?,
    val trackPeak: Float?,
    val albumGainDb: Float?,
    val albumPeak: Float?,
) {

    /**
     * The gain to play this file with: the track's, else the album's, held back so its peak stays at full scale at
     * most (as mpv does with `replaygain-clip=no`).
     */
    val gainDb: Float
        get() {
            val (gain, peak) = if (trackGainDb != null) trackGainDb to trackPeak else (albumGainDb ?: 0f) to albumPeak
            return if (peak != null && peak > 0f) minOf(gain, -20f * log10(peak)) else gain
        }

    companion object {
        /** RFC 7845's -23 LUFS against ReplayGain 2.0's -18 LUFS. */
        private const val R128_TO_REPLAYGAIN_DB = 5f

        /** The ReplayGain in a file's tags (key, value) — keys in any case, behind any domain — or null without one. */
        fun parse(tags: Iterable<Pair<String, String>>): ReplayGain? {
            var trackGain: Float? = null
            var trackPeak: Float? = null
            var albumGain: Float? = null
            var albumPeak: Float? = null
            var r128Track: Float? = null
            var r128Album: Float? = null
            for ((key, value) in tags) {
                when (key.substringAfterLast(':').trim().uppercase()) {
                    "REPLAYGAIN_TRACK_GAIN" -> trackGain = decibels(value) ?: trackGain
                    "REPLAYGAIN_TRACK_PEAK" -> trackPeak = number(value) ?: trackPeak
                    "REPLAYGAIN_ALBUM_GAIN" -> albumGain = decibels(value) ?: albumGain
                    "REPLAYGAIN_ALBUM_PEAK" -> albumPeak = number(value) ?: albumPeak
                    "R128_TRACK_GAIN" -> r128Track = q78(value) ?: r128Track
                    "R128_ALBUM_GAIN" -> r128Album = q78(value) ?: r128Album
                }
            }
            val track = trackGain ?: r128Track
            val album = albumGain ?: r128Album
            if (track == null && album == null) return null
            return ReplayGain(track, trackPeak, album, albumPeak)
        }

        private fun number(text: String): Float? = text.trim().replace(',', '.').toFloatOrNull()

        private fun decibels(text: String): Float? = number(text.trim().removeSuffix("dB").removeSuffix("DB"))

        private fun q78(text: String): Float? = text.trim().toIntOrNull()?.let { it / 256f + R128_TO_REPLAYGAIN_DB }
    }
}
