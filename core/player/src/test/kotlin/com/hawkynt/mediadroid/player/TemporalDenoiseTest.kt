package com.hawkynt.mediadroid.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reference temporal denoising against FFmpeg's own atadenoise filter, byte for byte: `resources/temporal-denoise/`
 * holds five grainy frames with a moving bar (`make-input.py`) and the middle one denoised against its neighbours by
 * FFmpeg's filter (`make-expected.sh`).
 */
class TemporalDenoiseTest {

    private fun ppm(name: String): ByteArray {
        val data = requireNotNull(javaClass.getResourceAsStream("/temporal-denoise/$name")) { name }
            .use { it.readBytes() }
        return data.copyOfRange(data.size - 64 * 48 * 3, data.size)
    }

    private val before = ppm("frame1.ppm")
    private val current = ppm("frame2.ppm")
    private val after = ppm("frame3.ppm")

    private fun check(a: String, b: String) {
        val expected = ppm("$a-$b.ppm")
        val changed = expected.indices.count { expected[it] != current[it] }
        assertTrue("FFmpeg changed only $changed values", changed > expected.size / 10)
        val params = TemporalDenoise.Params(a.toFloat(), b.toFloat())
        assertArrayEquals(expected, TemporalDenoise.apply(before, current, after, params))
    }

    @Test
    fun `FFmpeg's defaults agree`() = check("0.02", "0.04")

    @Test
    fun `four times the thresholds agree`() = check("0.08", "0.16")

    @Test
    fun `the moving bar is left alone, the still background averaged`() {
        val out = TemporalDenoise.apply(before, current, after, TemporalDenoise.Params(0.08f, 0.16f))
        // Frame 2's bar spans x 16..23, frame 1's 13..20: at x 22 the frame before shows the background, so no frame
        // is taken; in the background far from the bar most values move.
        for (c in 0..2) assertEquals(current[(10 * 64 + 22) * 3 + c], out[(10 * 64 + 22) * 3 + c])
        val background = (30 until 48).flatMap { y -> (40 until 64).map { x -> (y * 64 + x) * 3 } }
        assertTrue(background.count { out[it] != current[it] } > background.size / 3)
    }

    @Test
    fun `the slider runs from off through FFmpeg's defaults to four times their tolerance`() {
        assertNull(TemporalDenoise.params(0f))
        assertEquals(TemporalDenoise.Defaults, TemporalDenoise.params(0.25f))
        assertEquals(TemporalDenoise.Params(0.08f, 0.16f), TemporalDenoise.params(1f))
        assertEquals(4, TemporalDenoise.threshold(0.02f))
        assertEquals(9, TemporalDenoise.threshold(0.04f))
    }
}
