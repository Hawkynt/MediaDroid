package com.hawkynt.mediadroyd.player

import com.hawkynt.mediadroyd.player.DecodedBuffer.Unit
import org.junit.Assert.assertEquals
import org.junit.Test

class DecodedBufferTest {
    private val fullHd = PictureSize(1920, 1080)
    private val gb = 1024L * 1024 * 1024

    @Test
    fun `a decoded 1080p picture takes about 3 MB`() {
        assertEquals(3_110_400L, DecodedBuffer.pictureBytes(fullHd))
    }

    @Test
    fun `a duration becomes frames by the frame rate, and memory by the picture size`() {
        val buffer = DecodedBuffer(Unit.SECONDS, 2f)
        assertEquals(50, buffer.frames(fullHd, 25f, ceilingBytes = 4 * gb))
        assertEquals(148.3f, buffer.megabytes(fullHd, 25f, ceilingBytes = 4 * gb), 0.1f)
        assertEquals(2f, buffer.seconds(fullHd, 25f, ceilingBytes = 4 * gb), 0.001f)
    }

    @Test
    fun `a memory size becomes frames by the picture size`() {
        val buffer = DecodedBuffer(Unit.MEGABYTES, 300f)
        assertEquals(101, buffer.frames(fullHd, 25f, ceilingBytes = 4 * gb))
        assertEquals(4.04f, buffer.seconds(fullHd, 25f, ceilingBytes = 4 * gb), 0.01f)
    }

    @Test
    fun `a number of frames is taken as it is`() {
        assertEquals(30, DecodedBuffer(Unit.FRAMES, 30f).frames(fullHd, 25f, ceilingBytes = 4 * gb))
    }

    @Test
    fun `the device's memory is the ceiling, and a few frames the floor`() {
        assertEquals(33, DecodedBuffer(Unit.SECONDS, 5f).frames(fullHd, 25f, ceilingBytes = 100L * 1024 * 1024))
        assertEquals(DecodedBuffer.MIN_FRAMES, DecodedBuffer(Unit.FRAMES, 0f).frames(fullHd, 25f, ceilingBytes = 4 * gb))
    }

    @Test
    fun `without a known frame rate a second counts as 30 frames`() {
        assertEquals(60, DecodedBuffer(Unit.SECONDS, 2f).frames(fullHd, -1f, ceilingBytes = 4 * gb))
    }

    @Test
    fun `a quarter of the device's memory is the ceiling`() {
        assertEquals(2 * gb, DecodedBuffer.ceilingBytes(8 * gb))
    }

    @Test
    fun `each unit has a range to choose from, steps rounded to what it counts`() {
        assertEquals(0.5f..10f, Unit.SECONDS.range)
        assertEquals(50f..2000f, Unit.MEGABYTES.range)
        assertEquals(4f..300f, Unit.FRAMES.range)
        assertEquals(2.5f, Unit.SECONDS.rounded(2.6f), 0.001f)
        assertEquals(350f, Unit.MEGABYTES.rounded(361f), 0.001f)
        assertEquals(31f, Unit.FRAMES.rounded(30.7f), 0.001f)
    }

    @Test
    fun `the decoder keeps the setting's frames, at most as many as Media3's decoder renderer copes with`() {
        // Five seconds of 1080p at 25 fps would be 125; the decoder keeps 16.
        assertEquals(DecodedBuffer.MAX_DECODER_FRAMES, DecodedBuffer.DEFAULT.decoderFrames(ceilingBytes = 4 * gb))
        assertEquals(10, DecodedBuffer(DecodedBuffer.Unit.FRAMES, 10f).decoderFrames(ceilingBytes = 4 * gb))
        // 100 MB holds eight 4K pictures.
        assertEquals(8, DecodedBuffer.DEFAULT.decoderFrames(ceilingBytes = 100L * 1024 * 1024))
    }

    @Test
    fun `the decoder's pictures live in the app's heap, so a quarter of it is their ceiling too`() {
        // 8 GB of memory but a 256 MB heap: 64 MB, five 4K pictures.
        val ceiling = DecodedBuffer.decoderCeilingBytes(totalMemoryBytes = 8 * gb, heapBytes = 256L * 1024 * 1024)
        assertEquals(64L * 1024 * 1024, ceiling)
        assertEquals(5, DecodedBuffer.DEFAULT.decoderFrames(ceiling))
    }

    @Test
    fun `five seconds ahead by default`() {
        assertEquals(DecodedBuffer(Unit.SECONDS, 5f), DecodedBuffer.DEFAULT)
    }
}
