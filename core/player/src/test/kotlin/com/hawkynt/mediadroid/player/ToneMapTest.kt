package com.hawkynt.mediadroid.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The reference tone mapping against libplacebo 7.360's own colour mapping, run by libplacebo on Mesa's OpenGL:
 * `resources/tonemap/` holds the HDR input (`make-input.py`) and libplacebo's output, its curve and its matrices
 * (`make-expected.sh`).
 */
class ToneMapTest {

    private fun resource(name: String): ByteArray =
        requireNotNull(ToneMapTest::class.java.getResourceAsStream("/tonemap/$name")) { name }.use { it.readBytes() }

    private class Ppm(val width: Int, val height: Int, val data: ByteArray)

    private fun ppm(name: String): Ppm {
        val bytes = resource(name)
        val header = String(bytes, 0, 20, Charsets.US_ASCII).split(Regex("\\s+"))
        val width = header[1].toInt()
        val height = header[2].toInt()
        val size = width * height * if (header[3] == "65535") 6 else 3
        return Ppm(width, height, bytes.copyOfRange(bytes.size - size, bytes.size))
    }

    private val input: ShortArray by lazy {
        val data = ppm("input.ppm").data
        ShortArray(data.size / 2) {
            ((data[2 * it].toInt() and 0xFF) shl 8 or (data[2 * it + 1].toInt() and 0xFF)).toShort()
        }
    }

    private val curveLines: List<String> by lazy { String(resource("curve.txt")).lines().filter { it.isNotBlank() } }

    private fun check(expectedName: String, pass: ToneMap.Pass) {
        val expected = ppm(expectedName)
        val ours = ToneMap.apply(input, expected.width, expected.height, pass)
        var worst = 0
        var sum = 0L
        for (i in ours.indices) {
            val d = abs((ours[i].toInt() and 0xFF) - (expected.data[i].toInt() and 0xFF))
            worst = maxOf(worst, d)
            sum += d
        }
        val mean = sum.toDouble() / ours.size
        // libplacebo reads its curve from a 256-entry table of half floats, interpolated; ours computes it.
        assertTrue("$expectedName: max $worst, mean $mean", worst <= 1 && mean <= 0.05)
        // The picture is far from what clipping alone would give, so the agreement is about the mapping.
        val clipped = ToneMap.apply(input, expected.width, expected.height, ToneMap.pass(ToneMap.Transfer.PQ, 1f))
        if (pass.curve.inputMax < 0.99f) {
            assertTrue(expectedName, ours.indices.count { ours[it] != clipped[it] } > ours.size / 10)
        }
    }

    @Test
    fun `PQ without metadata is mapped from 10000 nits as libplacebo's`() =
        check("pq-0.ppm", ToneMap.pass(ToneMap.Transfer.PQ))

    @Test
    fun `PQ mastered at 400 nits agrees`() = check("pq-400.ppm", ToneMap.pass(ToneMap.Transfer.PQ, peakNits = 400f))

    @Test
    fun `PQ mastered at 1000 nits agrees`() =
        check("pq-1000.ppm", ToneMap.pass(ToneMap.Transfer.PQ, peakNits = 1000f))

    @Test
    fun `PQ mastered at 4000 nits agrees`() =
        check("pq-4000.ppm", ToneMap.pass(ToneMap.Transfer.PQ, peakNits = 4000f))

    @Test
    fun `a detected peak in PQ is the same as the metadata's in nits`() {
        val nits = ToneMap.curve(ToneMap.Transfer.PQ, peakNits = 1000f)
        val pq = ToneMap.curve(ToneMap.Transfer.PQ, peakPq = ToneMap.pqOfNits(1000f))
        assertEquals(nits, pq)
    }

    @Test
    fun `HLG agrees`() = check("hlg.ppm", ToneMap.pass(ToneMap.Transfer.HLG))

