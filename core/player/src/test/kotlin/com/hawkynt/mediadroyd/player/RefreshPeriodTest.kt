package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshPeriodTest {

    private val hz60 = 16_666_667L

    @Test
    fun `callbacks asked for back to back measure the refresh`() {
        val period = RefreshPeriod(initialNs = 20_000_000L)
        var t = 0L
        repeat(80) {
            period.refreshed(t)
            period.askedAgain()
            t += 8_333_333L
        }
        assertEquals(8_333_333.0, period.periodNs.toDouble(), 50_000.0)
    }

    @Test
    fun `a gap after the queue ran empty spans several refreshes and does not count`() {
        val period = RefreshPeriod()
        var t = 0L
        repeat(200) {
            period.refreshed(t)
            // Every other refresh nothing waits: no callback is asked for until the next picture comes.
            if (it % 2 == 0) {
                period.askedAgain()
                t += hz60
            } else {
                t += 2 * hz60
            }
        }
        assertEquals(hz60.toDouble(), period.periodNs.toDouble(), 1_000.0)
    }

    @Test
    fun `a 24 Hz second screen is measured as such`() {
        val period = RefreshPeriod()
        var t = 0L
        repeat(80) {
            period.refreshed(t)
            period.askedAgain()
            t += 41_666_667L
        }
        assertEquals(41_666_667.0, period.periodNs.toDouble(), 50_000.0)
    }

    @Test
    fun `gaps no screen has are ignored`() {
        val period = RefreshPeriod()
        period.refreshed(0L)
        period.askedAgain()
        period.refreshed(500_000_000L)
        assertEquals(hz60, period.periodNs)
    }
}
