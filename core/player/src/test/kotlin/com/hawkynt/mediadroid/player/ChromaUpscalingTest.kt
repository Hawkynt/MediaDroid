package com.hawkynt.mediadroid.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The reference chroma upscaling against libplacebo's own renderer (7.360) on Mesa's OpenGL: `resources/chroma/` holds
 * a 4:2:0 picture (`make-input.py`) and what libplacebo made of its colour with spline36 and KrigBilateral
 * (`make-expected.sh`), 16 bits a value.
 */
class ChromaUpscalingTest {

    private val width = 64
    private val height = 48

    private val planes: ChromaUpscaling.Planes by lazy {
        val bytes = resource("input.yuv")
        val luma = width * height
        val chroma = luma / 4
        ChromaUpscaling.Planes(
            width,
            height,
            bytes.copyOfRange(0, luma),
            bytes.copyOfRange(luma, luma + chroma),
            bytes.copyOfRange(luma + chroma, luma + 2 * chroma),
        )
    }

    private fun resource(name: String): ByteArray =
        requireNotNull(ChromaUpscalingTest::class.java.getResourceAsStream("/chroma/$name")) { name }
            .use { it.readBytes() }

    /** libplacebo's Y, Cb and Cr from a 16-bit PPM, on 0..1. */
    private fun expected(name: String): Array<FloatArray> {
        val data = resource("$name.ppm")
        val start = data.size - width * height * 6
        return Array(3) { c ->
            FloatArray(width * height) { i ->
                val at = start + (i * 3 + c) * 2
                ((data[at].toInt() and 0xFF) shl 8 or (data[at + 1].toInt() and 0xFF)) / 65535f
            }
        }
    }

    /** The largest and the mean difference, in 8-bit steps, of [ours] from [theirs]. */
    private fun apart(ours: FloatArray, theirs: FloatArray): Pair<Float, Float> {
        val d = ours.indices.map { abs(ours[it] - theirs[it]) * 255f }
        return d.max() to d.average().toFloat()
    }

    private fun check(name: String, ours: ChromaUpscaling.Chroma, max: Float, mean: Float) {
        val theirs = expected(name)
        for ((label, pair) in listOf("Cb" to (ours.cb to theirs[1]), "Cr" to (ours.cr to theirs[2]))) {
            val (worst, average) = apart(pair.first, pair.second)
            assertTrue("$name $label: $worst steps apart at worst", worst <= max)
            assertTrue("$name $label: $average steps apart on average", average <= mean)
        }
    }

    @Test
    fun `bilinear blends the nearest four, the samples centred between the pixels`() {
        val out = ChromaUpscaling.bilinear(planes)
        // Pixel (1, 1) lies a quarter of a sample right of and below sample (0, 0): weights 9, 3, 3 and 1 sixteenths.
        val expected = (9 * planes.cb(0, 0) + 3 * planes.cb(1, 0) + 3 * planes.cb(0, 1) + planes.cb(1, 1)) / 16f
        assertEquals(expected, out.cb[width + 1], 1e-6f)
    }

    // libplacebo reads spline36's weights from a table it interpolates, so within a fifth of a step.
    @Test
    fun `spline36 agrees with libplacebo's`() = check("spline36", ChromaUpscaling.spline36(planes), 0.2f, 0.05f)

    // Furthest apart (under 3/4 of a step) where a colour edge meets a corner, where the kriging system is nearly
    // singular and solved without pivoting: there float rounding in another order, and the GPU's own exp, show.
    @Test
    fun `KrigBilateral agrees with libplacebo running Shiandow's shader`() =
        check("krig", ChromaUpscaling.krig(planes), 0.75f, 0.1f)

    @Test
    fun `the sharper filters are not bilinear, so agreeing with libplacebo says something`() {
        val bilinear = ChromaUpscaling.bilinear(planes)
        for (other in listOf(ChromaUpscaling.spline36(planes), ChromaUpscaling.krig(planes))) {
            assertTrue(apart(other.cb, bilinear.cb).first > 4f)
        }
    }

    @Test
    fun `KrigBilateral keeps a colour edge where the brightness has it, unlike bilinear`() {
        // The green bar's left edge at x = 26 falls inside a chroma sample (columns 26 and 27 share sample 13), but
        // 25 is still grey: Krig keeps 25 grey's colour, bilinear smears green into it.
        val theirs = expected("krig")
        val krig = ChromaUpscaling.krig(planes)
        val bilinear = ChromaUpscaling.bilinear(planes)
        val at = 10 * width + 25
        assertTrue(abs(krig.cr[at] - theirs[2][at]) < 1f / 255f)
        assertTrue(abs(krig.cr[at] - 128f / 255f) < abs(bilinear.cr[at] - 128f / 255f))
    }

    @Test
    fun `spline36's weights are libplacebo's, one at the centre and nothing at the other samples`() {
        assertEquals(1f, ChromaUpscaling.spline36Weight(0f), 1e-6f)
        for (d in listOf(1f, 2f, 3f, -1f, 4f)) assertEquals(0f, ChromaUpscaling.spline36Weight(d), 1e-6f)
    }

    @Test
    fun `each filter of the setting reaches its reference`() {
        for (filter in PictureAdjustment.Chroma.entries) {
            val out = ChromaUpscaling.upscale(planes, filter)
            assertEquals(width * height, out.cb.size)
        }
    }
}
