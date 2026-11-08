package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssEventsTest {
    @Test
    fun `ASS and SSA are what libass draws, by MIME type or FFmpeg's codec name`() {
        assertTrue(AssEvents.isAss("text/x-ssa"))
        assertTrue(AssEvents.isAssCodec("ass"))
        assertTrue(AssEvents.isAssCodec("ssa"))
        assertFalse(AssEvents.isAss("application/x-subrip"))
        assertFalse(AssEvents.isAss(null))
        assertFalse(AssEvents.isAssCodec("subrip"))
    }

    @Test
    fun `a file beside the video Media3 leaves unread is ASS by the type it keeps in codecs`() {
        assertTrue(AssEvents.isAss("text/x-ssa", null))
        assertTrue(AssEvents.isAss("text/x-unknown", "text/x-ssa"))
        assertFalse(AssEvents.isAss("text/x-unknown", "application/x-subrip"))
        assertFalse(AssEvents.isAss("application/x-media3-cues", "text/x-ssa"))
    }

    @Test
    fun `a Matroska sample as Media3 hands it gives its event and how long it shows`() {
        // MatroskaExtractor's SSA_PREFIX with the block's duration as the end ("%01d:%02d:%02d:%02d").
        val sample = "Dialogue: 0:00:00:00,0:01:02:35,7,0,Default,,0,0,0,,{\\an8}Hello".toByteArray()
        val event = AssEvents.fromMatroskaSample(sample)!!
        assertEquals(62_350L, event.durationMs)
        assertArrayEquals("7,0,Default,,0,0,0,,{\\an8}Hello".toByteArray(), event.data)
    }

    @Test
    fun `hours count in a sample's duration, and text with commas stays whole`() {
        val sample = "Dialogue: 0:00:00:00,1:00:00:05,1,0,S,,0,0,0,,a, b, c".toByteArray()
        val event = AssEvents.fromMatroskaSample(sample)!!
        assertEquals(3_600_050L, event.durationMs)
        assertEquals("1,0,S,,0,0,0,,a, b, c", String(event.data))
    }

    @Test
    fun `what is not a Matroska sample gives no event`() {
        assertNull(AssEvents.fromMatroskaSample("[Script Info]\nTitle: x\n".toByteArray()))
        assertNull(AssEvents.fromMatroskaSample("Dialogue: nonsense".toByteArray()))
        assertNull(AssEvents.fromMatroskaSample(ByteArray(0)))
    }

    @Test
    fun `a stream's header is the codec private Media3 keeps after the events' format`() {
        val format = "Format: Start, End, ReadOrder, Layer, Style, Name, MarginL, MarginR, MarginV, Effect, Text"
        val header = "[Script Info]\nScriptType: v4.00+\n".toByteArray()
        assertArrayEquals(header, AssEvents.header(listOf(format.toByteArray(), header)))
        assertNull(AssEvents.header(emptyList()))
    }

    @Test
    fun `a whole script file is told from an event, a byte order mark or not`() {
        assertTrue(AssEvents.isScript("[Script Info]\r\nTitle: x".toByteArray()))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertTrue(AssEvents.isScript(bom + "[Script Info]".toByteArray()))
        assertTrue(AssEvents.isScript("; comment\n[Script Info]".toByteArray()))
        assertFalse(AssEvents.isScript("Dialogue: 0:00:00:00,0:00:01:00,1,0,S,,0,0,0,,x".toByteArray()))
    }
}
