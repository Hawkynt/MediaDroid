package com.hawkynt.mediadroid.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AbLoopTest {

    @Test
    fun `one button cycles through start, end and off`() {
        val start = AbLoop.OFF.mark(10_000)
        assertEquals(AbLoop(10_000, null), start)
        assertFalse(start.repeating)
        val both = start.mark(25_000)
        assertEquals(AbLoop(10_000, 25_000), both)
        assertTrue(both.repeating)
        assertEquals(AbLoop.OFF, both.mark(30_000))
    }

    @Test
    fun `an end marked before the start swaps the two`() {
        assertEquals(AbLoop(5_000, 10_000), AbLoop.OFF.mark(10_000).mark(5_000))
    }

    @Test
    fun `an end too close to the start is not taken`() {
        val start = AbLoop.OFF.mark(10_000)
        assertEquals(start, start.mark(10_000 + AbLoop.MIN_LENGTH_MS - 1))
        assertEquals(AbLoop(10_000, 10_000 + AbLoop.MIN_LENGTH_MS), start.mark(10_000 + AbLoop.MIN_LENGTH_MS))
    }

    @Test
    fun `reaching the end jumps back to the start`() {
        val loop = AbLoop(10_000, 25_000)
        assertNull(loop.jumpFrom(9_000))
        assertNull(loop.jumpFrom(24_999))
        assertEquals(10_000L, loop.jumpFrom(25_000))
        assertEquals(10_000L, loop.jumpFrom(40_000))
    }

    @Test
    fun `nothing jumps while only the start is marked or nothing is`() {
        assertNull(AbLoop(10_000, null).jumpFrom(60_000))
        assertNull(AbLoop.OFF.jumpFrom(60_000))
    }
}
