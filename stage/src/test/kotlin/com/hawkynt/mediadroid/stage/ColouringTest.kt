package com.hawkynt.mediadroid.stage

import com.hawkynt.mediadroid.player.Colorizing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ColouringTest {

    private val gl = RecordingGles()
    private val colouring = Colouring(gl, Passes(gl))
    private val from = FakeTarget(1, 64, 48)
    private val into = FakeTarget(2, 64, 48)
    private val opened = AtomicInteger()
    private val closed = AtomicInteger()
    private val network = object : Colouring.Network {
        override fun run(grey: FloatArray) = FloatArray(2 * Colorizing.SIZE * Colorizing.SIZE) { 10f }

        override fun close() {
            closed.incrementAndGet()
        }
    }

    private fun colour(key: Any = "net") = colouring.colored(from, key, { opened.incrementAndGet(); network }) { into }

    // Each picture goes to the network once it is free; its colour shows from the picture after it came back.
    private fun untilColoured(): DrawTarget {
        repeat(200) {
            val out = colour()
            if (out !== from) return out
            Thread.sleep(10)
        }
        return from
    }

    @Test
    fun `the picture stays as it is until the network's colour is in, then shows it`() {
        assertSame(from, colour())
        assertSame(into, untilColoured())
        assertEquals(1, opened.get())
        assertTrue(gl.calls.contains("read 256 x 256"))
        assertTrue(gl.calls.any { it.startsWith("texSubImage 256 x 256") })
    }

    @Test
    fun `a cut drops the colour until the new scene's has come`() {
        untilColoured()
        gl.readValue = 200.toByte()
        assertSame(from, colour())
        assertSame(into, untilColoured())
    }

    @Test
    fun `another network is opened in place of the one before, which is closed`() {
        untilColoured()
        colouring.colored(from, "other", { opened.incrementAndGet(); network }) { into }
        repeat(200) {
            if (opened.get() == 2) return@repeat
            Thread.sleep(10)
        }
        colouring.close()
        Thread.sleep(100)
        assertEquals(2, opened.get())
        assertEquals(2, closed.get())
    }
}
