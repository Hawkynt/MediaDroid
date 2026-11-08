package com.hawkynt.mediadroyd.stage

import com.hawkynt.mediadroyd.imaging.Xbrz
import com.hawkynt.mediadroyd.player.Ewa
import com.hawkynt.mediadroyd.player.MotionInterpolation
import com.hawkynt.mediadroyd.player.Nis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class StageTablesTest {

    private fun ByteArray.u16(at: Int) = ((this[at].toInt() and 255) shl 8) or (this[at + 1].toInt() and 255)

    @Test
    fun `NIS's table holds both filters' taps as nis-frag reads them back, to 16 bits`() {
        val table = StageTables.nis()
        assertEquals(StageTables.NIS_WIDTH * Nis.PHASES * 4, table.size)
        for (phase in 0 until Nis.PHASES) for (tap in 0 until StageTables.NIS_WIDTH) {
            val at = (phase * StageTables.NIS_WIDTH + tap) * 4
            // glsl/video/nis.frag: value * 2.5 - 1, from the two bytes high first.
            val scale = table.u16(at) / 65535f * 2.5f - 1f
            val sharpen = table.u16(at + 2) / 65535f * 2.5f - 1f
            assertEquals(Nis.scaleCoefficient(phase, tap), scale, 2.5f / 65535f)
            assertEquals(Nis.sharpenCoefficient(phase, tap), sharpen, 2.5f / 65535f)
        }
    }

    @Test
    fun `EWA's table holds the weights by distance in red and green, to 16 bits`() {
        val table = StageTables.ewa()
        val weights = Ewa.table(StageTables.EWA_SIZE)
        assertEquals(StageTables.EWA_SIZE * 4, table.size)
        for (i in weights.indices) {
            val back = table.u16(i * 4) / 65535f * 1.25f - 0.25f
            assertEquals(weights[i], back, 1.25f / 65535f * 1.01f)
            assertEquals(0, table[i * 4 + 2].toInt())
        }
    }

    @Test
    fun `xBRZ's table has a row per factor and pattern, each cell's share as m and n`() {
        val table = StageTables.xbrz()
        assertEquals(StageTables.XBRZ_WIDTH * StageTables.XBRZ_ROWS * 4, table.size)
        var row = 0
        for (factor in Xbrz.FACTORS) for (pattern in Xbrz.Pattern.entries) {
            val weights = Xbrz.weights(factor, pattern)
            for (cell in 0 until StageTables.XBRZ_WIDTH) {
                val at = (row * StageTables.XBRZ_WIDTH + cell) * 4
                val inside = cell < factor * factor
                assertEquals(if (inside) weights[cell * 2] else 0, table[at].toInt() and 255)
                assertEquals(if (inside) weights[cell * 2 + 1] else 1, table[at + 1].toInt() and 255)
            }
            row++
        }
        assertEquals(StageTables.XBRZ_ROWS, row)
    }

    @Test
    fun `the overlapped blocks' weights sit in red, the rest zero`() {
        val table = StageTables.obmc()
        for (i in 0 until StageTables.OBMC_SIZE * StageTables.OBMC_SIZE) {
            assertEquals(MotionInterpolation.obmcWeight(i), table[i * 4].toInt() and 255)
            assertTrue((1..3).all { table[i * 4 + it].toInt() == 0 })
        }
    }

    @Test
    fun `a table becomes a texture read texel by texel, clamped`() {
        val gl = RecordingGles()
        val texture = Passes(gl).table(2, 1, ByteArray(8) { it.toByte() })
        assertEquals("genTexture $texture", gl.calls.first())
        assertTrue(gl.calls.contains("texImage 2 x 1 8"))
        for (filter in listOf(Gles.TEXTURE_MIN_FILTER, Gles.TEXTURE_MAG_FILTER)) {
            assertTrue(gl.calls.contains("parameter $filter ${Gles.NEAREST}"))
        }
        assertTrue(abs(gl.calls.count { it.endsWith(" ${Gles.CLAMP_TO_EDGE}") }) == 2)
    }
}
