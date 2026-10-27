package com.hawkynt.mediadroid.library

import com.hawkynt.mediadroid.nfo.AudioStream
import com.hawkynt.mediadroid.nfo.StreamDetails
import org.junit.Assert.assertEquals
import org.junit.Test

class QualityTest {

    private fun details(
        width: Int?,
        height: Int?,
        codec: String? = null,
        hdr: String? = null,
        vararg audio: AudioStream,
    ) =
        StreamDetails(width, height, codec, hdr, audio.toList())

    @Test
    fun resolutionGoesByWidthSoCroppedFilmsKeepTheirClass() {
        val labels = listOf(
            640 to 480, 720 to 576, 1280 to 720, 1920 to 800, 1920 to 1080, 2560 to 1440, 3840 to 1606, 7680 to 4320,
        ).map { (w, h) -> Quality.of(details(w, h)).resolution }
        assertEquals(listOf("480p", "576p", "720p", "1080p", "1080p", "2K", "4K", "8K"), labels)
    }

    @Test
    fun theBestAudioStreamNamesTheSound() {
        val q = Quality.of(
            details(1920, 1080, "h264", null, AudioStream("ac3", 6, "ger"), AudioStream("truehd_atmos", 8, "eng")),
        )
        assertEquals("Dolby Atmos", q.audio)
        assertEquals("7.1", q.channels)
        assertEquals("H.264", q.video)
    }

    @Test
    fun kodiCodecNamesBecomeWhatPeopleKnow() {
        fun audio(codec: String) = Quality.of(details(null, null, null, null, AudioStream(codec, 2, null))).audio
        assertEquals("Dolby Digital", audio("ac3"))
        assertEquals("Dolby Digital+", audio("eac3"))
        assertEquals("Dolby TrueHD", audio("truehd"))
        assertEquals("DTS", audio("dca"))
        assertEquals("DTS-HD MA", audio("dtshd_ma"))
        assertEquals("DTS:X", audio("dtshd_ma_x"))
        assertEquals("AAC", audio("aac"))
        assertEquals("Dolby Vision", Quality.of(details(3840, 2160, "hevc", "dolbyvision")).hdr)
        assertEquals("HDR10", Quality.of(details(3840, 2160, "hevc", "hdr10")).hdr)
    }

    @Test
    fun media3MimeTypesBecomeCodecNames() {
        assertEquals("hevc", Quality.codecOf("video/hevc"))
        assertEquals("h264", Quality.codecOf("video/avc"))
        assertEquals("mpeg4", Quality.codecOf("video/mp4v-es"))
        assertEquals("eac3_atmos", Quality.codecOf("audio/eac3-joc"))
        assertEquals("truehd", Quality.codecOf("audio/true-hd"))
        assertEquals("dts", Quality.codecOf("audio/vnd.dts"))
        assertEquals("dtshd", Quality.codecOf("audio/vnd.dts.hd"))
        assertEquals("dtshd_ma_x", Quality.codecOf("audio/vnd.dts.uhd;profile=p2"))
        val atmos = details(null, null, null, null, AudioStream("eac3_atmos", 6, null))
        assertEquals("Dolby Atmos", Quality.of(atmos).audio)
    }

    @Test
    fun nothingKnownIsNothingShown() {
        assertEquals(Quality.NONE, Quality.of(null))
    }
}
