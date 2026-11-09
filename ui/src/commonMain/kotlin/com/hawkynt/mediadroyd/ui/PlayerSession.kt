package com.hawkynt.mediadroyd.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hawkynt.mediadroyd.engine.PlaybackEngine
import com.hawkynt.mediadroyd.engine.Track
import com.hawkynt.mediadroyd.engine.Tracks
import com.hawkynt.mediadroyd.library.Chapter
import com.hawkynt.mediadroyd.library.SkipRules
import com.hawkynt.mediadroyd.player.AbLoop
import com.hawkynt.mediadroyd.player.ChapterSteps
import com.hawkynt.mediadroyd.player.FrameStep
import com.hawkynt.mediadroyd.player.PlaybackMath
import com.hawkynt.mediadroyd.state.StateStore

/**
 * What a player does with its queue on every platform, over the [engine] that plays it and the household's [store]:
 * which video plays ([index]), where it starts (resumed, never back into its intro), how far it got (a resume point
 * once it has played long enough, watched once only its tail or credits are left), what comes after it (the next
 * episode with binge-watching on, [repeat], the sleep timer's "end of this video"), intros skipped and credits ending
 * it while [skipping] is on, the part repeated between two marks, the chapters and frame steps, and the subtitle
 * language a video starts in until a track is picked by hand. The screens around it draw; this decides.
 */
