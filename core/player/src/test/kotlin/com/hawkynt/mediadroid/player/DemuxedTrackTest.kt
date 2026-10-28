package com.hawkynt.mediadroid.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DemuxedTrackTest {

    /** A codec-parameter blob as mediadroid_codecpar.h packs it, with [extradata] at its end. */
    private fun blob(extradata: ByteArray = ByteArray(0)): ByteArray {
        val header = ByteArray(48)
        "MDCP".toByteArray().copyInto(header)
        header[4] = 1
        val size = extradata.size
        for (i in 0 until 4) header[44 + i] = (size ushr (8 * i)).toByte()
        return header + extradata
    }

    private val annexB = byteArrayOf(0, 0, 0, 1, 0x67, 0x42)
    private val avcc = byteArrayOf(1, 0x42, 0, 0x1e)

    @Test
    fun theExtradataIsReadOutOfTheBlob() {
        assertArrayEquals(avcc, DemuxedTrack.extradataOf(blob(avcc)))
        assertArrayEquals(ByteArray(0), DemuxedTrack.extradataOf(blob()))
        assertArrayEquals(ByteArray(0), DemuxedTrack.extradataOf(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun codecsAndroidDecodesKeepMedia3sMimeTypeSoTheHardwareCanTakeThem() {
        assertEquals("video/avc", DemuxedTrack.map(DemuxedTrack.VIDEO, "h264", blob(annexB))?.mimeType)
        assertEquals("video/hevc", DemuxedTrack.map(DemuxedTrack.VIDEO, "hevc", blob())?.mimeType)
        assertEquals("video/mpeg2", DemuxedTrack.map(DemuxedTrack.VIDEO, "mpeg2video", blob())?.mimeType)
        assertEquals("video/x-vnd.on2.vp9", DemuxedTrack.map(DemuxedTrack.VIDEO, "vp9", blob())?.mimeType)
        assertEquals("audio/ac3", DemuxedTrack.map(DemuxedTrack.AUDIO, "ac3", blob())?.mimeType)
        assertEquals("audio/vnd.dts", DemuxedTrack.map(DemuxedTrack.AUDIO, "dts", blob())?.mimeType)
        assertEquals("audio/mpeg", DemuxedTrack.map(DemuxedTrack.AUDIO, "mp3", blob())?.mimeType)
        val mpeg4 = DemuxedTrack.map(DemuxedTrack.VIDEO, "mpeg4", blob(byteArrayOf(0, 0, 1, 0xb0.toByte())))!!
        assertEquals("video/mp4v-es", mpeg4.mimeType)
        assertArrayEquals(byteArrayOf(0, 0, 1, 0xb0.toByte()), mpeg4.initializationData.single())
        val aac = DemuxedTrack.map(DemuxedTrack.AUDIO, "aac", blob(byteArrayOf(0x12, 0x10)))!!
        assertEquals("audio/mp4a-latm", aac.mimeType)
        assertArrayEquals(byteArrayOf(0x12, 0x10), aac.initializationData.single())
    }

    @Test
    fun whatAndroidCannotTakeAsItIsGoesToFfmpegWithTheWholeBlob() {
        val wma = blob(byteArrayOf(9, 9))
        val mapped = DemuxedTrack.map(DemuxedTrack.AUDIO, "wmav2", wma)!!
        assertEquals("audio/x-ffmpeg-wmav2", mapped.mimeType)
        assertArrayEquals(wma, mapped.initializationData.single())
        assertEquals("video/x-ffmpeg-rv40", DemuxedTrack.map(DemuxedTrack.VIDEO, "rv40", blob())?.mimeType)
        assertEquals("video/x-ffmpeg-wmv3", DemuxedTrack.map(DemuxedTrack.VIDEO, "wmv3", blob())?.mimeType)
        // FFmpeg finds decoders by their own name: RealAudio 1.0 is codec ra_144, decoder real_144.
        assertEquals(
            "audio/x-ffmpeg-real_144",
            DemuxedTrack.map(DemuxedTrack.AUDIO, "ra_144", blob(), decoderName = "real_144")?.mimeType,
        )
        // Length-prefixed H.264 and ADTS-less AAC without its config are no use to MediaCodec.
        assertEquals("video/x-ffmpeg-h264", DemuxedTrack.map(DemuxedTrack.VIDEO, "h264", blob(avcc))?.mimeType)
        assertEquals("audio/x-ffmpeg-aac", DemuxedTrack.map(DemuxedTrack.AUDIO, "aac", blob())?.mimeType)
    }

    @Test
    fun streamsWithoutACodecNameOrOfAnotherKindAreLeftOut() {
        assertNull(DemuxedTrack.map(DemuxedTrack.VIDEO, null, blob()))
        assertNull(DemuxedTrack.map(DemuxedTrack.OTHER, "mjpeg", blob()))
    }
}
