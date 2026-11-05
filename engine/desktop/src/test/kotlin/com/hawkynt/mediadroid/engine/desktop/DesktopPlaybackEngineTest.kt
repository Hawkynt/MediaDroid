package com.hawkynt.mediadroid.engine.desktop

import com.hawkynt.mediadroid.engine.MediaRequest
import com.hawkynt.mediadroid.engine.PlaybackTuning
import com.hawkynt.mediadroid.engine.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DesktopPlaybackEngineTest {

    private fun fixture(name: String): String = File(requireNotNull(javaClass.getResource("/$name")).toURI()).path
    private val clip: String = fixture("clip.mp4")

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

    @Test
    fun `a subtitle file beside the video is a track, and its line shows while it is due`() {
        val sink = ManualSink()
        val srt = MediaRequest.SubtitleFile(fixture("clip.de.srt"), null, "de", "Deutsch", forced = false)
        DesktopPlaybackEngine(PlaybackTuning.Mutable()) { sink }.use { engine ->
            engine.open(MediaRequest(clip, subtitles = listOf(srt)))
            waitFor { engine.isPlaying }
            val track = engine.tracks.single { it.kind == Track.Kind.TEXT }
            assertEquals(1, track.source)
            assertNull(engine.subtitle())
            engine.select(Track.Kind.TEXT, track.id)
            assertTrue(engine.tracks.single { it.kind == Track.Kind.TEXT }.selected)
            Thread.sleep(100) // the reader thread takes the switch in
            sink.playedMs = 300
            waitFor { engine.subtitle() == "First line" }
            sink.playedMs = 900
            waitFor { engine.subtitle() == null }
            engine.subtitlesOn = false
            sink.playedMs = 1200
            Thread.sleep(20)
            assertNull(engine.subtitle())
        }
    }

    @Test
    fun `a file's own subtitle stream shows the same way once picked`() {
        val sink = ManualSink()
        DesktopPlaybackEngine(PlaybackTuning.Mutable()) { sink }.use { engine ->
            engine.open(MediaRequest(fixture("subtitled.mkv")))
            waitFor { engine.isPlaying }
            val track = engine.tracks.single { it.kind == Track.Kind.TEXT }
            assertEquals(0, track.source)
            engine.select(Track.Kind.TEXT, track.id)
            sink.playedMs = 1200
            waitFor { engine.subtitle() == "Second line\nwith two rows" }
        }
    }
}
