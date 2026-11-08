package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadBackDemandTest {

    @Test
    fun `nothing is read back while nobody asks`() {
        val demand = ReadBackDemand(histogramEveryMs = 500)
        repeat(100) { assertFalse(demand.wanted(it * 16L, histogramShown = false).any) }
    }

    @Test
    fun `a snapshot asks for the picture once`() {
        val demand = ReadBackDemand(500)
        demand.askSnapshot()
        assertEquals(ReadBackDemand.Wanted(snapshot = true, histogram = false), demand.wanted(0, false))
        assertFalse(demand.wanted(16, false).any)
    }

    @Test
    fun `the histogram asks at once and then every half second`() {
        val demand = ReadBackDemand(500)
        assertTrue(demand.wanted(1_000, histogramShown = true).histogram)
        assertFalse(demand.wanted(1_016, true).histogram)
        assertFalse(demand.wanted(1_499, true).histogram)
        assertTrue(demand.wanted(1_500, true).histogram)
    }

    @Test
    fun `the histogram switched off asks no more and on again asks at once`() {
        val demand = ReadBackDemand(500)
        demand.wanted(1_000, true)
        assertFalse(demand.wanted(1_050, false).any)
        assertTrue(demand.wanted(1_100, true).histogram)
    }

    @Test
    fun `a snapshot and the histogram due together take one read-back`() {
        val demand = ReadBackDemand(500)
        demand.askSnapshot()
        assertEquals(ReadBackDemand.Wanted(snapshot = true, histogram = true), demand.wanted(0, true))
    }
}
