package com.hawkynt.mediadroid.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class NeuralChecksTest {
    private val heights = ArrayList<Int>()
    private val progress = object : NeuralChecks.Progress {
        override fun running(height: Int) = Unit
        override fun timed(height: Int, ms: Double) {
            heights += height
        }
    }

    private fun rgb(rgba: ByteBuffer, w: Int, h: Int): ByteBuffer = ByteBuffer.allocateDirect(w * h * 3).also {
        for (i in 0 until w * h) for (c in 0 until 3) it.put(rgba.get(i * 4 + c))
        it.rewind()
    }

    @Test
    fun `a network that makes the true middle passes, timed size by size until it falls behind`() {
        // The check's pair is the test frames at shifts 0 and 2 (no ids), its middle shift 1; the timed pairs carry
        // ids. This one keeps up at the check's small picture and takes longer than the budget at 480p.
        val net = NeuralChecks.Between { _, _, w, h, earlierId, _, _, _ ->
            if (earlierId == 0L) {
                rgb(NeuralChecks.testFrame(w, h, 1), w, h)
            } else {
                Thread.sleep(NeuralInterpolation.BUDGET_MS.toLong() + 5)
                ByteBuffer.allocateDirect(w * h * 3)
            }
        }
        val timing = requireNotNull(NeuralChecks.interpolator(net, "CPU", progress))
        assertEquals("CPU", timing.unit)
        assertEquals(listOf(480), timing.msByHeight.keys.toList())
        assertTrue(timing.msByHeight.getValue(480) > NeuralInterpolation.BUDGET_MS)
        assertEquals(listOf(480), heights)
    }

    @Test
    fun `a network that only repeats a frame computes wrong and is not timed`() {
        val net = NeuralChecks.Between { earlier, _, w, h, _, _, _, _ -> rgb(earlier, w, h) }
        assertNull(NeuralChecks.interpolator(net, "GPU", progress))
        assertTrue(heights.isEmpty())
    }

    @Test
    fun `an upscaler that enlarges by repeating pixels runs right, one that darkens does not`() {
        fun enlarge(scale: Int, darken: Int) = NeuralChecks.Enlarge { picture, w, h ->
            ByteBuffer.allocateDirect(w * h * scale * scale * 3).also { out ->
                for (y in 0 until h * scale) for (x in 0 until w * scale) for (c in 0 until 3) {
                    val v = (picture.get(((y / scale) * w + x / scale) * 4 + c).toInt() and 255) - darken
                    out.put(v.coerceAtLeast(0).toByte())
                }
                out.rewind()
            }
        }
        val timing = requireNotNull(NeuralChecks.enlarger(enlarge(4, 0), 4, "GPU", progress))
        assertEquals(listOf(PictureCheck.HEIGHT), timing.msByHeight.keys.toList())
        assertNull(NeuralChecks.enlarger(enlarge(2, 60), 2, "GPU", progress))
    }
}
