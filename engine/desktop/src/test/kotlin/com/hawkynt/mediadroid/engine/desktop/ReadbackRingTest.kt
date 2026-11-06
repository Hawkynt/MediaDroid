package com.hawkynt.mediadroid.engine.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadbackRingTest {

    @Test
    fun `read-backs go into the buffers in turn`() {
        val ring = ReadbackRing(buffers = 3, lag = 2)
        assertEquals(0, ring.start())
        assertEquals(1, ring.start())
        assertEquals(2, ring.start())
        assertEquals(0, ring.finish())
        assertEquals(0, ring.start())
    }

    @Test
    fun `without lag each read-back is collected as it starts`() {
        val ring = ReadbackRing(buffers = 1, lag = 0)
        ring.start()
        assertTrue(ring.mustCollect)
        assertEquals(0, ring.finish())
        assertFalse(ring.mustCollect)
    }

    @Test
    fun `with a lag of one a picture is handed out when the next one is read back`() {
        val ring = ReadbackRing(buffers = 2, lag = 1)
        ring.start()
        // Nothing handed out yet: the first one is waited for rather than shown late.
        assertTrue(ring.mustCollect)
        assertEquals(0, ring.finish())
        ring.start()
        assertFalse(ring.mustCollect)
        ring.start()
        assertTrue(ring.mustCollect)
        assertEquals(1, ring.finish())
        assertFalse(ring.mustCollect)
    }

    @Test
    fun `the oldest in flight is the one collected next, none when nothing is`() {
        val ring = ReadbackRing(buffers = 3, lag = 2)
        assertNull(ring.oldest)
        ring.start()
        ring.start()
        assertEquals(0, ring.oldest)
        assertEquals(2, ring.inFlight)
        ring.finish()
        assertEquals(1, ring.oldest)
    }

    @Test(expected = IllegalStateException::class)
    fun `no read-back starts into a buffer still in flight`() {
        val ring = ReadbackRing(buffers = 2, lag = 1)
        ring.start()
        ring.start()
        ring.start()
    }

    @Test
    fun `a reset forgets what is in flight and waits for the next again`() {
        val ring = ReadbackRing(buffers = 2, lag = 1)
        ring.start()
        ring.finish()
        ring.start()
        ring.reset()
        assertEquals(0, ring.inFlight)
        ring.start()
        assertTrue(ring.mustCollect)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a lag needs a buffer more than it`() {
        ReadbackRing(buffers = 2, lag = 2)
    }
}
