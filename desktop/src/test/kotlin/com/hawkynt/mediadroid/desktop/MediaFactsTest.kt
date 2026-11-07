package com.hawkynt.mediadroid.desktop

import com.hawkynt.mediadroid.engine.desktop.MediaProbe
import com.hawkynt.mediadroid.engine.desktop.MediaProbe.Kind
import com.hawkynt.mediadroid.engine.desktop.MediaProbe.Stream
import com.hawkynt.mediadroid.nfo.AudioStream
import com.hawkynt.mediadroid.nfo.StreamDetails
import com.hawkynt.mediadroid.player.ToneMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaFactsTest {

    private val film = MediaProbe.Facts(
        durationMs = 7_200_000,
        streams = listOf(
            Stream(Kind.VIDEO, "hevc", "Main 10", 3840, 1606, 23.976f, ToneMap.Transfer.PQ),
            Stream(Kind.SUBTITLE, "subrip", language = "eng"),
            Stream(Kind.AUDIO, "truehd", "Dolby TrueHD + Dolby Atmos", channels = 8, language = "eng"),
            Stream(Kind.AUDIO, "ac3", channels = 6, language = "ger", title = "Commentary"),
            Stream(Kind.SUBTITLE, "hdmv_pgs_subtitle", language = "deu", title = "Forced"),
        ),
    )

    @Test
    fun `every track is listed as Android's details list it, video first, then sound, then subtitles`() {
        assertEquals(
            listOf(
                "Video: HEVC · Main 10 · 3840 × 1606 · 23.976 fps",
                "Audio: Dolby Atmos · 7.1 · EN",
                "Audio: Dolby Digital · 5.1 · DE · Commentary",
                "Subtitles: SubRip · EN",
                "Subtitles: PGS (pictures) · DE · Forced",
            ),
            MediaFacts.tracks(film),
        )
    }

    @Test
    fun `the stream details are the first picture's and every sound's, in Kodi's codec names`() {
        assertEquals(
            StreamDetails(
                3840, 1606, "hevc", "hdr10",
                listOf(AudioStream("truehd_atmos", 8, "eng"), AudioStream("ac3", 6, "ger")),
            ),
            MediaFacts.details(film),
        )
    }

    @Test
    fun `DTS's and Dolby's extensions take Kodi's names from the profile FFmpeg reads`() {
        fun codec(name: String, profile: String?) = MediaFacts.kodiCodec(Stream(Kind.AUDIO, name, profile))
        assertEquals("dtshd_ma", codec("dts", "DTS-HD MA"))
        assertEquals("dtshd_hra", codec("dts", "DTS-HD HRA"))
        assertEquals("dtshd_ma_x", codec("dts", "DTS-HD MA + DTS:X"))
        assertEquals("dtshd_ma_x_imax", codec("dts", "DTS-HD MA + DTS:X IMAX"))
        assertEquals("dts", codec("dts", "DTS"))
        assertEquals("eac3_ddp_atmos", codec("eac3", "Dolby Digital Plus + Dolby Atmos"))
        assertEquals("aac", codec("aac", "LC"))
    }

    @Test
    fun `a file without a picture has no picture facts, and one unread has none at all`() {
        val song = MediaProbe.Facts(1000, listOf(Stream(Kind.AUDIO, "flac", channels = 2)))
        assertEquals(
            StreamDetails(null, null, null, null, listOf(AudioStream("flac", 2, null))),
            MediaFacts.details(song),
        )
        assertEquals(listOf("Audio: FLAC · 2.0"), MediaFacts.tracks(song))
        assertNull(MediaFacts.details(null))
    }
}
