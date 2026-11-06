package com.hawkynt.mediadroid.engine.desktop

import com.hawkynt.mediadroid.engine.MediaRequest
import com.hawkynt.mediadroid.engine.PlaybackEngine
import com.hawkynt.mediadroid.engine.PlaybackTuning
import com.hawkynt.mediadroid.engine.Track
import com.hawkynt.mediadroid.library.FsFile
import com.hawkynt.mediadroid.player.AssPicture
import com.hawkynt.mediadroid.player.AssTrack
import com.hawkynt.mediadroid.player.FileLoudness
import com.hawkynt.mediadroid.player.LibAss
import com.hawkynt.mediadroid.player.LoudnessChain
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingDeque

/**
 * The desktop playback engine: a [MediaReader] on a thread of its own decodes ahead — the sound into the
 * [AudioSink], which paces it, the pictures into a short queue — and the clock is the sound's: the time the reader
 * started from plus what the sink has played (wall time where the file has no sound). [picture] hands out the latest
 * picture due by that clock, for the window to draw at its next refresh, and [moment] that picture with the ones
 * around it for the stage. With [film] on, the pictures go through Film on their way to the queue.
 * ASS/SSA subtitles — the file's own streams and files beside it — are drawn by libass where the native library
 * carries it ([subtitlePicture]: styles, positions, karaoke, signs, the file's attached fonts); everything else, and
 * ASS without it, as lines of text ([subtitle]).
 */
