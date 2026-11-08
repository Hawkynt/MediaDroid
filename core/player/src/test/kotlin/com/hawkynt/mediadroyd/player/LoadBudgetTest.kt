package com.hawkynt.mediadroyd.player

import org.junit.Assert.assertEquals
import org.junit.Test

class LoadBudgetTest {
    private val mb = 1024L * 1024

    @Test
    fun `undecoded video takes at most a quarter of the app's memory`() {
        assertEquals(64 * mb, LoadBudget.targetBytes(heapBytes = 256 * mb))
        assertEquals(100 * mb, LoadBudget.targetBytes(heapBytes = 400 * mb))
    }

    @Test
    fun `never less than enough for a few seconds of 4K, never more than Media3's own default`() {
        assertEquals(LoadBudget.MIN_BYTES, LoadBudget.targetBytes(heapBytes = 64 * mb))
        assertEquals(LoadBudget.MAX_BYTES, LoadBudget.targetBytes(heapBytes = 4096 * mb))
    }
}