class PlayerSession(
    val queue: List<Playable>,
    startIndex: Int,
    private val store: StateStore,
    private val engine: PlaybackEngine,
    /** The next video starts when one ends (Settings → binge-watching). */
    private val autoAdvance: Boolean,
    /** Intros jumped over and the credits ending a video (Settings → skip intros and credits). */
    val skipping: Boolean,
) {
    /** The video playing, as its place in [queue]. */
    var index by mutableIntStateOf(startIndex)
        private set

    /** The video playing. */
    val current: Playable get() = queue[index]

    /** Whether there is a video after this one. */
    val hasNext: Boolean get() = index + 1 < queue.size

    /** The queue is over: nothing plays after the last video, or the sleep timer ended it. */
    var finished by mutableStateOf(false)
        private set

    /** What plays again once a video ends. */
    var repeat by mutableStateOf(PlaybackMath.Repeat.OFF)

    /** The part repeated between two marks (L, "Repeat a part"). */
    var abLoop by mutableStateOf(AbLoop.OFF)
        private set

    /** The file's own chapters, once read. */
    var chapters by mutableStateOf(emptyList<Chapter>())
        private set

    /** The intro jumped over and where the credits start, for the video playing; null where none is known. */
    var introSpanMs: LongRange? = null
        private set
    var creditsStartMs: Long? = null
        private set

    /** The sleep timer stops after this video: the queue ends with it. */
    var sleepAtEnd by mutableStateOf(false)

    /** When the sleep timer runs out, on the screen's own clock (see [sleepIn]); null while it is off. */
    var sleepAtMs by mutableStateOf<Long?>(null)
        private set

    /** The minutes the sleep timer was set to, and how long it has left as last [counted][sleepTick]. */
    var sleepMinutes by mutableStateOf<Int?>(null)
        private set
    var sleepLeftMs by mutableLongStateOf(0L)
        private set

    /** The sleep timer set to [minutes] from [nowMs] (a monotonic clock in milliseconds); the video's end forgotten. */
    fun sleepIn(minutes: Int, nowMs: Long) {
        sleepAtEnd = false
        sleepMinutes = minutes
        sleepLeftMs = minutes * 60_000L
        sleepAtMs = nowMs + minutes * 60_000L
    }

    /** The sleep timer stops at the end of this video instead ([finishCurrent]). */
    fun sleepAtEndOfVideo() {
        sleepAtMs = null
        sleepAtEnd = true
    }

    /** No sleep timer. */
    fun sleepOff() {
        sleepAtMs = null
        sleepAtEnd = false
    }

    /**
     * The sleep timer at [nowMs]: how loud the sound plays now (PlaybackMath.sleepFade: fading over its last seconds,
     * 1 without a timer); run out, the video pauses and the timer is off. Returns true when it just ran out.
     */
    fun sleepTick(nowMs: Long): Boolean {
        val at = sleepAtMs ?: return false
        val left = at - nowMs
        sleepLeftMs = left
        if (left > 0) return false
        engine.pause()
        sleepAtMs = null
        return true
    }

    /** How loud the sound plays under the sleep timer: fading over its last seconds, else 1. */
    val sleepVolume: Float get() = if (sleepAtMs == null) 1f else PlaybackMath.sleepFade(sleepLeftMs)

    /** A track picked by hand: the household's subtitle language steps back for this video. */
    var manualTrackPick by mutableStateOf(false)

    /**
     * The time the video has actually played this time — not where it stands — for whether it is worth a resume point
     * at all (StateStore.saveResume): counted by [countPlayed], from nothing for each video.
     */
    var playedMs by mutableLongStateOf(0L)
        private set

    private var ticks = 0

    /**
     * A new video begins with its [chapters] read: its intro and credits found (the chapters first, the NFO's times
     * after, SkipRules), the repeated part and the played time forgotten. Returns where it starts: its resume point,
     * past a known intro.
     */
    fun begin(chapters: List<Chapter>): Long {
        manualTrackPick = false
        abLoop = AbLoop.OFF
        playedMs = 0L
        this.chapters = chapters
        applySkips()
        val resume = store.resumeFor(current.videoPath)?.positionMs ?: 0
        return SkipRules.skipTarget(resume, introSpanMs) ?: resume
    }

    /**
     * The intro and credits of the video playing: from its chapters or the NFO's times (an edit decision list), else
     * what looking at its sound found (IntroCredits, StateStore.foundSkipFor). Called again once that has been looked
     * at while the video plays: an intro not reached yet is still skipped.
     */
    fun applySkips() {
        val found = store.foundSkipFor(current.videoPath)
        introSpanMs = if (skipping) SkipRules.introSpanMs(chapters, current.intro ?: found?.intro) else null
        creditsStartMs = SkipRules.creditsStartMs(chapters, current.credits) ?: found?.creditsStartMs
    }

    /** [elapsedMs] of the clock went by at [speed]: counted as played while the engine plays. */
    fun countPlayed(elapsedMs: Long, speed: Float) {
        if (engine.isPlaying) playedMs += (elapsedMs * speed).toLong()
    }

    /** Remembers how far the video playing got: watched once only its tail or credits are left. */
    fun saveProgress(position: Long = engine.positionMs, duration: Long = engine.durationMs) {
        if (duration <= 0 || position <= 0) return
        val path = current.videoPath
        // Right after Next or Previous the engine still holds the last file: its position is not ours.
        if (engine.openedPath != path) return
        if (PlaybackMath.isWatched(position, duration, creditsStartMs)) {
            store.markWatched(path)
        } else {
            store.saveResume(path, position, duration, playedMs)
        }
    }

    /** The next video, its predecessor's progress kept; nothing after the last. */
    fun next() {
        if (!hasNext) return
        saveProgress()
        index++
    }

    /** The video before; a few seconds in, or on the first, this one from its start again. */
    fun previous() {
        when (val previous = PlaybackMath.previous(engine.positionMs, index)) {
            PlaybackMath.Previous.Restart -> engine.seekTo(0)
            is PlaybackMath.Previous.GoTo -> {
                saveProgress()
                index = previous.index
            }
        }
    }

    /** Goes to the video at [to] in the queue, this one's progress kept. */
    fun goTo(to: Int) {
        if (to !in queue.indices || to == index) return
        saveProgress()
        index = to
    }

    /**
     * The video playing reached its end: watched, then what comes after it — the next one with binge-watching on or
     * [repeat] All, this one again with [repeat] One — or the queue is [finished] (also at the sleep timer's end).
     */
    fun finishCurrent() {
        store.markWatched(current.videoPath)
        if (sleepAtEnd) {
            sleepAtEnd = false
            finished = true
            return
        }
        when (val next = PlaybackMath.afterEnd(index, queue.size, autoAdvance, repeat)) {
            null -> finished = true
            index -> {
                // The same video again: the index does not change, so it starts over by hand.
                engine.seekTo(0)
                engine.play()
            }
            else -> index = next
        }
    }

    /**
     * Twice a second while it plays: inside a known intro it jumps to its end, once the credits have begun (with
     * skipping on) to the video's end, which ends it; every third second the progress is kept. A part repeating is
     * the viewer's choice: no jump takes it away. Returns where it jumped to, or null.
     */
    fun tick(): Long? {
        if (!engine.isPlaying) return null
        val position = engine.positionMs
        if (abLoop.a != null) {
            if (++ticks % 6 == 0) saveProgress(position)
            return null
        }
        SkipRules.skipTarget(position, introSpanMs)?.let {
            engine.seekTo(it)
            return it
        }
        val credits = creditsStartMs
        if (skipping && credits != null && position >= credits) {
            return engine.durationMs.takeIf { it > 0 }?.also { engine.seekTo(it) }
        }
        if (++ticks % 6 == 0) saveProgress(position)
        return null
    }

    /** Back to the repeated part's start once its end is reached; returns where it went, or null. */
    fun keepRepeating(): Long? {
        if (!abLoop.repeating || !engine.isPlaying) return null
        return abLoop.jumpFrom(engine.positionMs)?.also { engine.seekTo(it) }
    }

    /** The next or the previous chapter; returns the name of the one it went to, or null where there is none. */
    fun jumpChapter(forward: Boolean): String? {
        val position = engine.positionMs
        val target = (if (forward) ChapterSteps.next(chapters, position) else ChapterSteps.previous(chapters, position))
            ?: return null
        engine.seekTo(target)
        return ChapterSteps.current(chapters, target)?.let { ChapterSteps.name(chapters, it) }
    }

    /** One picture on or back, paused there (as mpv's frame steps pause). */
    fun stepFrame(forward: Boolean) {
        if (engine.isPlaying) engine.pause()
        engine.seekTo(FrameStep.target(engine.positionMs, engine.frameRate, forward, engine.durationMs))
    }

    /** Marks where the repeated part starts, then ends, then stops repeating; returns what it does now. */
    fun markRepeat(): String {
        abLoop = abLoop.mark(engine.positionMs)
        val a = abLoop.a
        val b = abLoop.b
        return when {
            a != null && b != null -> "Repeating ${clock(a)} – ${clock(b)}"
            a != null -> "Repeat from ${clock(a)} — press again where it should end"
            else -> "Repeat off"
        }
    }

    /** The video's tracks are known: its subtitles start in [language] (the household's), unless picked by hand. */
    fun tracksChanged(tracks: List<Track>, language: String) {
        if (manualTrackPick) return
        val wanted = Tracks.preferredText(tracks, language) ?: return
        if (!wanted.selected) engine.select(Track.Kind.TEXT, wanted.id)
    }
}
