package com.hawkynt.mediadroid.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodecProfileTest {

    @Test
    fun h264NamesItsProfileAndLevel() {
        assertEquals("High@L4.1", CodecProfile.of("avc1.640029"))
        assertEquals("Main@L3.1", CodecProfile.of("avc1.4D401F"))
        assertEquals("Constrained Baseline@L3", CodecProfile.of("avc1.42E01E"))
        assertEquals("Baseline@L3", CodecProfile.of("avc3.42001E"))
        assertEquals("High 10@L5.1", CodecProfile.of("avc1.6E0033"))
    }

    @Test
    fun hevcNamesItsProfileLevelAndTier() {
        assertEquals("Main 10@L5.1", CodecProfile.of("hvc1.2.4.L153.B0"))
        assertEquals("Main@L4", CodecProfile.of("hev1.1.6.L120.90"))
        assertEquals("Main 10@L5.1, High tier", CodecProfile.of("hvc1.2.4.H153.B0"))
        assertEquals("Range Extensions@L6.1", CodecProfile.of("hvc1.4.10.L183.9D.08"))
    }

    @Test
    fun av1AndVp9NameProfileLevelAndBitDepth() {
        assertEquals("Main@L5.1, 10-bit", CodecProfile.of("av01.0.13M.10"))
        assertEquals("High@L4, 8-bit", CodecProfile.of("av01.1.08M.08"))
        assertEquals("Profile 2@L4.1, 10-bit", CodecProfile.of("vp09.02.41.10"))
        assertEquals("Profile 0@L3, 8-bit", CodecProfile.of("vp09.00.30.08.01.01.01.01.00"))
    }

    @Test
    fun aacNamesItsAudioObjectType() {
        assertEquals("LC", CodecProfile.of("mp4a.40.2"))
        assertEquals("HE-AAC", CodecProfile.of("mp4a.40.5"))
        assertEquals("HE-AAC v2", CodecProfile.of("mp4a.40.29"))
        assertEquals("ELD", CodecProfile.of("mp4a.40.39"))
    }

    @Test
    fun dolbyVisionNamesItsProfile() {
        assertEquals("Profile 8@L6", CodecProfile.of("dvh1.08.06"))
        assertEquals("Profile 5@L9", CodecProfile.of("dvhe.05.09"))
    }

    @Test
    fun anythingElseHasNoProfile() {
        assertNull(CodecProfile.of(null))
        assertNull(CodecProfile.of(""))
        assertNull(CodecProfile.of("wmv3"))
        assertNull(CodecProfile.of("avc1"))
        assertNull(CodecProfile.of("avc1.zz"))
        assertNull(CodecProfile.of("mp4a.40"))
    }

    @Test
    fun aStreamTheBundledFfmpegDemuxedIsNamedByItsCodecNotItsDecoder() {
        // The MIME type names FFmpeg's decoder; Format.codecs keeps the codec the file holds.
        assertEquals("mp3", Quality.codecOf("audio/x-ffmpeg-mp3float", "mp3"))
        assertEquals("ac3", Quality.codecOf("audio/x-ffmpeg-ac3_fixed", "ac3"))
        assertEquals("wmv3", Quality.codecOf("video/x-ffmpeg-wmv3", null))
        // Media3's own MIME types stay as they are, whatever the codecs string says.
        assertEquals("h264", Quality.codecOf("video/avc", "avc1.640029"))
    }
}
