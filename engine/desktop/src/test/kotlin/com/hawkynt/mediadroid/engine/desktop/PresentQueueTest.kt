package com.hawkynt.mediadroid.engine.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PresentQueueTest {

    @Test
    fun `the stage writes into the slots in turn while nobody shows one`() {
        val queue = PresentQueue(3)
        assertEquals(0, queue.writeSlot())
        assertEquals(1, queue.writeSlot())
        assertEquals(2, queue.writeSlot())
        assertEquals(0, queue.writeSlot())
    }

    @Test
    fun `the window takes the newest picture posted and nothing until another comes`() {
        val queue = PresentQueue(3)
        queue.post(queue.writeSlot())
        queue.post(queue.writeSlot())
        assertEquals(1, queue.take())
        assertNull(queue.take())
    }

    @Test
    fun `the slot on screen is never written into`() {
        val queue = PresentQueue(3)
        val first = queue.writeSlot()
        queue.post(first)
        assertEquals(first, queue.take())
        repeat(10) { assertNotEquals(first, queue.writeSlot()) }
    }

    @Test
    fun `a slot taken frees the one shown before`() {
        val queue = PresentQueue(3)
        queue.post(queue.writeSlot())
        assertEquals(0, queue.take())
        assertEquals(1, queue.writeSlot())
        queue.post(1)
        assertEquals(1, queue.take())
        assertEquals(2, queue.writeSlot())
        assertEquals(0, queue.writeSlot())
    }

    @Test
    fun `the slot shown is the one the window holds`() {
        val queue = PresentQueue(3)
        assertNull(queue.shown)
        queue.post(queue.writeSlot())
        queue.take()
        assertEquals(0, queue.shown)
    }

    @Test
    fun `a picture waiting to be taken is never written over, the newer one goes beside it and replaces it`() {
        val queue = PresentQueue(3)
        queue.post(queue.writeSlot())
        assertEquals(0, queue.take())
        queue.post(queue.writeSlot())
        // Slot 0 is on screen and slot 1 waits — the window may take it any moment: the next goes into slot 2.
        repeat(5) {
            val slot = queue.writeSlot()
            assertEquals(2, slot)
            queue.post(slot)
            // Now slot 2 waits and slot 1 is free again.
            assertEquals(1, queue.writeSlot())
            queue.post(1)
        }
        assertEquals(1, queue.take())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `two slots leave none to write while one is shown and one waits`() {
        PresentQueue(2)
    }
}
