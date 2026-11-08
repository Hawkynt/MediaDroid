package com.hawkynt.mediadroyd.engine.desktop

import com.hawkynt.mediadroyd.engine.MediaRequest
import com.hawkynt.mediadroyd.engine.PlaybackTuning
import com.hawkynt.mediadroyd.engine.Track
import com.hawkynt.mediadroyd.player.AssPicture
import com.hawkynt.mediadroyd.player.LibAss
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit

/**
 * The desktop engine draws ASS subtitles through libass ([LibAssTest] holds libass itself to its oracle): a file's own
 * ASS stream with the fonts it carries, and an ASS file beside the video. It needs the native library and FFmpeg's
 * natives for this system: `-PnativeLibrary` (_desktop.yml, on Linux and Windows x64).
 */
class LibAssEngineTest {
    // A native call that never returns fails the test with where it hangs, not the whole run.
    @get:Rule
    val timeout: Timeout = Timeout.builder().withTimeout(60, TimeUnit.SECONDS).withLookingForStuckThread(true).build()

    @Before
    fun library() {
        assertTrue("the native library did not load", NativeNetworks.loaded)
        assertTrue("the native library carries no libass", LibAss.available)
    }

    private fun fixture(name: String) = java.io.File(requireNotNull(javaClass.getResource(name)).toURI()).path

    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "timed out" }
            Thread.sleep(5)
        }
    }

    /** A sound output that plays nothing and never moves on: the clock stays where it is put. */
    private class SilentSink : AudioSink {
        override fun open(sampleRate: Int, channels: Int) = Unit
        override fun write(pcm: ByteArray) = Thread.sleep(1)
        override fun available(): Int = Int.MAX_VALUE
        override fun playedMs(): Long = 0
        override fun pause() = Unit
        override fun resume() = Unit
        override fun flush() = Unit
        override var volume: Float = 1f
        override fun close() = Unit
    }

    /** The engine at 1.5 s with [pick] its subtitle track: what libass draws there, at the picture's size. */
    private fun drawnAt1500(request: MediaRequest, pick: (List<Track>) -> Track): AssPicture =
        DesktopPlaybackEngine(PlaybackTuning.Mutable()) { SilentSink() }.use { engine ->
            engine.open(request)
            engine.select(Track.Kind.TEXT, pick(engine.tracks).id)
            assertTrue(engine.drawsSubtitles)
            // Playing from the start reads the events (they begin at 0); paused at 1.5 s, libass still has them.
            var drawn: AssPicture? = null
            waitFor {
                engine.subtitlePicture(WIDTH, HEIGHT)?.let { drawn = it }
                drawn?.isEmpty == false
            }
            engine.pause()
            engine.seekTo(1500)
            waitFor { engine.positionMs == 1500L }
            drawn = engine.subtitlePicture(WIDTH, HEIGHT) ?: drawn
            // The text overlay stays away: libass draws the subtitles.
            assertNull(engine.subtitle())
            drawn!!
        }

    @Test
    fun `the desktop engine draws a file's own ASS stream with the fonts it carries`() {
        val drawn = drawnAt1500(MediaRequest(fixture("/ass/styled.mkv"))) { tracks ->
            tracks.single { it.kind == Track.Kind.TEXT }
        }
        // The karaoke line at the top and the dialogue at the bottom: the box spans most of the frame.
        assertTrue(drawn.y < 60 && drawn.y + drawn.height > 300)
    }

    @Test
    fun `the desktop engine draws an ASS file beside the video`() {
        val ass = MediaRequest.SubtitleFile(fixture("/ass/styled.ass"), null, "en", "English", forced = false)
        val drawn = drawnAt1500(MediaRequest(fixture("/clip.mp4"), subtitles = listOf(ass))) { tracks ->
            tracks.single { it.kind == Track.Kind.TEXT && it.source == 1 }
        }
        assertTrue(drawn.y < 60 && drawn.y + drawn.height > 300)
    }

    @Test
    fun `the subtitle delay moves the ASS lines as it moves the text ones`() {
        val ass = MediaRequest.SubtitleFile(fixture("/ass/styled.ass"), null, "en", "English", forced = false)
        val tuning = PlaybackTuning.Mutable()
        DesktopPlaybackEngine(tuning) { SilentSink() }.use { engine ->
            engine.open(MediaRequest(fixture("/clip.mp4"), subtitles = listOf(ass)))
            engine.select(Track.Kind.TEXT, engine.tracks.single { it.kind == Track.Kind.TEXT && it.source == 1 }.id)
            var drawn: AssPicture? = null
            waitFor {
                engine.subtitlePicture(WIDTH, HEIGHT)?.let { drawn = it }
                drawn?.isEmpty == false
            }
            engine.pause()
            engine.seekTo(1500)
            // Drawn at the seek's target from the moment it returns, whether or not the reader has taken it in yet.
            assertEquals(1500L, engine.positionMs)
            // The lines run from 0 to 3 s: 2 s later, at 1.5 s they have not begun.
            tuning.subtitleDelayMs = 2_000
            drawn = engine.subtitlePicture(WIDTH, HEIGHT) ?: drawn
            assertTrue(drawn!!.isEmpty)
            // 1 s sooner they still show; 2 s sooner they are over.
            tuning.subtitleDelayMs = -1_000
            drawn = engine.subtitlePicture(WIDTH, HEIGHT) ?: drawn
            assertTrue(!drawn!!.isEmpty)
            tuning.subtitleDelayMs = -2_000
            drawn = engine.subtitlePicture(WIDTH, HEIGHT) ?: drawn
            assertTrue(drawn!!.isEmpty)
        }
    }

    private companion object {
        const val WIDTH = 640
        const val HEIGHT = 360
    }
}
