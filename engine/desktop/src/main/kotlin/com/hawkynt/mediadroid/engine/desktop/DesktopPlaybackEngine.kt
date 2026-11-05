package com.hawkynt.mediadroid.engine.desktop

import com.hawkynt.mediadroid.engine.MediaRequest
import com.hawkynt.mediadroid.engine.PlaybackEngine
import com.hawkynt.mediadroid.engine.PlaybackTuning
import com.hawkynt.mediadroid.engine.Track
import com.hawkynt.mediadroid.library.FsFile
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
    /** The file behind a network source's location (SourceFiles.resolve); null for a local path FFmpeg opens. */
    private val files: (String) -> FsFile? = { null },
    private val sinkFactory: () -> AudioSink = { JavaSoundSink() },
) : PlaybackEngine {

    private fun readerOf(location: String) = MediaReader(location, files(location))

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
    @Volatile private var pendingAudio: String? = null
    @Volatile private var soundFiles: List<String> = emptyList()
    @Volatile private var selectedSound: String? = null
    @Volatile private var baseMs = 0L
    @Volatile private var pendingSpeed: Float? = null
    @Volatile private var clockSpeed = 1f
    @Volatile private var hasSound = false
    @Volatile private var wallStartNs = 0L
    @Volatile private var wallPausedMs = 0L
    @Volatile private var shown: MediaReader.Picture? = null
    @Volatile private var pendingText: Int? = null
    @Volatile private var sidecars: List<Pair<Track, List<MediaReader.Cue>>> = emptyList()
    @Volatile private var selectedText: String? = null
    private val cues = java.util.concurrent.CopyOnWriteArrayList<MediaReader.Cue>()

    override var openedPath: String? = null
        private set

    override fun open(request: MediaRequest, autoPlay: Boolean) {
        stop()
        val opened = readerOf(request.path)
        reader = opened
        openedPath = request.path
        hasSound = opened.tracks.any { it.kind == Track.Kind.AUDIO }
        soundFiles = request.audio
        selectedSound = opened.tracks.firstOrNull { it.kind == Track.Kind.AUDIO && it.selected }?.id
        // Subtitle files beside the video, read whole now: they are small.
        sidecars = request.subtitles.mapIndexedNotNull { i, file ->
            runCatching {
                readerOf(file.path).use { r ->
                    r.selectText(r.tracks.first { it.kind == Track.Kind.TEXT }.id.toInt())
                    val read = generateSequence { r.read() }.filterIsInstance<MediaReader.Cue>().toList()
                    Track("sidecar:$i", Track.Kind.TEXT, file.language, file.label, source = i + 1) to read
                }
            }.getOrNull()
        }
        selectedText = null
        cues.clear()
        sink = sinkFactory().also { if (hasSound || soundFiles.isNotEmpty()) it.open(MediaReader.SAMPLE_RATE, 2) }
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
        clockSpeed = 1f
        // The speed carries over from the video before: the new reader's filter takes it in at once.
        pendingSpeed = speed.takeIf { it != 1f }
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
        // A sound file beside the video, when one is picked: read alongside, whichever of the two is behind.
        var soundSource: MediaReader? = null
        var pictureMs = Long.MIN_VALUE
        var soundMs = Long.MIN_VALUE
        val tempo = Tempo()
        try {
            while (running) {
                pendingSpeed?.let { value ->
                    // The clock starts again from here at the new rate, the sound held back by the filter dropped.
                    pendingSpeed = null
                    val at = positionMs
                    tempo.speed = value
                    if (pendingSeekMs == null) pendingSeekMs = at
                }
                pendingSeekMs?.let { target ->
                    pendingSeekMs = null
                    tempo.reset()
                    clockSpeed = tempo.speed
                    source.seek(target)
                    soundSource?.seek(target)
                    pictureMs = Long.MIN_VALUE
                    soundMs = Long.MIN_VALUE
                    pictures.clear()
                    if (selectedText?.startsWith("sidecar:") == false) cues.clear()
                    sink?.flush()
                    baseMs = target
                    wallStartNs = System.nanoTime()
                    wallPausedMs = 0
                    shown = null
                    ended = false
                }
                pendingText?.let { index ->
                    pendingText = null
                    source.selectText(index)
                    // A stream of the file itself: read again from here so its lines arrive; a file beside it
                    // brought all of its lines along when it was picked.
                    if (index >= 0) {
                        cues.clear()
                        pendingSeekMs = positionMs
                    }
                }
                pendingAudio?.let { id ->
                    pendingAudio = null
                    val at = positionMs
                    soundSource?.close()
                    soundSource = null
                    val file = id.removePrefix(SOUND_FILE).toIntOrNull()?.takeIf { id.startsWith(SOUND_FILE) }
                    if (file != null) {
                        source.selectAudio(-1)
                        soundSource = runCatching { readerOf(soundFiles[file]) }.getOrNull()
                    } else {
                        source.selectAudio(id.toInt())
                    }
                    hasSound = soundSource != null || file == null
                    selectedSound = id
                    listeners.forEach { it.onTracksChanged(tracks) }
                    pendingSeekMs = at
                }
                if (!playing && pictures.remainingCapacity() == 0) {
                    Thread.sleep(5)
                    continue
                }
                val sound = soundSource
                val item = if (sound != null && soundMs <= pictureMs) {
                    // Only its sound: a cover picture in the file (an MP3's art) is no part of the video.
                    generateSequence { sound.read() }.firstOrNull { it is MediaReader.Sound }
                        ?.also { soundMs = (it as MediaReader.Sound).ptsMs }
                        ?: source.read().also { soundMs = Long.MAX_VALUE }
                } else {
                    source.read()
                }
                when (item) {
                    null -> {
                        if (!ended) {
                            ended = true
                            waitForEnd()
                        }
                        Thread.sleep(10)
                    }
                    is MediaReader.Picture -> {
                        pictureMs = item.ptsMs
                        while (running && pendingSeekMs == null) {
                            if (pictures.offer(item, 10, TimeUnit.MILLISECONDS)) break
                        }
                    }
                    is MediaReader.Sound -> {
                        val pcm = tempo.process(item.pcm)
                        if (pcm.isNotEmpty()) sink?.write(pcm)
                    }
                    is MediaReader.Cue -> cues += item
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            soundSource?.close()
            tempo.close()
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
            return baseMs + (played * clockSpeed).toLong()
        }

    override val durationMs: Long get() = reader?.durationMs ?: 0

    /** The shape of the picture's pixels (width over height), for drawing it in its true proportions. */
    val pixelAspect: Float get() = reader?.pixelAspect ?: 1f

    override val isPlaying: Boolean get() = playing

    /** How fast it plays, the sound at its own pitch (Tempo); taken in by the reader at its next turn. */
    override var speed: Float = 1f
        set(value) {
            if (value == field || value <= 0f) return
            field = value
            pendingSpeed = value
        }

    override var volume: Float = 1f
        set(value) {
            field = value
            sink?.volume = value
        }

    override val tracks: List<Track>
        get() {
            val own = reader?.tracks.orEmpty()
            val files = soundFiles.mapIndexed { i, path ->
                Track("$SOUND_FILE$i", Track.Kind.AUDIO, null, java.io.File(path).name, source = i + 1)
            }
            return (own + files + sidecars.map { it.first }).map {
                when (it.kind) {
                    Track.Kind.TEXT -> it.copy(selected = it.id == selectedText)
                    Track.Kind.AUDIO -> it.copy(selected = it.id == selectedSound)
                    Track.Kind.VIDEO -> it
                }
            }
        }

    override fun select(kind: Track.Kind, id: String?) {
        when (kind) {
            Track.Kind.AUDIO -> if (id != null && id != selectedSound) pendingAudio = id
            Track.Kind.TEXT -> {
                selectedText = id
                val sidecar = sidecars.firstOrNull { it.first.id == id }
                if (sidecar != null) {
                    pendingText = -1
                    cues.clear()
                    cues.addAll(sidecar.second)
                } else {
                    cues.clear()
                    pendingText = id?.toInt() ?: -1
                }
                listeners.forEach { it.onTracksChanged(tracks) }
            }
            Track.Kind.VIDEO -> Unit
        }
    }

    /** The subtitle line due by the clock, while subtitles are on and a subtitle track is picked; else null. */
    fun subtitle(): String? {
        if (!subtitlesOn || selectedText == null) return null
        val now = positionMs
        return cues.firstOrNull { now >= it.startMs && now < it.endMs }?.text
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

        /** The id prefix of a sound file beside the video: `sound:0` the first of [MediaRequest.audio]. */
        const val SOUND_FILE = "sound:"
    }
}
