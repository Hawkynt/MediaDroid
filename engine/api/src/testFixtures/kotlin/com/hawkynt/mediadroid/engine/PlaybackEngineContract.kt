package com.hawkynt.mediadroid.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * What every [PlaybackEngine] does alike, as the screens rely on it: one subclass per engine — the fake in this
 * module, each platform's own in its module — supplies the engine, something to play and a way to let time pass;
 * the tests here are the same for all of them. A real engine works on threads of its own, so every expectation is
 * waited for (at most [patienceMs]) while [pass] lets the engine's time go on.
 */
abstract class PlaybackEngineContract {

    /** A new engine, closed after each test. */
    protected abstract fun newEngine(): PlaybackEngine

    /** Something to play: a video with pictures and sound, at least two seconds long. */
    protected abstract val media: MediaRequest

    /** What the engine cannot open. */
    protected open val unreadable: MediaRequest = MediaRequest("/nowhere/missing.mp4")

    /** A picture output this engine takes. */
    protected abstract fun output(): VideoOutput

    /**
     * Lets about [ms] of the engine's time go by: a playing engine's clock moves on, its pictures are taken as a
     * screen would take them, its events are delivered.
     */
    protected abstract fun pass(engine: PlaybackEngine, ms: Long)

    /** How far a position may stand from the one asked for: a decoder lands on a picture's time. */
    protected open val toleranceMs: Long = 100

    /** How long an expectation is waited for. */
    protected open val patienceMs: Long = 5_000

    protected lateinit var engine: PlaybackEngine
    private val heard = Recorder()

    @Before
    fun build() {
        engine = newEngine()
        engine.addListener(heard)
    }

    @After
    fun tearDown() = engine.close()

    /** Every event heard, in order; engines tell from their own threads. */
    private class Recorder : PlaybackEngine.Listener {
        val events: MutableList<Any> = java.util.Collections.synchronizedList(mutableListOf())

        data class Playing(val playing: Boolean)
        data class State(val state: PlaybackState)
        data class Seeked(val positionMs: Long)
        data class Size(val width: Int, val height: Int)
        data class Tracks(val tracks: List<Track>)
        data class Error(val code: String)
        data class Decoder(val kind: Track.Kind, val name: String)
        object Ended
        object FirstFrame

        override fun onPlayingChanged(playing: Boolean) { events += Playing(playing) }
        override fun onEnded() { events += Ended }
        override fun onStateChanged(state: PlaybackState) { events += State(state) }
        override fun onSeeked(positionMs: Long) { events += Seeked(positionMs) }
        override fun onFirstFrame() { events += FirstFrame }
        override fun onDecoder(kind: Track.Kind, name: String) { events += Decoder(kind, name) }
        override fun onTracksChanged(tracks: List<Track>) { events += Tracks(tracks) }
        override fun onVideoSize(width: Int, height: Int, pixelAspect: Float) { events += Size(width, height) }
        override fun onError(code: String) { events += Error(code) }

        inline fun <reified T> all(): List<T> = synchronized(events) { events.filterIsInstance<T>() }
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + patienceMs
        while (!condition()) {
            if (System.currentTimeMillis() > until) fail("waited in vain for $what")
            pass(engine, STEP_MS)
        }
    }

    private fun assertNear(expected: Long, actual: Long) = assertTrue(
        "$actual is not within $toleranceMs ms of $expected",
        kotlin.math.abs(actual - expected) <= toleranceMs,
    )

    private fun openPaused(request: MediaRequest = media) {
        engine.open(request, autoPlay = false)
        waitFor("ready") { engine.state == PlaybackState.READY }
    }

    @Test
    fun `nothing open, it is idle, still and empty`() {
        assertNull(engine.openedPath)
        assertEquals(PlaybackState.IDLE, engine.state)
        assertFalse(engine.isPlaying)
        assertEquals(0L, engine.durationMs)
        assertTrue(engine.tracks.isEmpty())
    }

    @Test
    fun `opened without playing, it stands ready and paused at the start`() {
        openPaused()
        assertEquals(media.path, engine.openedPath)
        assertFalse(engine.isPlaying)
        waitFor("its length") { engine.durationMs >= MIN_LENGTH_MS }
        assertNear(0, engine.positionMs)
        assertTrue(heard.all<Recorder.State>().map { it.state }.contains(PlaybackState.READY))
    }

    @Test
    fun `opened at a start time, it stands there`() {
        openPaused(media.copy(startMs = 1_000))
        assertNear(1_000, engine.positionMs)
    }

    @Test
    fun `opened to play, it plays, and pause and play are heard`() {
        engine.open(media)
        waitFor("playing") { engine.isPlaying && Recorder.Playing(true) in heard.all<Recorder.Playing>() }
        engine.pause()
        waitFor("paused") { !engine.isPlaying && heard.all<Recorder.Playing>().last() == Recorder.Playing(false) }
        engine.play()
        waitFor("playing again") { engine.isPlaying && heard.all<Recorder.Playing>().last() == Recorder.Playing(true) }
    }

    @Test
    fun `playing, the position moves on`() {
        engine.open(media)
        waitFor("playing") { engine.isPlaying }
        val from = engine.positionMs
        waitFor("the position moving on") { engine.positionMs >= from + 300 }
    }

