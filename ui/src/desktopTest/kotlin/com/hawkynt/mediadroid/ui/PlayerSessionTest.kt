package com.hawkynt.mediadroid.ui

import com.hawkynt.mediadroid.engine.FakePlaybackEngine
import com.hawkynt.mediadroid.engine.MediaRequest
import com.hawkynt.mediadroid.engine.Track
import com.hawkynt.mediadroid.library.Chapter
import com.hawkynt.mediadroid.nfo.TimeRange
import com.hawkynt.mediadroid.player.PlaybackMath
import com.hawkynt.mediadroid.state.StateFile
import com.hawkynt.mediadroid.state.StateStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The player's session, which both players play their queues by, over the engine contract's fake engine.
class PlayerSessionTest {

    private val store = StateStore(
        object : StateFile {
            override fun read(): String? = null

            override fun write(text: String) = Unit
        },
    )
    private val engine = FakePlaybackEngine(length = 600_000)
    private val queue = (1..3).map { Playable("Episode $it", null, "/show/e$it.mkv", null) }

    private fun session(autoAdvance: Boolean = true, skipping: Boolean = true, queue: List<Playable> = this.queue) =
        PlayerSession(queue, 0, store, engine, autoAdvance, skipping)

    private fun PlayerSession.play() {
        engine.open(MediaRequest(current.videoPath, startMs = begin(emptyList())))
    }

    @Test
    fun `binge-watching plays the next episode when one ends, without it the queue is over`() {
        val binge = session()
        binge.play()
        binge.finishCurrent()
        assertEquals(1, binge.index)
        assertTrue(store.isWatched("/show/e1.mkv"))
        val single = session(autoAdvance = false)
        single.play()
        single.finishCurrent()
        assertEquals(0, single.index)
        assertTrue(single.finished)
    }

    @Test
    fun `repeat one plays the same video again, and the sleep timer's end of this video ends the queue`() {
        val s = session()
        s.play()
        s.repeat = PlaybackMath.Repeat.ONE
        engine.advance(10_000)
        s.finishCurrent()
        assertEquals(0, s.index)
        assertEquals("seek 0", engine.commands[engine.commands.size - 2])
        s.repeat = PlaybackMath.Repeat.OFF
        s.sleepAtEnd = true
        s.finishCurrent()
        assertTrue(s.finished)
        assertFalse(s.sleepAtEnd)
    }

    @Test
    fun `a video resumes where it was left, past a known intro, and keeps a resume point once it played long enough`() {
        store.saveResume("/show/e1.mkv", 20_000, 600_000)
        val withIntro = queue.mapIndexed { i, p -> if (i == 0) p.copy(intro = TimeRange(10_000, 60_000)) else p }
        val s = session(queue = withIntro)
        assertEquals(60_000, s.begin(emptyList()))
        engine.open(MediaRequest("/show/e1.mkv", startMs = 60_000))
        engine.advance(5_000)
        s.countPlayed(5_000, 1f)
        s.saveProgress()
        assertEquals(65_000L, store.resumeFor("/show/e1.mkv")?.positionMs)
        assertEquals(600_000L, store.resumeFor("/show/e1.mkv")?.durationMs)
        // A short look into an episode not begun is nothing to continue.
        s.next()
        engine.open(MediaRequest("/show/e2.mkv", startMs = s.begin(emptyList())))
        engine.advance(5_000)
        s.countPlayed(5_000, 1f)
        s.saveProgress()
        assertNull(store.resumeFor("/show/e2.mkv"))
    }

    @Test
    fun `intros are skipped and the credits end the video while skipping is on, never while it is off`() {
        val chapters = listOf(
            Chapter(0, 30_000, "Intro"),
            Chapter(30_000, 550_000, "Episode"),
            Chapter(550_000, 600_000, "Credits"),
        )
        val s = session()
        engine.open(MediaRequest(s.current.videoPath, startMs = 0))
        s.begin(chapters)
        engine.advance(1_000)
        assertEquals(30_000L, s.tick())
        engine.seekTo(560_000)
        assertEquals(600_000L, s.tick())
        val off = session(skipping = false)
        engine.open(MediaRequest(off.current.videoPath, startMs = 0))
        off.begin(chapters)
        engine.advance(1_000)
        assertNull(off.tick())
        // The credits still count towards watched.
        engine.seekTo(560_000)
        off.saveProgress()
        assertTrue(store.isWatched("/show/e1.mkv"))
    }

    @Test
    fun `previous restarts a video a few seconds in, else goes back, chapters and repeats step as the keys ask`() {
        val s = session()
        s.play()
        s.next()
        val chapters = listOf(Chapter(0, 90_000, "One"), Chapter(90_000, 600_000, "Two"))
        engine.open(MediaRequest(s.current.videoPath, startMs = s.begin(chapters)))
        engine.advance(10_000)
        s.previous()
        assertEquals(1, s.index)
        assertEquals("Two", s.jumpChapter(true))
        assertEquals(90_000L, engine.positionMs)
        assertEquals("Repeat from 1:30 — press again where it should end", s.markRepeat())
        engine.advance(5_000)
        assertEquals("Repeating 1:30 – 1:35", s.markRepeat())
        engine.seekTo(1_000)
        s.previous()
        assertEquals(0, s.index)
    }

    @Test
    fun `subtitles start in the household's language until a track is picked by hand`() {
        val s = session()
        s.play()
        s.tracksChanged(engine.tracks, "deu")
        assertEquals("t2", engine.tracks.first { it.kind == Track.Kind.TEXT && it.selected }.id)
        s.manualTrackPick = true
        engine.select(Track.Kind.TEXT, "t1")
        s.tracksChanged(engine.tracks, "deu")
        assertEquals("t1", engine.tracks.first { it.kind == Track.Kind.TEXT && it.selected }.id)
    }

    @Test
    fun `the sleep timer fades the sound over its last seconds and pauses when it runs out`() {
        val s = session()
        s.play()
        s.sleepIn(15, nowMs = 1_000)
        assertFalse(s.sleepTick(1_000 + 15 * 60_000 - 5_000))
        assertEquals(0.5f, s.sleepVolume)
        assertEquals(1, PlaybackMath.sleepMinutesLeft(s.sleepLeftMs))
        assertTrue(s.sleepTick(1_000 + 15 * 60_000))
        assertFalse(engine.isPlaying)
        assertNull(s.sleepAtMs)
        assertEquals(1f, s.sleepVolume)
        s.sleepAtEndOfVideo()
        assertTrue(s.sleepAtEnd)
        s.sleepOff()
        assertFalse(s.sleepAtEnd)
    }
}
