package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackTextTest {

    @Test
    fun `a picture track tells its codec, profile, size and frame rate`() {
        assertEquals(
            "HEVC · Main 10@L5.1 · 3840 × 1606 · 23.976 fps",
            TrackText.describe(
                TrackText.Kind.VIDEO, "video/hevc", "hvc1.2.4.L153.B0",
                width = 3840, height = 1606, frameRate = 23.976f,
            ),
        )
    }

    @Test
    fun `a sound track tells its codec, channels and language, never the decoder`() {
        assertEquals(
            "AC3 · 5.1 · DE",
            TrackText.describe(TrackText.Kind.AUDIO, "audio/ac3", null, channels = 6, language = "deu"),
        )
    }

    @Test
    fun `a stream FFmpeg demuxed is told by the codec it holds`() {
        assertEquals(
            "H.264 · 720 × 576 · 25 fps",
            TrackText.describe(TrackText.Kind.VIDEO, "video/x-ffmpeg-h264", "h264", 720, 576, 25f),
        )
    }

    @Test
    fun `a subtitle track tells its format, language and its own name`() {
        assertEquals(
            "SubRip · EN · Signs",
            TrackText.describe(TrackText.Kind.TEXT, "application/x-subrip", null, language = "eng", label = "Signs"),
        )
        assertEquals(
            "SSA / ASS · EN",
            TrackText.describe(TrackText.Kind.TEXT, "text/x-ssa", null, language = "eng", label = "EN"),
        )
    }
}
