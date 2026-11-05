package com.hawkynt.mediadroid.engine.desktop

import com.hawkynt.mediadroid.engine.MediaRequest
import com.hawkynt.mediadroid.engine.PlaybackTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DesktopPlaybackEngineTest {

    private val clip: String = File(requireNotNull(javaClass.getResource("/clip.mp4")).toURI()).path

    /** A sound output whose played time the test moves on by hand. */
    private class ManualSink : AudioSink {
        @Volatile var playedMs = 0L
        @Volatile var flushed = 0
        override fun open(sampleRate: Int, channels: Int) = Unit
        override fun write(pcm: ByteArray) = Thread.sleep(1)
        override fun playedMs(): Long = playedMs
        override fun pause() = Unit
        override fun resume() = Unit
        override fun flush() {
            flushed++
            playedMs = 0
        }
        override var volume: Float = 1f
        override fun close() = Unit
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "timed out" }
            Thread.sleep(5)
        }
    }

    @Test
    fun `the picture shown is the latest one due by the sound's clock`() {
        val sink = ManualSink()
        DesktopPlaybackEngine(PlaybackTuning.Mutable()) { sink }.use { engine ->
            engine.open(MediaRequest(clip))
            waitFor { engine.durationMs > 0 && engine.isPlaying }
            waitFor { engine.picture()?.ptsMs == 0L }
            sink.playedMs = 130
            waitFor { engine.picture()?.ptsMs == 120L }
            assertEquals(130L, engine.positionMs)
        }
    }

    @Test
    fun `a seek starts the clock and the pictures at the new time`() {
        val sink = ManualSink()
        DesktopPlaybackEngine(PlaybackTuning.Mutable()) { sink }.use { engine ->
            engine.open(MediaRequest(clip))
            waitFor { engine.isPlaying }
            engine.seekTo(1000)
            waitFor { sink.flushed > 0 && engine.picture()?.ptsMs == 1000L }
            assertEquals(1000L, engine.positionMs)
            sink.playedMs = 90
            waitFor { engine.picture()?.ptsMs == 1080L }
        }
    }

    @Test
    fun `nothing is shown before anything is open`() {
        DesktopPlaybackEngine(PlaybackTuning.Mutable()) { ManualSink() }.use { engine ->
            assertNull(engine.picture())
            assertEquals(0L, engine.durationMs)
            assertTrue(engine.tracks.isEmpty())
        }
    }
}
