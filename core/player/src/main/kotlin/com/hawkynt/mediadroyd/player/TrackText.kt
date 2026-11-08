package com.hawkynt.mediadroyd.player

import com.hawkynt.mediadroyd.library.CodecProfile
import com.hawkynt.mediadroyd.library.Languages
import com.hawkynt.mediadroyd.library.Quality
import com.hawkynt.mediadroyd.nfo.AudioStream
import com.hawkynt.mediadroyd.nfo.StreamDetails

/** One track as the info sheet lists it, the same on every platform. */
object TrackText {
    /** What a track is: picture, sound or subtitles. */
    enum class Kind { VIDEO, AUDIO, TEXT }

    /**
     * One track as the file holds it: `HEVC · Main 10@L5.1 · 3840 × 1606 · 23.976 fps` or `AC3 · 5.1 ·
     * DE` — the codec and its profile, never the decoder that plays it. [mimeType] and [codecs] as the engine
     * tells them (a subtitle track's own format; `video/x-ffmpeg-<name>` with FFmpeg's codec name in [codecs] for a
     * stream FFmpeg demuxed); 0 for what is not known.
     */
    fun describe(
        kind: Kind,
        mimeType: String?,
        codecs: String?,
        width: Int = 0,
        height: Int = 0,
        frameRate: Float = 0f,
        channels: Int = 0,
        language: String? = null,
        label: String? = null,
    ): String {
        if (kind == Kind.TEXT) {
            val languageName = language?.let(Languages::label)
            return listOfNotNull(
                mimeType?.let(PlaybackChain::subtitleFormat),
                languageName,
                label?.takeIf { it != languageName },
            ).joinToString(" · ")
        }
        val codec = Quality.codecOf(mimeType, codecs)
        val parts = mutableListOf<String>()
        val shown = Quality.of(
            StreamDetails(
                width.takeIf { it > 0 },
                height.takeIf { it > 0 },
                codec,
                null,
                if (kind == Kind.AUDIO) listOf(AudioStream(codec, channels.takeIf { it > 0 }, null)) else emptyList(),
            ),
        )
        (shown.video ?: shown.audio ?: codec ?: mimeType)?.let { parts += it }
        CodecProfile.of(codecs)?.let { parts += it }
        if (width > 0) parts += "$width × $height"
        if (frameRate > 0) {
            parts += String.format(java.util.Locale.ROOT, "%.3f", frameRate).trimEnd('0').trimEnd('.') + " fps"
        }
        shown.channels?.takeIf { channels > 0 }?.let { parts += it }
        language?.let { parts += Languages.label(it) }
        label?.let { parts += it }
        return parts.joinToString(" · ")
    }
}
