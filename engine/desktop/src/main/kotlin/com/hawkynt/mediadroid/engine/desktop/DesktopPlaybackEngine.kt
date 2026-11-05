package com.hawkynt.mediadroid.engine.desktop

import com.hawkynt.mediadroid.engine.MediaRequest
import com.hawkynt.mediadroid.engine.PlaybackEngine
import com.hawkynt.mediadroid.engine.PlaybackTuning
import com.hawkynt.mediadroid.engine.Track
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * The desktop playback engine: a [MediaReader] on a thread of its own decodes ahead — the sound into the
 * [AudioSink], which paces it, the pictures into a short queue — and the clock is the sound's: the time the reader
 * started from plus what the sink has played (wall time where the file has no sound). [picture] hands out the latest
 * picture due by that clock, for the window to draw at its next refresh.
 */
class DesktopPlaybackEngine(
    override val tuning: PlaybackTuning,
    private val sinkFactory: () -> AudioSink = { JavaSoundSink() },
) : PlaybackEngine {

    private val listeners = CopyOnWriteArrayList<PlaybackEngine.Listener>()
    private val pictures = LinkedBlockingDeque<MediaReader.Picture>(PICTURES_AHEAD)
    private val lock = Object()

    @Volatile private var reader: MediaReader? = null
    @Volatile private var sink: AudioSink? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var playing = false
    @Volatile private var ended = false
    @Volatile private var pendingSeekMs: Long? = null
    @Volatile private var pendingAudio: Int? = null
    @Volatile private var baseMs = 0L
    @Volatile private var hasSound = false
    @Volatile private var wallStartNs = 0L
    @Volatile private var wallPausedMs = 0L
    @Volatile private var shown: MediaReader.Picture? = null

    override var openedPath: String? = null
        private set

    override fun open(request: MediaRequest, autoPlay: Boolean) {
        stop()
        val opened = MediaReader(request.path)
        reader = opened
        openedPath = request.path
        hasSound = opened.tracks.any { it.kind == Track.Kind.AUDIO }
        sink = sinkFactory().also { if (hasSound) it.open(MediaReader.SAMPLE_RATE, 2) }
        sink?.volume = volume
        baseMs = 0
        ended = false
        shown = null
        pictures.clear()
        if (request.startMs > 0) {
            opened.seek(request.startMs)
            baseMs = request.startMs
        }
        wallStartNs = System.nanoTime()
        wallPausedMs = 0
        running = true
        thread = Thread({ readLoop(opened) }, "mediadroid-reader").apply {
            isDaemon = true
            start()
        }
        listeners.forEach {
            it.onTracksChanged(tracks)
            it.onVideoSize(opened.width, opened.height, opened.pixelAspect)
        }
        if (autoPlay) play() else pause()
    }

    private fun readLoop(source: MediaReader) {
        try {
            while (running) {
                pendingSeekMs?.let { target ->
                    pendingSeekMs = null
                    source.seek(target)
                    pictures.clear()
                    sink?.flush()
                    baseMs = target
                    wallStartNs = System.nanoTime()
                    wallPausedMs = 0
                    shown = null
                    ended = false
                }
                pendingAudio?.let { index ->
                    pendingAudio = null
                    source.selectAudio(index)
                    pendingSeekMs = positionMs
                }
                if (!playing && pictures.remainingCapacity() == 0) {
                    Thread.sleep(5)
                    continue
                }
                when (val item = source.read()) {
                    null -> {
                        if (!ended) {
                            ended = true
                            waitForEnd()
                        }
                        Thread.sleep(10)
                    }
                    is MediaReader.Picture -> while (running && pendingSeekMs == null) {
                        if (pictures.offer(item, 10, TimeUnit.MILLISECONDS)) break
                    }
                    is MediaReader.Sound -> sink?.write(item.pcm)
                }
            }
        } catch (_: InterruptedException) {
        }
    }

    // Once the file is read to its end, the end is heard when the last picture is due.
    private fun waitForEnd() {
        Thread {
            while (running && ended && pendingSeekMs == null) {
                if (pictures.isEmpty() && (durationMs == 0L || positionMs >= durationMs - 50)) {
                    if (playing) {
                        playing = false
                        listeners.forEach { it.onPlayingChanged(false); it.onEnded() }
                    }
                    return@Thread
                }
                Thread.sleep(20)
            }
        }.apply { isDaemon = true }.start()
    }

    /** The picture due now by the clock (the latest one not later than it), or null before the first. */
    fun picture(): MediaReader.Picture? {
        val now = positionMs
        while (true) {
            val next = pictures.peekFirst() ?: break
            if (next.ptsMs > now) break
            shown = pictures.pollFirst()
        }
        return shown
    }

    override fun play() {
        if (reader == null || playing) return
        if (ended && pictures.isEmpty()) seekTo(0)
        playing = true
        sink?.resume()
        wallStartNs = System.nanoTime() - wallPausedMs * 1_000_000
        listeners.forEach { it.onPlayingChanged(true) }
    }

    override fun pause() {
        if (!playing) return
        playing = false
        sink?.pause()
        wallPausedMs = (System.nanoTime() - wallStartNs) / 1_000_000
        listeners.forEach { it.onPlayingChanged(false) }
    }

    override fun seekTo(positionMs: Long) {
        if (reader == null) return
        pendingSeekMs = positionMs.coerceIn(0, durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
    }

    override val positionMs: Long
        get() {
            pendingSeekMs?.let { return it }
            val played = if (hasSound) {
                sink?.playedMs() ?: 0
            } else if (playing) {
                (System.nanoTime() - wallStartNs) / 1_000_000
            } else {
                wallPausedMs
            }
            return baseMs + played
        }

    override val durationMs: Long get() = reader?.durationMs ?: 0

    /** The shape of the picture's pixels (width over height), for drawing it in its true proportions. */
    val pixelAspect: Float get() = reader?.pixelAspect ?: 1f

    override val isPlaying: Boolean get() = playing

    /** Speed other than 1 is not played yet on the desktop (#288): the value is kept. */
    override var speed: Float = 1f

    override var volume: Float = 1f
        set(value) {
            field = value
            sink?.volume = value
        }

    override val tracks: List<Track>
        get() = reader?.tracks.orEmpty()

    override fun select(kind: Track.Kind, id: String?) {
        if (kind == Track.Kind.AUDIO && id != null) pendingAudio = id.toInt()
    }

    override var subtitlesOn: Boolean = true

    override fun addListener(listener: PlaybackEngine.Listener) {
        listeners += listener
    }

    override fun removeListener(listener: PlaybackEngine.Listener) {
        listeners -= listener
    }

    private fun stop() {
        running = false
        thread?.interrupt()
        thread?.join(1_000)
        thread = null
        sink?.close()
        sink = null
        reader?.close()
        reader = null
        playing = false
        pictures.clear()
    }

    override fun close() = stop()

    private companion object {
        /** Pictures decoded ahead of the clock: a fifth of a second at 25 fps. */
        const val PICTURES_AHEAD = 6
    }
}
