package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

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

    /**
     * A PAL composite capture's residue at frame [t]: the subcarrier at 283.75 times the line rate plus 25 Hz (ITU-R
     * BT.1700: 4 433 618.75 Hz on a 15 625 Hz line) sampled at 13.5 MHz (ITU-R BT.601), a frame's rows taken from
     * its two fields 312.5 lines apart, the V component's sign switched every line (625 a frame, so the switch flips
     * from one frame to the next too); a cross-luminance residue of about ±8 levels from a colour with equal U and V.
     */
    private fun palResidue(x: Int, y: Int, t: Int): Int {
        val field = y % 2
        val lines = field * 312.5 + y / 2
        val cycles = x * PAL_SUBCARRIER / 13_500_000.0 + lines * PAL_SUBCARRIER / 15_625.0 + t * PAL_SUBCARRIER / 25.0
        val switched = if (Math.floorMod(625 * t + field * 313 + y / 2, 2) == 0) 1 else -1
        val phase = 2 * PI * (cycles - floor(cycles))
        return (5.7 * (sin(phase) + switched * cos(phase))).roundToInt()
    }

    private fun palCaptured(t: Int, barX: Int) = clean(barX).also { picture ->
        for (i in picture.indices) {
            val p = i / 3
            picture[i] = ((picture[i].toInt() and 0xFF) + palResidue(p % w, p / w, t)).coerceIn(0, 255).toByte()
        }
    }

    /** The residue [out] holds against the clean picture [truth] outside the columns [moving], and the values seen. */
    private fun residueLeft(out: ByteArray, truth: ByteArray, moving: IntRange): Pair<Int, Int> {
        var sum = 0
        var counted = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (x in moving) continue
            for (c in 0..2) {
                val i = (y * w + x) * 3 + c
                sum += abs((out[i].toInt() and 0xFF) - (truth[i].toInt() and 0xFF))
                counted++
            }
        }
        return sum to counted
    }

    @Test
    fun `PAL's residue comes back in phase only every four frames`() {
        // Two frames on, the residue is the opposite of the frame's own; one either side, the opposite of each other.
        for (y in 0 until 8) for (x in 0 until 8) {
            assertEquals(-palResidue(x, y, 2).toFloat(), palResidue(x, y, 0).toFloat(), 1f)
            assertEquals(-palResidue(x, y, -1).toFloat(), palResidue(x, y, 1).toFloat(), 1f)
            assertEquals(palResidue(x, y, 4).toFloat(), palResidue(x, y, 0).toFloat(), 1f)
        }
    }

    @Test
    fun `NTSC's comb leaves PAL's residue`() {
        val frames = (0..2).map { palCaptured(it, 10) }
        val out = DotCrawl.apply(frames[0], frames[1], frames[2], w, h, requireNotNull(DotCrawl.threshold(0.5f)))
        val (before, counted) = residueLeft(frames[1], clean(10), IntRange.EMPTY)
        val (after, _) = residueLeft(out, clean(10), IntRange.EMPTY)
        assertTrue("residue $before before, $after after", before >= counted * 3)
        assertTrue("residue $before before, $after after", after * 3 > before)
    }

    @Test
    fun `PAL's comb clears the still parts and leaves the moving bar`() {
        // Frames t - 2 to t + 1, the bar moving 4 pixels a frame; the frame shown is the third.
        val frames = (0..3).map { palCaptured(it, 36 + 4 * it) }
        val out = DotCrawl.applyPal(
            frames[0], frames[1], frames[2], frames[3], w, h, requireNotNull(DotCrawl.threshold(0.5f)),
        )
        // The bar's span in any of the four frames, a pixel either side.
        val moving = 35..54
        val (before, counted) = residueLeft(frames[2], clean(44), moving)
        val (after, _) = residueLeft(out, clean(44), moving)
        assertTrue("residue $before before, $after after", before >= counted * 3)
        assertTrue("residue $before before, $after after", after * 10 < before)
        for (y in 0 until h) for (x in 46..47) for (c in 0..2) {
            val i = (y * w + x) * 3 + c
            assertEquals("($x, $y) in the bar", frames[2][i], out[i])
        }
    }

    @Test
    fun `PAL's comb fades out between the threshold and twice it`() {
        fun px(v: Int) = ByteArray(3) { v.toByte() }
        // The frame two before and the frame shown against the frames either side: motion is half their difference.
        val full = DotCrawl.applyPal(px(100), px(120), px(140), px(110), 1, 1, 10f)
        assertEquals(120, full[0].toInt() and 0xFF)
        val half = DotCrawl.applyPal(px(100), px(110), px(140), px(100), 1, 1, 10f)
        assertEquals(140f + 0.5f * (120f - 140f), (half[0].toInt() and 0xFF).toFloat(), 0.5f)
        val none = DotCrawl.applyPal(px(100), px(100), px(140), px(100), 1, 1, 10f)
        assertEquals(140, none[0].toInt() and 0xFF)
    }

    @Test
    fun `the standard follows the rate, PAL-M only where the tags say PAL colour or the household does`() {
        fun of(ms: Float, system: PictureAdjustment.ColourSystem = AUTO, pal: Boolean = false) =
            DotCrawl.Standard.of(ms, system, pal)
        assertEquals(DotCrawl.Standard.PAL, of(40f))
        assertEquals(DotCrawl.Standard.PAL, of(39f))
        assertEquals(DotCrawl.Standard.NTSC, of(1001f / 30f))
        assertEquals(DotCrawl.Standard.NTSC, of(1001f / 24f))
        assertEquals(DotCrawl.Standard.NTSC, of(0f))
        // Bobbed to the fields: PAL's 50, NTSC's 59.94.
        assertEquals(DotCrawl.Standard.PAL_FIELDS, of(20f))
        assertEquals(DotCrawl.Standard.NTSC_FIELDS, of(1001f / 60f))
        // PAL-M (29.97, PAL's colour): from the tags, or chosen; PAL's comb at its frame rate.
        assertEquals(DotCrawl.Standard.PAL, of(1001f / 30f, pal = true))
        assertEquals(DotCrawl.Standard.PAL, of(1001f / 30f, PictureAdjustment.ColourSystem.PAL_M))
        assertEquals(DotCrawl.Standard.PAL_FIELDS, of(1001f / 60f, PictureAdjustment.ColourSystem.PAL_M))
        assertEquals(DotCrawl.Standard.PAL_FIELDS, of(1001f / 60f, pal = true))
        // Chosen by hand over the tags and the rate.
        assertEquals(DotCrawl.Standard.NTSC, of(1001f / 30f, PictureAdjustment.ColourSystem.NTSC, pal = true))
        assertEquals(DotCrawl.Standard.NTSC, of(40f, PictureAdjustment.ColourSystem.NTSC))
        assertEquals(DotCrawl.Standard.PAL, of(40f, PictureAdjustment.ColourSystem.PAL))
        assertEquals(DotCrawl.Standard.PAL_FIELDS, of(20f, PictureAdjustment.ColourSystem.PAL))
        assertEquals(DotCrawl.Standard.NTSC_FIELDS, of(20f, PictureAdjustment.ColourSystem.NTSC))
        assertEquals(DotCrawl.Standard.NTSC_FIELDS, of(1001f / 60f, PictureAdjustment.ColourSystem.NTSC, pal = true))
    }

    /**
     * A PAL-M composite capture's residue at frame [t] (Brazil): the subcarrier at 227.25 times the line rate (ITU-R
     * BT.1700, System M/PAL: 3 575 611.49 Hz on a 15 734.27 Hz line) sampled at 13.5 MHz, 525 lines a frame, a frame's
     * rows taken from its two fields 262.5 lines apart, the V component's sign switched every line (525 a frame, so
     * the switch flips from one frame to the next).
     */
    private fun palMResidue(x: Int, y: Int, t: Int): Int {
        val field = y % 2
        val lines = field * 262.5 + y / 2 + 525.0 * t
        val cycles = x * PAL_M_SUBCARRIER / 13_500_000.0 + lines * 227.25
        val switched = if (Math.floorMod(525 * t + field * 263 + y / 2, 2) == 0) 1 else -1
        val phase = 2 * PI * (cycles - floor(cycles))
        return (5.7 * (sin(phase) + switched * cos(phase))).roundToInt()
    }

    private fun palMCaptured(t: Int, barX: Int) = clean(barX).also { picture ->
        for (i in picture.indices) {
            val p = i / 3
            picture[i] = ((picture[i].toInt() and 0xFF) + palMResidue(p % w, p / w, t)).coerceIn(0, 255).toByte()
        }
    }

    @Test
    fun `PAL-M's residue is opposite two frames on, as PAL's, and PAL's comb clears it where NTSC's cannot`() {
        for (y in 0 until 8) for (x in 0 until 8) {
            assertEquals(-palMResidue(x, y, 2).toFloat(), palMResidue(x, y, 0).toFloat(), 1f)
            assertEquals(-palMResidue(x, y, -1).toFloat(), palMResidue(x, y, 1).toFloat(), 1f)
        }
        val still = (0..3).map { palMCaptured(it, 10) }
        val ntsc = DotCrawl.apply(still[1], still[2], still[3], w, h, requireNotNull(DotCrawl.threshold(0.5f)))
        val (before, counted) = residueLeft(still[2], clean(10), IntRange.EMPTY)
        assertTrue("residue $before", before >= counted * 3)
        assertTrue("NTSC's comb: ${residueLeft(ntsc, clean(10), IntRange.EMPTY).first} of $before",
            residueLeft(ntsc, clean(10), IntRange.EMPTY).first * 3 > before)
        // Frames t - 2 to t + 1, the bar moving 4 pixels a frame.
        val frames = (0..3).map { palMCaptured(it, 36 + 4 * it) }
        val out = DotCrawl.applyPal(
            frames[0], frames[1], frames[2], frames[3], w, h, requireNotNull(DotCrawl.threshold(0.5f)),
        )
        val moving = 35..54
        val (shown, _) = residueLeft(frames[2], clean(44), moving)
        val (left, _) = residueLeft(out, clean(44), moving)
        assertTrue("residue $shown before, $left after", left * 10 < shown)
        for (y in 0 until h) for (x in 46..47) for (c in 0..2) {
            val i = (y * w + x) * 3 + c
            assertEquals("($x, $y) in the bar", frames[2][i], out[i])
        }
    }

    /**
     * A PAL capture bobbed to 50 pictures a second: picture [n] is field n % 2 of frame n / 2, its lines doubled — the
     * residue of that field's lines, as [palResidue] gives them.
     */
    private fun bobbedResidue(x: Int, y: Int, n: Int): Int =
        palResidue(x, 2 * (y / 2) + Math.floorMod(n, 2), Math.floorDiv(n, 2))

    private fun bobbedCaptured(n: Int, barX: Int) = clean(barX).also { picture ->
        for (i in picture.indices) {
            val p = i / 3
            picture[i] = ((picture[i].toInt() and 0xFF) + bobbedResidue(p % w, p / w, n)).coerceIn(0, 255).toByte()
        }
    }

    @Test
    fun `bobbed PAL is combed per field, with the pictures of the same field two and three frames back`() {
        // The same field two frames on carries the opposite residue; one frame on, neither the same nor the opposite.
        for (y in 0 until 8) for (x in 0 until 8) {
            assertEquals(-bobbedResidue(x, y, 4).toFloat(), bobbedResidue(x, y, 0).toFloat(), 1f)
        }
        val threshold = requireNotNull(DotCrawl.threshold(0.5f))
        // Pictures n - 6 to n, the bar moving 2 pixels a picture; the one shown is the last.
        val pictures = (0..6).map { bobbedCaptured(it, 32 + 2 * it) }
        val out = DotCrawl.applyPalFields(pictures[0], pictures[2], pictures[4], pictures[6], w, h, threshold)
        val moving = 31..50
        val (before, counted) = residueLeft(pictures[6], clean(44), moving)
        val (after, _) = residueLeft(out, clean(44), moving)
        assertTrue("residue $before before, $after after", before >= counted * 3)
        assertTrue("residue $before before, $after after", after * 10 < before)
        for (y in 0 until h) for (x in 45..49) for (c in 0..2) {
            val i = (y * w + x) * 3 + c
            assertEquals("($x, $y) in the bar", pictures[6][i], out[i])
        }
        // NTSC's comb, which took 50 pictures a second before, on the pictures either side (the other field) leaves
        // most of it.
        val still = (0..7).map { bobbedCaptured(it, 10) }
        val ntsc = DotCrawl.apply(still[5], still[6], still[7], w, h, threshold)
        val (residue, _) = residueLeft(still[6], clean(10), IntRange.EMPTY)
        assertTrue(residueLeft(ntsc, clean(10), IntRange.EMPTY).first * 3 > residue)
    }

    /**
     * An NTSC composite capture's residue at frame [t]: the subcarrier at 227.5 times the line rate (SMPTE 170M:
     * 3 579 545.45 Hz on a 15 734.27 Hz line) sampled at 13.5 MHz, 525 lines a frame, a frame's rows taken from its two
     * fields 262.5 lines apart; a cross-luminance residue of about ±8 levels.
     */
    private fun ntscResidue(x: Int, y: Int, t: Int): Int {
        val lines = y % 2 * 262.5 + y / 2 + 525.0 * t
        val cycles = x * NTSC_SUBCARRIER / 13_500_000.0 + lines * 227.5
        return (8 * sin(2 * PI * (cycles - floor(cycles)))).roundToInt()
    }

    /** An NTSC capture bobbed to 59.94 pictures a second: picture [n] is field n % 2 of frame n / 2, lines doubled. */
    private fun ntscBobbedResidue(x: Int, y: Int, n: Int): Int =
        ntscResidue(x, 2 * (y / 2) + Math.floorMod(n, 2), Math.floorDiv(n, 2))

    private fun ntscBobbedCaptured(n: Int, barX: Int) = clean(barX).also { picture ->
        for (i in picture.indices) {
            val p = i / 3
            picture[i] = ((picture[i].toInt() and 0xFF) + ntscBobbedResidue(p % w, p / w, n)).coerceIn(0, 255)
                .toByte()
        }
    }

    @Test
    fun `bobbed NTSC is combed per field, with the pictures of the same field one, two and three frames back`() {
        // The same field a frame on carries the opposite residue; the other field, neither the same nor the opposite.
        var quadrature = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            assertEquals(-ntscBobbedResidue(x, y, 2).toFloat(), ntscBobbedResidue(x, y, 0).toFloat(), 1f)
            if (abs(ntscBobbedResidue(x, y, 1) + ntscBobbedResidue(x, y, 0)) > 2) quadrature++
        }
        assertTrue(quadrature > 16)
        val threshold = requireNotNull(DotCrawl.threshold(0.5f))
        // Pictures n - 6 to n, the bar moving 2 pixels a picture; the one shown is the last.
        val pictures = (0..6).map { ntscBobbedCaptured(it, 32 + 2 * it) }
        val out = DotCrawl.applyNtscFields(pictures[0], pictures[2], pictures[4], pictures[6], w, h, threshold)
        val moving = 31..50
        val (before, counted) = residueLeft(pictures[6], clean(44), moving)
        val (after, _) = residueLeft(out, clean(44), moving)
        assertTrue("residue $before before, $after after", before >= counted * 3)
        assertTrue("residue $before before, $after after", after * 10 < before)
        for (y in 0 until h) for (x in 45..49) for (c in 0..2) {
            val i = (y * w + x) * 3 + c
            assertEquals("($x, $y) in the bar", pictures[6][i], out[i])
        }
        // The frame-rate comb on the pictures either side (the other field), and PAL's per field (the same field's
        // two frames back, in phase again here), leave most of it.
        val still = (0..7).map { ntscBobbedCaptured(it, 10) }
        val (residue, _) = residueLeft(still[6], clean(10), IntRange.EMPTY)
        val frameRate = DotCrawl.apply(still[5], still[6], still[7], w, h, threshold)
        assertTrue(residueLeft(frameRate, clean(10), IntRange.EMPTY).first * 3 > residue)
        val pal = DotCrawl.applyPalFields(still[0], still[2], still[4], still[6], w, h, threshold)
        assertTrue(residueLeft(pal, clean(10), IntRange.EMPTY).first * 3 > residue)
    }

    private companion object {
        /** NTSC's colour subcarrier, Hz: 227.5 × the System M line rate, 4.5 MHz / 286 (SMPTE 170M). */
        const val NTSC_SUBCARRIER = 227.5 * 4_500_000.0 / 286.0

        /** PAL's colour subcarrier, Hz: 283.75 × 15 625 + 25 (ITU-R BT.1700). */
        const val PAL_SUBCARRIER = 4_433_618.75

        /** PAL-M's: 227.25 × the System M line rate, 4.5 MHz / 286 (ITU-R BT.1700). */
        const val PAL_M_SUBCARRIER = 227.25 * 4_500_000.0 / 286.0

        val AUTO = PictureAdjustment.ColourSystem.AUTO
    }
}
