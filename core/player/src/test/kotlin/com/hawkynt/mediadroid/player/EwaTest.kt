package com.hawkynt.mediadroid.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The EWA Lanczos-sharp weights against libplacebo's own definition evaluated with the C library's
 * Bessel function: `resources/ewa/weights.txt`, printed by `oracle.c` beside it.
 */
class EwaTest {

    @Test
    fun `the weights are libplacebo's ewa_lanczossharp`() {
        val lines = String(requireNotNull(javaClass.getResourceAsStream("/ewa/weights.txt")).readBytes())
            .lines().filter { it.isNotBlank() }
        for (line in lines) {
            val (x, expected) = line.split(' ').map { it.toDouble() }
            assertEquals("weight at $x", expected, Ewa.weight(x), 2e-7)
        }
    }

    @Test
    fun `the weights reach just over three pixels and are one at the centre`() {
        assertEquals(1.0, Ewa.weight(0.0), 0.0)
        assertEquals(0.0, Ewa.weight(Ewa.RADIUS + 0.01), 0.0)
        assertEquals(3.17760, Ewa.RADIUS, 1e-5)
    }

    @Test
    fun `the table the graphics pass reads holds the weights by distance`() {
        val table = Ewa.table(65)
        assertEquals(1.0f, table[0], 1e-6f)
        assertEquals(Ewa.weight(Ewa.RADIUS / 2).toFloat(), table[32], 1e-6f)
        assertEquals(0.0f, table[64], 1e-6f)
    }
}