    @Test
    fun `a seek is taken, told and stood at`() {
        openPaused()
        engine.seekTo(1_500)
        waitFor("the seek told") { heard.all<Recorder.Seeked>().isNotEmpty() }
        assertNear(1_500, heard.all<Recorder.Seeked>().last().positionMs)
        waitFor("ready after the seek") { engine.state == PlaybackState.READY }
        assertNear(1_500, engine.positionMs)
    }

    @Test
    fun `played to its end, it ends`() {
        openPaused()
        waitFor("its length") { engine.durationMs >= MIN_LENGTH_MS }
        engine.seekTo(engine.durationMs - 400)
        engine.play()
        waitFor("the end") { Recorder.Ended in heard.events }
        waitFor("the end's state") { engine.state == PlaybackState.ENDED && !engine.isPlaying }
        assertTrue(Recorder.State(PlaybackState.ENDED) in heard.events)
    }

    @Test
    fun `the tracks and the picture's size are told once known`() {
        // Media3 tells the size as the first picture reaches an output.
        engine.videoOutput = output()
        openPaused()
        waitFor("tracks with pictures and sound") {
            heard.all<Recorder.Tracks>().any { told ->
                told.tracks.any { it.kind == Track.Kind.AUDIO } && told.tracks.any { it.kind == Track.Kind.VIDEO }
            }
        }
        waitFor("the picture's size") { heard.all<Recorder.Size>().any { it.width > 0 && it.height > 0 } }
        assertTrue(engine.tracks.any { it.kind == Track.Kind.AUDIO && it.selected })
    }

    @Test
    fun `the picture's format is known once it shows`() {
        assertNull(engine.videoFormat)
        engine.videoOutput = output()
        openPaused()
        waitFor("the picture's format") { (engine.videoFormat?.width ?: 0) > 0 }
        val format = engine.videoFormat!!
        assertTrue(format.height > 0 && format.pixelAspect > 0f && format.frameRate > 0f)
        assertEquals(VideoFormat.Transfer.SDR, format.transfer)
    }

    @Test
    fun `the decoders at work are told`() {
        engine.videoOutput = output()
        engine.open(media)
        waitFor("a video and a sound decoder") {
            val kinds = heard.all<Recorder.Decoder>().filter { it.name.isNotEmpty() }.map { it.kind }
            Track.Kind.VIDEO in kinds && Track.Kind.AUDIO in kinds
        }
    }

    @Test
    fun `an audio track picked plays`() {
        openPaused()
        waitFor("tracks") { engine.tracks.isNotEmpty() }
        val other = Tracks.of(engine.tracks, Track.Kind.AUDIO).firstOrNull { !it.selected }
        assumeTrue("the media has a second audio track", other != null)
        engine.select(Track.Kind.AUDIO, other!!.id)
        waitFor("the picked track playing") {
            Tracks.of(engine.tracks, Track.Kind.AUDIO).single { it.selected }.id == other.id
        }
    }

    @Test
    fun `speed, volume and subtitles read back as set`() {
        openPaused()
        engine.speed = 1.5f
        engine.volume = 0.25f
        engine.subtitlesOn = false
        assertEquals(1.5f, engine.speed, 1e-3f)
        assertEquals(0.25f, engine.volume, 1e-3f)
        assertFalse(engine.subtitlesOn)
        engine.subtitlesOn = true
        assertTrue(engine.subtitlesOn)
    }

    @Test
    fun `a new video starts without a second subtitle`() {
        openPaused()
        assertNull(engine.secondSubtitle)
        assertTrue(engine.tracks.none { it.second })
    }

    @Test
    fun `a listener removed hears nothing more`() {
        val late = Recorder()
        engine.addListener(late)
        engine.removeListener(late)
        engine.open(media)
        waitFor("playing") { engine.isPlaying && heard.all<Recorder.Playing>().isNotEmpty() }
        assertTrue(late.events.isEmpty())
    }

    @Test
    fun `the picture output reads back as set, and none is none`() {
        val output = output()
        engine.videoOutput = output
        assertSame(output, engine.videoOutput)
        engine.videoOutput = null
        assertNull(engine.videoOutput)
    }

    @Test
    fun `an output the engine does not take is refused`() {
        try {
            engine.videoOutput = object : VideoOutput {}
            fail("a foreign output was taken")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `the first picture reaches the output`() {
        engine.videoOutput = output()
        openPaused()
        waitFor("the first picture") { Recorder.FirstFrame in heard.events }
    }

    @Test
    fun `what cannot be opened is told or refused, and nothing plays`() {
        try {
            engine.open(unreadable)
            waitFor("the error") { heard.all<Recorder.Error>().isNotEmpty() }
        } catch (_: Exception) {
        }
        assertFalse(engine.isPlaying)
        assertEquals(PlaybackState.IDLE, engine.state)
    }

    @Test
    fun `closed, it is idle again`() {
        openPaused()
        engine.close()
        assertEquals(PlaybackState.IDLE, engine.state)
        assertFalse(engine.isPlaying)
    }

    private companion object {
        const val STEP_MS = 10L

        /** The least [media] lasts. */
        const val MIN_LENGTH_MS = 2_000L
    }
}
