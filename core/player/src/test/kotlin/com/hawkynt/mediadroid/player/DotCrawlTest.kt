package com.hawkynt.mediadroid.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * The comb filter against a simulated composite capture: a picture with a residue that flips sign every frame, as
 * NTSC's subcarrier does (227.5 cycles a line: a dot pattern along colour edges, crawling as the phase turns), over
 * a still background and a bar that moves.
 */
class DotCrawlTest {

    private val w = 64
    private val h = 32

    /** The clean picture: soft colour areas with edges, the bar at [barX]. */
    private fun clean(barX: Int) = ByteArray(w * h * 3) { i ->
        val x = i / 3 % w
        val y = i / 3 / w
        val c = i % 3
        val v = when {
            x in barX until barX + 6 -> intArrayOf(230, 210, 60)[c]
            x < 24 -> intArrayOf(180, 60, 60)[c] + y
            else -> intArrayOf(60, 140, 190)[c] - y
        }
        v.toByte()
    }

    /** The decoder's residue at frame [t]: ±8 levels at half the line's pixel rate, its phase turning each frame. */
    private fun residue(x: Int, y: Int, t: Int) = (8 * cos(PI * (x + y) / 2 + PI * t)).roundToInt()

    private fun captured(t: Int, barX: Int) = clean(barX).also { picture ->
        for (i in picture.indices) {
            val p = i / 3
            picture[i] = ((picture[i].toInt() and 0xFF) + residue(p % w, p / w, t)).coerceIn(0, 255).toByte()
        }
    }

    @Test
    fun `still parts lose the residue, the moving bar is left as it is`() {
        // The bar moves 4 pixels a frame; the rest stands still.
        val frames = (0..2).map { captured(it, 40 + 4 * it) }
        val out = DotCrawl.apply(frames[0], frames[1], frames[2], w, h, requireNotNull(DotCrawl.threshold(0.5f)))
        val truth = clean(44)
        var before = 0
        var after = 0
        var counted = 0
        for (y in 0 until h) for (x in 0 until w) {
            // The bar's span in any of the three frames, a pixel either side.
            val moving = x in 39..54
            for (c in 0..2) {
                val i = (y * w + x) * 3 + c
                if (moving) {
                    if (x in 45..48) assertEquals("($x, $y) in the bar", frames[1][i], out[i])
                } else {
                    before += abs((frames[1][i].toInt() and 0xFF) - (truth[i].toInt() and 0xFF))
                    after += abs((out[i].toInt() and 0xFF) - (truth[i].toInt() and 0xFF))
                    counted++
                }
            }
        }
        assertTrue("residue $before before, $after after", before >= counted * 3)
        assertTrue("residue $before before, $after after", after * 20 < before)
    }

    @Test
    fun `a picture without residue stays as it is`() {
        val still = clean(10)
        val out = DotCrawl.apply(still, still, still, w, h, 8f)
        assertTrue(out.contentEquals(still))
    }

    @Test
    fun `the comb fades out between the threshold and twice it`() {
        val before = ByteArray(3) { 100.toByte() }
        val current = ByteArray(3) { 140.toByte() }
        val full = DotCrawl.apply(before, current, ByteArray(3) { 110.toByte() }, 1, 1, 10f)
        assertEquals((100 + 280 + 110) / 4f, (full[0].toInt() and 0xFF).toFloat(), 0.5f)
        val half = DotCrawl.apply(before, current, ByteArray(3) { 115.toByte() }, 1, 1, 10f)
        assertEquals(140f + 0.5f * ((100 + 280 + 115) / 4f - 140f), (half[0].toInt() and 0xFF).toFloat(), 0.5f)
        val none = DotCrawl.apply(before, current, ByteArray(3) { 125.toByte() }, 1, 1, 10f)
        assertEquals(140, none[0].toInt() and 0xFF)
    }

    @Test
    fun `the slider runs from off to a threshold of twenty steps`() {
        assertNull(DotCrawl.threshold(0f))
        assertEquals(20f, DotCrawl.threshold(1f)!!, 0f)
        assertEquals(12f, DotCrawl.threshold(0.5f)!!, 0f)
    }
}
