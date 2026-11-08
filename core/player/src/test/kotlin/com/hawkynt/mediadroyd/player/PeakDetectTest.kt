package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reference peak detection against libplacebo 7.360's own (`pl_shader_detect_peak`, its defaults), run by
 * libplacebo on Mesa's OpenGL over a short scene: `resources/tonemap/peak.txt` and `peak-hlg.txt` hold the peak and
 * average libplacebo had detected after each frame (`make-expected.sh`).
 */
class PeakDetectTest {

    private fun picture(name: String): ShortArray {
        val bytes = requireNotNull(PeakDetectTest::class.java.getResourceAsStream("/tonemap/$name")) { name }
            .use { it.readBytes() }
        val data = bytes.copyOfRange(bytes.size - WIDTH * HEIGHT * 6, bytes.size)
        return ShortArray(data.size / 2) {
            ((data[2 * it].toInt() and 0xFF) shl 8 or (data[2 * it + 1].toInt() and 0xFF)).toShort()
        }
    }

    private fun expected(name: String): List<Pair<Float, Float>> =
        String(requireNotNull(PeakDetectTest::class.java.getResourceAsStream("/tonemap/$name")).use { it.readBytes() })
            .lines().filter { it.isNotBlank() }.map { line -> line.split(" ").let { it[0].toFloat() to it[1].toFloat() } }

    private fun scene(transfer: ToneMap.Transfer, frames: List<String>, expected: List<Pair<Float, Float>>) {
        val detect = PeakDetect()
        assertNull(detect.peak)
        frames.forEachIndexed { k, name ->
            detect.update(PeakDetect.blocks(picture(name), WIDTH, HEIGHT, transfer))
            // A pixel's luminance in 14-bit PQ is truncated: the shader compiler's float may land one step lower.
            assertEquals("frame $k ($name): peak", expected[k].first, detect.peak!!, 2f / PeakDetect.PQ_MAX)
            assertEquals("frame $k ($name): average", expected[k].second, detect.average!!, 2f / PeakDetect.PQ_MAX)
        }
    }

    @Test
    fun `a scene's peak and average follow libplacebo's, smoothed and through scene changes`() = scene(
        ToneMap.Transfer.PQ,
        listOf("input", "input", "dimmer", "dimmer", "dimmer", "darker", "dark", "input").map { "$it.ppm" },
        expected("peak.txt"),
    )

    @Test
    fun `HLG is measured as libplacebo measures it`() =
        scene(ToneMap.Transfer.HLG, listOf("input.ppm", "darker.ppm"), expected("peak-hlg.txt"))

    @Test
    fun `a black frame counts as black and a reset starts afresh`() {
        val detect = PeakDetect()
        detect.update(PeakDetect.Blocks(IntArray(4), IntArray(4)))
        assertEquals(1e-6f, detect.peak!!, 0f)
        detect.reset()
        assertNull(detect.peak)
        assertNull(detect.average)
    }

    @Test
    fun `the GL pass's bytes unpack into blocks`() {
        val blocks = PeakDetect.unpack(byteArrayOf(0x3F, -1, 0x12, 0x34, 0, 0, 0, 0), 2)
        assertEquals(16383, blocks.maxima[0])
        assertEquals(0x1234, blocks.averages[0])
        assertEquals(0, blocks.maxima[1])
    }

    private companion object {
        const val WIDTH = 96
        const val HEIGHT = 64
    }
}