class DesktopPlaybackEngine(
    override val tuning: PlaybackTuning,
    /** The file behind a network source's location (SourceFiles.resolve); null for a local path FFmpeg opens. */
    private val files: (String) -> FsFile? = { null },
    private val sinkFactory: () -> AudioSink = { JavaSoundSink() },
) : PlaybackEngine {

    private fun readerOf(location: String) = MediaReader(location, files(location))

    private val listeners = CopyOnWriteArrayList<PlaybackEngine.Listener>()
    // As many as picturesAhead allows: a few, more while the sound is set sooner than the picture.
    private val pictures = LinkedBlockingDeque<MediaReader.Picture>()
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
    @Volatile private var pictureGapMs = 40L
    @Volatile private var pictureBytes = 1
    @Volatile private var wallStartNs = 0L
    @Volatile private var wallPausedMs = 0L
    @Volatile private var shown: MediaReader.Picture? = null
    @Volatile private var shownBefore: MediaReader.Picture? = null
    @Volatile private var filmChanged = false
    @Volatile private var pendingText: Int? = null
    @Volatile private var sidecars: List<Sidecar> = emptyList()
    @Volatile private var selectedText: String? = null
    @Volatile private var fonts: List<Pair<String, ByteArray>> = emptyList()
    @Volatile private var ass: AssTrack? = null
    @Volatile private var assShown = false
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
                    val index = r.tracks.first { it.kind == Track.Kind.TEXT }.id.toInt()
                    r.selectText(index)
                    val read = generateSequence { r.read() }.toList()
                    Sidecar(
                        Track("sidecar:$i", Track.Kind.TEXT, file.language, file.label, source = i + 1),
                        read.filterIsInstance<MediaReader.Cue>(),
                        r.assHeader(index),
                        read.filterIsInstance<MediaReader.AssEvent>(),
                    )
                }
            }.getOrNull()
        }
        selectedText = null
        // The fonts the file carries, for its ASS subtitles and those beside it.
        fonts = if (libass) opened.fonts else emptyList()
        cues.clear()
        sink = sinkFactory().also { if (hasSound || soundFiles.isNotEmpty()) it.open(MediaReader.SAMPLE_RATE, 2) }
        sink?.volume = volume
        baseMs = 0
        ended = false
        shown = null
        shownBefore = null
        filmChanged = true
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
        val pulldown = Film()
        // The household's sound settings, on the sound as it goes to the output (after Tempo).
        val chain = LoudnessChain(MediaReader.SAMPLE_RATE, 2)
        var loudness = fileLoudness(source, null)
        var samples = ShortArray(0)
        try {
            while (running) {
                if (filmChanged) {
                    filmChanged = false
                    pulldown.reset()
                }
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
                    pulldown.reset()
                    if (selectedText?.startsWith("sidecar:") == false) cues.clear()
                    sink?.flush()
                    baseMs = target
                    wallStartNs = System.nanoTime()
                    wallPausedMs = 0
                    shown = null
                    shownBefore = null
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
                    loudness = fileLoudness(source, soundSource)
                    listeners.forEach { it.onTracksChanged(tracks) }
                    pendingSeekMs = at
                }
                if (!playing && pictures.size >= picturesAhead(pictureBytes)) {
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
                            // With the sound set sooner than the picture, the last pictures are due after the
                            // sound's end: silence keeps its clock going until then.
                            val sooner = -soundDelayMs()
                            if (sooner > 0) sink?.write(ByteArray(sooner * MediaReader.SAMPLE_RATE / 1000 * 4))
                            waitForEnd()
                        }
                        Thread.sleep(10)
                    }
                    is MediaReader.Picture -> {
                        if (item.ptsMs > pictureMs && pictureMs != Long.MIN_VALUE) pictureGapMs = item.ptsMs - pictureMs
                        pictureMs = item.ptsMs
                        pictureBytes = item.rgba.size
                        for (picture in if (film) pulldown.add(item) else listOf(item)) {
                            while (running && pendingSeekMs == null) {
                                if (pictures.size < picturesAhead(picture.rgba.size)) {
                                    pictures.offerLast(picture)
                                    break
                                }
                                Thread.sleep(5)
                            }
                        }
                    }
                    is MediaReader.Sound -> {
                        val pcm = tempo.process(item.pcm)
                        if (pcm.isNotEmpty()) {
                            val t = tuning
                            if (t.loudnessDb != 0f || t.nightMode || t.normalizeLoudness) {
                                val count = pcm.size / 2
                                if (samples.size < count) samples = ShortArray(count)
                                val view = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                view.get(samples, 0, count)
                                chain.process(
                                    samples, 0, count / 2, t.loudnessDb, t.nightMode, t.normalizeLoudness, loudness,
                                )
                                view.clear()
                                view.put(samples, 0, count)
                            }
                            sink?.write(pcm)
                        }
                    }
                    is MediaReader.Cue -> cues += item
                    is MediaReader.AssEvent -> ass?.event(item.data.toByteArray(), item.startMs, item.durationMs)
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            soundSource?.close()
            tempo.close()
        }
    }

    /** Which file and sound track play, and its ReplayGain, for even loudness. */
    private fun fileLoudness(source: MediaReader, soundFile: MediaReader?): FileLoudness =
        FileLoudness("$openedPath#$selectedSound", runCatching { (soundFile ?: source).replayGain?.gainDb }.getOrNull())

    /** The lip-sync delay (positive: the sound later), where there is sound to delay. */
    private fun soundDelayMs(): Int = if (hasSound) tuning.audioDelayMs else 0

    /**
     * How many pictures may wait to be shown: a few, and while the sound is set sooner than the picture as many more
     * as that delay covers — within [PICTURE_BYTES_AHEAD], so a large picture cannot fill the memory.
     */
    private fun picturesAhead(bytes: Int): Int {
        val sooner = (-soundDelayMs()).coerceAtLeast(0)
        if (sooner == 0) return PICTURES_AHEAD
        val more = (sooner + pictureGapMs - 1) / pictureGapMs
        val fit = PICTURE_BYTES_AHEAD / bytes.coerceAtLeast(1)
        return maxOf(PICTURES_AHEAD.toLong(), minOf(PICTURES_AHEAD + more, fit)).toInt()
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

    /**
     * The picture due now by the clock (the latest one not later than it), or null before the first. Paused after a
     * seek, the first picture from there shows at once though the clock stands just short of it — a frame step's.
     * The audio delay moves the clock the pictures follow against the sound.
     */
    fun picture(): MediaReader.Picture? = moment()?.shown

    /**
     * The moment due now by the clock: the latest picture not later than it (paused after a seek, the first from
     * there), the one shown before it, the two after it decoded already, and how far the clock is between it and the
     * next; null before the first.
     */
    @Synchronized
    fun moment(): Moment? {
        val now = positionMs + soundDelayMs()
        while (true) {
            val next = pictures.peekFirst() ?: break
            if (next.ptsMs > now) break
            val taken = pictures.pollFirst() ?: break
            if (shown != null) shownBefore = shown
            shown = taken
        }
        if (shown == null && !playing && pendingSeekMs == null) shown = pictures.pollFirst()
        val current = shown ?: return null
        val ahead = pictures.iterator()
        val next = if (ahead.hasNext()) ahead.next() else null
        val after = if (ahead.hasNext()) ahead.next() else null
        return Moment(current, shownBefore, next, after, Moment.phase(current, next, now))
    }

    /**
     * Film (3:2 pulldown undone, see Film) on the pictures decoded from now on; those queued already stay as they
     * are.
     */
    @Volatile var film: Boolean = false
        set(value) {
            if (value == field) return
            field = value
            filmChanged = true
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

    override val frameRate: Float get() = reader?.frameRate ?: 0f

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
            return (own + files + sidecars.map { it.track }).map {
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
                ass?.close()
                ass = null
                val sidecar = sidecars.firstOrNull { it.track.id == id }
                if (sidecar != null) {
                    pendingText = -1
                    cues.clear()
                    cues.addAll(sidecar.cues)
                    ass = sidecar.assHeader?.let { header ->
                        assTrack(header)?.apply {
                            sidecar.assEvents.forEach { event(it.data.toByteArray(), it.startMs, it.durationMs) }
                        }
                    }
                } else {
                    cues.clear()
                    pendingText = id?.toInt() ?: -1
                    ass = id?.toInt()?.let { reader?.assHeader(it) }?.let(::assTrack)
                    ass?.let { track -> prefetch(track, id!!.toInt()) }
                }
                listeners.forEach { it.onTracksChanged(tracks) }
            }
            Track.Kind.VIDEO -> Unit
        }
    }

    /** A libass track with [header] and the file's fonts, the system's own behind them; null without libass. */
    private fun assTrack(header: ByteArray): AssTrack? {
        if (!libass) return null
        val track = AssTrack()
        if (!track.open) return null
        fonts.forEach { (name, data) -> track.addFont(name, data) }
        track.defaultFont(path = defaultFont.orEmpty(), system = true)
        track.header(header)
        return track
    }

    /**
     * A local file's ASS stream [index] read through once on a thread of its own, every event into [track]: a seek
     * lands after events that began before it (and a stream picked midway would miss them), which libass then has
     * anyway. A network source's file is not read twice over; there the events come as it plays.
     */
    private fun prefetch(track: AssTrack, index: Int) {
        val path = openedPath ?: return
        if (files(path) != null) return
        Thread({
            runCatching {
                MediaReader(path).use { r ->
                    r.forEachAssEvent(index, { ass !== track }) {
                        track.event(it.data.toByteArray(), it.startMs, it.durationMs)
                    }
                }
            }
        }, "mediadroid-subtitles").apply {
            isDaemon = true
            start()
        }
    }

    /** Whether the picked subtitles are drawn by libass ([subtitlePicture]) rather than shown as text. */
    val drawsSubtitles: Boolean get() = ass != null && subtitlesOn

    /**
     * The picked ASS subtitles due with the picture (less the subtitle delay) drawn for a picture of [width] ×
     * [height] — the size it is shown at, for sharp text; null where nothing changed since the last call,
     * [AssPicture.EMPTY] once they are switched off.
     */
    fun subtitlePicture(width: Int, height: Int): AssPicture? {
        val track = ass
        if (track == null || !subtitlesOn) {
            if (!assShown) return null
            assShown = false
            return AssPicture.EMPTY
        }
        assShown = true
        val opened = reader
        val atMs = positionMs + soundDelayMs() - tuning.subtitleDelayMs
        return track.render(atMs, width, height, opened?.width ?: 0, opened?.height ?: 0)
    }

    /** The subtitle line due with the picture, while subtitles are on and a subtitle track is picked; else null. */
    fun subtitle(): String? {
        if (!subtitlesOn || selectedText == null || ass != null) return null
        val now = positionMs + soundDelayMs()
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
        ass?.close()
        ass = null
        playing = false
        pictures.clear()
        shown = null
        shownBefore = null
    }

    override fun close() = stop()

    /** A subtitle file beside the video, read whole: its lines, and for ASS its header and events for libass. */
    private class Sidecar(
        val track: Track,
        val cues: List<MediaReader.Cue>,
        val assHeader: ByteArray?,
        val assEvents: List<MediaReader.AssEvent>,
    )

    /** Whether libass is there: the native library built for this system, loaded, carrying it. */
    private val libass: Boolean by lazy { NativeNetworks.loaded && LibAss.available }

    private companion object {
        /**
         * The font for what neither the file's fonts nor the system's provider draw (a Linux built without Fontconfig):
         * the first of the usual sans faces this machine has.
         */
        val defaultFont: String? by lazy {
            listOf(
                "C:\\Windows\\Fonts\\arial.ttf",
                "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                "/usr/share/fonts/TTF/DejaVuSans.ttf",
                "/usr/share/fonts/dejavu-sans-fonts/DejaVuSans.ttf",
                "/usr/share/fonts/noto/NotoSans-Regular.ttf",
                "/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf",
                "/usr/share/fonts/liberation/LiberationSans-Regular.ttf",
                "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
            ).firstOrNull { java.io.File(it).isFile }
        }

        /** Pictures decoded ahead of the clock: a fifth of a second at 25 fps. */
        const val PICTURES_AHEAD = 6

        /** How much memory the pictures held back for a sound set sooner may take. */
        const val PICTURE_BYTES_AHEAD = 512L * 1024 * 1024

        /** The id prefix of a sound file beside the video: `sound:0` the first of [MediaRequest.audio]. */
        const val SOUND_FILE = "sound:"
    }
}
