package com.hawkynt.mediadroid.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.GZIPInputStream

/**
 * The black-bar scan against FFmpeg's own cropdetect (n9.0.2, black mode), run over exactly the same luma:
 * `resources/cropdetect/` holds the synthetic clips and the filter's line for every frame it analysed
 * (`make-expected.sh`). cropdetect is GPL, so it is the oracle here, never the code.
 */
class BlackBarsTest {

    private fun frames(clip: String): List<Deinterlacer.Plane> {
        val bytes = requireNotNull(BlackBarsTest::class.java.getResourceAsStream("/cropdetect/$clip.gray.gz")) { clip }
            .use { GZIPInputStream(it).readBytes() }
        val size = WIDTH * HEIGHT
        return (0 until bytes.size / size).map { k ->
            Deinterlacer.Plane(WIDTH, HEIGHT, IntArray(size) { bytes[k * size + it].toInt() and 0xFF })
        }
    }

    /** cropdetect's lines: x1 x2 y1 y2 w h x y. */
    private fun expected(name: String): List<List<Int>> =
        String(requireNotNull(BlackBarsTest::class.java.getResourceAsStream("/cropdetect/$name.txt")).readBytes())
            .lines().filter { it.isNotBlank() }
            .map { line -> line.split(' ').map { it.substringAfter(':').toInt() } }

    private fun check(
        clip: String,
        case: String,
        skip: Int = 2,
        limit: Int = BlackBars.LIMIT,
        tolerance: Int = 0,
        resetAfter: Int = 0,
        multiple: Int = 16,
    ) {
        val lines = expected("$clip.$case")
        val scan = BlackBars(WIDTH, HEIGHT, limit, tolerance, resetAfter)
        val pictures = frames(clip).drop(skip)
        assertEquals(lines.size, pictures.size)
        pictures.zip(lines).forEachIndexed { k, (luma, line) ->
            scan.add(luma)
            val b = scan.picture
            val crop = scan.crop(multiple)
            val ours = listOf(b.left, b.right, b.top, b.bottom, crop.width, crop.height, crop.left, crop.top)
            assertEquals("$clip.$case frame ${k + skip}", line, ours)
        }
    }

    @Test
    fun `a letterbox agrees with cropdetect`() = check("letterbox", "default")

    @Test
    fun `a pillarbox agrees with cropdetect`() = check("pillarbox", "default")

    @Test
    fun `a windowbox agrees with cropdetect`() = check("windowbox", "default")

    @Test
    fun `a dark scene agrees with cropdetect, which crops it to the one light`() = check("dark", "default")

    @Test
    fun `subtitles in the bar widen the picture as in cropdetect`() = check("subtitles", "default")

    @Test
    fun `starting afresh every ten frames agrees`() = check("subtitles", "reset", resetAfter = 10)

    @Test
    fun `rounding to even sizes agrees`() = check("subtitles", "round2", skip = 0, multiple = 2)

    @Test
    fun `tolerated bright lines and another limit agree, even with nothing found`() =
        check("dark", "outliers", limit = 40, tolerance = 3)

    @Test
    fun `the subtitles really reached into the bar`() {
        val lines = expected("subtitles.default")
        assertTrue(lines.first()[3] < lines.last()[3])
    }

    private companion object {
        const val WIDTH = 192
        const val HEIGHT = 108
    }
}