    @Test
    fun `the curve's ends are libplacebo's`() {
        val peaks = listOf(0f, 400f, 1000f, 4000f)
        peaks.forEachIndexed { k, peak ->
            val numbers = curveLines[2 * k].substringAfter("input ").split(" ").filter { it != "output" }
                .map { it.toFloat() }
            val ours = ToneMap.curve(ToneMap.Transfer.PQ, peakNits = peak)
            assertEquals("$peak: input min", numbers[0], ours.inputMin, 1e-7f)
            assertEquals("$peak: input max", numbers[1], ours.inputMax, 1e-6f)
            assertEquals("$peak: output min", numbers[2], ours.outputMin, 1e-6f)
            assertEquals("$peak: output max", numbers[3], ours.outputMax, 1e-6f)
        }
    }

    @Test
    fun `the BT_2390 curve agrees with pl_tone_map_sample`() {
        listOf(0f, 400f, 1000f, 4000f).forEachIndexed { k, peak ->
            val samples = curveLines[2 * k + 1].removePrefix("curve ").split(" ").map { it.toFloat() }
            val curve = ToneMap.curve(ToneMap.Transfer.PQ, peakNits = peak)
            samples.forEachIndexed { i, expected ->
                assertEquals("$peak at ${i / 1000f}", expected, curve.map(i / 1000f), 2e-6f)
            }
        }
    }

    @Test
    fun `the matrices are libplacebo's`() {
        fun matrix(name: String) = curveLines.first { it.startsWith("$name ") }.split(" ").drop(1).map { it.toFloat() }
        fun same(name: String, expected: List<Float>, ours: ToneMap.Matrix) =
            expected.forEachIndexed { i, e -> assertEquals("$name[$i]", e, ours.m[i], 1e-6f) }
        same("rgb2lms", matrix("rgb2lms-bt2020"), ToneMap.rgbToLms(ToneMap.Primaries.BT2020))
        same("lms2rgb", matrix("lms2rgb-bt709"), ToneMap.lmsToRgb(ToneMap.Primaries.BT709))
        same("rgb2xyz", matrix("rgb2xyz-bt2020"), ToneMap.rgbToXyz(ToneMap.Primaries.BT2020))
    }

    @Test
    fun `printed constants round as C's printf does`() {
        // 18.8515625 is a tie at six decimals: printf goes to the even digit, Java's HALF_UP would not.
        assertEquals(18.851562f, ToneMap.printed(2413f / 4096f * 32f), 0f)
        assertEquals(0.159302f, ToneMap.printed(2610f / 4096f / 4f), 0f)
    }

    @Test
    fun `SDR white stays below the screen's peak and black stays black`() {
        val pass = ToneMap.pass(ToneMap.Transfer.PQ, peakNits = 1000f)
        val black = ToneMap.map(floatArrayOf(0f, 0f, 0f), pass)
        black.forEach { assertEquals(0f, it, 1f / 255) }
        val white = ToneMap.map(FloatArray(3) { ToneMap.pqOfNits(ToneMap.SDR_WHITE) }, pass)
        white.forEach { assertTrue("$it", it in 0.6f..1f) }
        val peak = ToneMap.map(FloatArray(3) { ToneMap.pqOfNits(1000f) }, pass)
        peak.forEach { assertEquals(1f, it, 2f / 255) }
    }

    @Test
    fun `the GL pass gets every uniform, the matrices column after column`() {
        val pass = ToneMap.pass(ToneMap.Transfer.HLG, peakNits = 1000f)
        val uniforms = HashMap<String, FloatArray>()
        pass.uniforms { name, value -> uniforms[name] = value }
        assertEquals(
            setOf(
                "uHlg", "uHlgLift", "uHlgOotf", "uLuma", "uRgbToLms", "uLmsToIpt", "uIptToLms", "uLmsToRgb", "uCurve",
                "uKnee", "uOutput", "uBt1886",
            ),
            uniforms.keys,
        )
        assertEquals(1f, uniforms.getValue("uHlg")[0])
        // Column-major: the second value is the first column's second row.
        assertEquals(pass.rgbToLms[1, 0], uniforms.getValue("uRgbToLms")[1])
        assertEquals(pass.curve.inputMax, uniforms.getValue("uCurve")[1])
    }
}
