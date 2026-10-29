package com.hawkynt.mediadroid.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val WIDTH = 48
private const val HEIGHT = 64

/** A fixture from `resources/deinterlace/`. */
internal fun resource(name: String): ByteArray =
    requireNotNull(DeinterlacerTest::class.java.getResourceAsStream("/deinterlace/$name")) { name }
        .use { it.readBytes() }

/** Frame [index] of a raw 48 × 64 grey fixture. */
internal fun plane(bytes: ByteArray, index: Int) = Deinterlacer.Plane(
    WIDTH,
    HEIGHT,
    IntArray(WIDTH * HEIGHT) { bytes[index * WIDTH * HEIGHT + it].toInt() and 0xFF },
)

/**
 * The reference deinterlacers against FFmpeg 6.0's own `yadif` and `bwdif` (C code, no assembly),
 * byte for byte. The inputs in `resources/deinterlace/` are written by `make-input.py` beside them; the
 * expected files are FFmpeg's output for them, made with the commands in that script's header.
 */
class DeinterlacerTest {

    private val input = resource("input.gray")

    private fun frame(index: Int) = plane(input, index)

    /** FFmpeg's output [index] of [file]; the frames at either end are left out, as they lack a neighbour. */
    private fun expected(file: String, index: Int) = plane(resource(file), index).pixels

    private fun check(method: Deinterlacer.Method, file: String, fieldRate: Boolean, topFirst: Boolean = true) {
        val frames = resource(if (topFirst) "input.gray" else "input-bff.gray")
        fun frame(index: Int) = plane(frames, index)
        fun run(k: Int, second: Boolean) =
            Deinterlacer.deinterlace(method, frame(k - 1), frame(k), frame(k + 1), second, topFirst).pixels
        for (k in 1..3) {
            val outputIndex = if (fieldRate) 2 * k else k
            assertArrayEquals("$method frame $k", expected(file, outputIndex), run(k, second = false))
            if (fieldRate) {
                assertArrayEquals("$method frame $k, second field", expected(file, 2 * k + 1), run(k, second = true))
            }
        }
    }

    @Test
    fun `YADIF rebuilds the second field exactly as FFmpeg does`() =
        check(Deinterlacer.Method.YADIF, "yadif-0.gray", fieldRate = false)

    @Test
    fun `YADIF at field rate rebuilds each field in turn exactly as FFmpeg does`() =
        check(Deinterlacer.Method.YADIF, "yadif-1.gray", fieldRate = true)

    @Test
    fun `BWDIF rebuilds the second field exactly as FFmpeg does`() =
        check(Deinterlacer.Method.BWDIF, "bwdif-0.gray", fieldRate = false)

    @Test
    fun `BWDIF at field rate rebuilds each field in turn exactly as FFmpeg does`() =
        check(Deinterlacer.Method.BWDIF, "bwdif-1.gray", fieldRate = true)

    @Test
    fun `bottom field first, YADIF and BWDIF rebuild each field in turn exactly as FFmpeg does`() {
        check(Deinterlacer.Method.YADIF, "yadif-0-bff.gray", fieldRate = false, topFirst = false)
        check(Deinterlacer.Method.YADIF, "yadif-1-bff.gray", fieldRate = true, topFirst = false)
        check(Deinterlacer.Method.BWDIF, "bwdif-0-bff.gray", fieldRate = false, topFirst = false)
        check(Deinterlacer.Method.BWDIF, "bwdif-1-bff.gray", fieldRate = true, topFirst = false)
    }

    @Test
    fun `the kept field passes through untouched`() {
        val out = Deinterlacer.deinterlace(
            Deinterlacer.Method.YADIF, frame(0), frame(1), frame(2), second = false, topFieldFirst = true,
        )
        for (y in 0 until HEIGHT step 2) {
            for (x in 0 until WIDTH) assertEquals(frame(1)[x, y], out[x, y])
        }
    }

    @Test
    fun `only the temporal deinterlacers need the neighbouring frames and can run at field rate`() {
        assertTrue(PictureAdjustment.Deinterlace.YADIF.isTemporal)
        assertTrue(PictureAdjustment.Deinterlace.BWDIF.isTemporal)
        assertFalse(PictureAdjustment.Deinterlace.BOB.isTemporal)
        assertFalse(PictureAdjustment.Deinterlace.AUTO.isTemporal)
        assertTrue(PictureAdjustment(deinterlace = PictureAdjustment.Deinterlace.YADIF, fieldRate = true).isFieldRate)
        assertFalse(PictureAdjustment(deinterlace = PictureAdjustment.Deinterlace.BOB, fieldRate = true).isFieldRate)
        assertFalse(PictureAdjustment(deinterlace = PictureAdjustment.Deinterlace.YADIF).isFieldRate)
    }
}
