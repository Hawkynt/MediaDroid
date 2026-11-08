package com.hawkynt.mediadroyd.desktop

import com.hawkynt.mediadroyd.engine.desktop.MediaProbe
import com.hawkynt.mediadroyd.library.Languages
import com.hawkynt.mediadroyd.library.Quality
import com.hawkynt.mediadroyd.nfo.AudioStream
import com.hawkynt.mediadroyd.nfo.StreamDetails
import com.hawkynt.mediadroyd.player.PlaybackChain
import com.hawkynt.mediadroyd.player.Teletext
import com.hawkynt.mediadroyd.player.ToneMap
import java.util.Locale

/**
 * What FFmpeg reads of a file ([MediaProbe]) in the words the shared screens use: the stream details its badges come
 * from, in Kodi's codec names, and the tracks its details' File section lists, worded as the Android app words them.
 */
object MediaFacts {

    /**
     * The Kodi `<streamdetails>` codec of [stream]: FFmpeg's own name, which Kodi's are, but for the extensions of DTS
     * and Dolby's formats, which FFmpeg tells by the profile and Kodi names apart.
     */
    fun kodiCodec(stream: MediaProbe.Stream): String = when (stream.profile) {
        "DTS-HD MA" -> "dtshd_ma"
        "DTS-HD HRA" -> "dtshd_hra"
        "DTS-HD MA + DTS:X" -> "dtshd_ma_x"
        "DTS-HD MA + DTS:X IMAX" -> "dtshd_ma_x_imax"
        "Dolby TrueHD + Dolby Atmos" -> "truehd_atmos"
        "Dolby Digital Plus + Dolby Atmos" -> "eac3_ddp_atmos"
        else -> stream.codec
    }

    /** The stream details of [facts]: its first picture's size, codec and HDR, and every sound; null where unread. */
    fun details(facts: MediaProbe.Facts?): StreamDetails? {
        facts ?: return null
        val video = facts.streams.firstOrNull { it.kind == MediaProbe.Kind.VIDEO }
        return StreamDetails(
            video?.width?.takeIf { it > 0 },
            video?.height?.takeIf { it > 0 },
            video?.let(::kodiCodec),
            when (video?.hdr) {
                ToneMap.Transfer.PQ -> "hdr10"
                ToneMap.Transfer.HLG -> "hlg"
                else -> null
            },
            facts.streams.filter { it.kind == MediaProbe.Kind.AUDIO }
                .map { AudioStream(kodiCodec(it), it.channels.takeIf { c -> c > 0 }, it.language) },
        )
    }

    /** Every track of [facts], `Video: H.264 · High · 1920 × 1080 · 25 fps`: video, then sound, then subtitles. */
    fun tracks(facts: MediaProbe.Facts): List<String> =
        facts.streams.sortedBy { it.kind.ordinal }.map { stream ->
            when (stream.kind) {
                MediaProbe.Kind.VIDEO -> "Video: " + video(stream)
                MediaProbe.Kind.AUDIO -> "Audio: " + audio(stream)
                MediaProbe.Kind.SUBTITLE -> "Subtitles: " + subtitle(stream)
            }
        }

    private fun video(stream: MediaProbe.Stream): String {
        val codec = kodiCodec(stream)
        val parts = mutableListOf(
            Quality.of(StreamDetails(stream.width, stream.height, codec, null, emptyList())).video ?: codec,
        )
        stream.profile?.let { parts += it }
        if (stream.width > 0) parts += "${stream.width} × ${stream.height}"
        if (stream.frameRate > 0) {
            parts += String.format(Locale.ROOT, "%.3f", stream.frameRate).trimEnd('0').trimEnd('.') + " fps"
        }
        return (parts + named(stream)).joinToString(" · ")
    }

    private fun audio(stream: MediaProbe.Stream): String {
        val codec = kodiCodec(stream)
        val channels = stream.channels.takeIf { it > 0 }
        val shown = Quality.of(StreamDetails(null, null, null, null, listOf(AudioStream(codec, channels, null))))
        return (listOfNotNull(shown.audio ?: codec, shown.channels) + named(stream)).joinToString(" · ")
    }

    private fun subtitle(stream: MediaProbe.Stream): String =
        (listOf(SUBTITLE_TYPES[stream.codec]?.let(PlaybackChain::subtitleFormat) ?: stream.codec) + named(stream))
            .joinToString(" · ")

    // Its language and its title, where the file gives them.
    private fun named(stream: MediaProbe.Stream): List<String> =
        listOfNotNull(stream.language?.let(Languages::label), stream.title)

    /** FFmpeg's subtitle codecs by the MIME type the player's chain names them by (PlaybackChain.subtitleFormat). */
    private val SUBTITLE_TYPES = mapOf(
        "subrip" to "application/x-subrip",
        "srt" to "application/x-subrip",
        "ass" to "text/x-ssa",
        "ssa" to "text/x-ssa",
        "webvtt" to "text/vtt",
        "ttml" to "application/ttml+xml",
        "mov_text" to "application/x-quicktime-tx3g",
        "hdmv_pgs_subtitle" to "application/pgs",
        "dvd_subtitle" to "application/vobsub",
        "dvb_subtitle" to "application/dvbsubs",
        "dvb_teletext" to Teletext.MIME_TYPE,
        "eia_608" to "application/cea-608",
    )
}
