package com.hawkynt.mediadroyd.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hawkynt.mediadroyd.engine.MediaRequest
import com.hawkynt.mediadroyd.engine.PlaybackEngine
import com.hawkynt.mediadroyd.engine.Track
import com.hawkynt.mediadroyd.library.FolderDefaults
import com.hawkynt.mediadroyd.library.Sidecars
import com.hawkynt.mediadroyd.player.Histogram
import com.hawkynt.mediadroyd.player.PictureFit
import com.hawkynt.mediadroyd.player.PlaybackMath
import com.hawkynt.mediadroyd.player.PlaybackStatistics
import com.hawkynt.mediadroyd.state.StateStore
import kotlin.math.round

/**
 * What the player screen keeps while it is open, the same on every platform: the [session] over the queue, the
 * current video's picture, its folder's and its subtitles' look, the speed and size chosen, which panel or dialog is
 * open, the tracks and the files beside the video, the statistics and the histogram, the last confirmation. The
 * platform's player (PlayerPlatform) reads it to draw the picture; [PlayerScreen] draws the rest from it.
 */
class PlayerScreenState(
    queue: List<Playable>,
    startIndex: Int,
    val store: StateStore,
    val engine: PlaybackEngine,
    autoAdvance: Boolean,
    /** The demo library's placeholders: nothing remembered of them. */
    val demo: Boolean = false,
    panelAtStart: Boolean = false,
    infoAtStart: Boolean = false,
) {
    private val settings get() = store.data.settings

    /** The queue, where each video starts and what comes after it (PlayerSession). */
    val session = PlayerSession(queue, startIndex, store, engine, autoAdvance, skipping = settings.skipIntroCredits)

    private val video get() = session.current.videoPath

    /** The current video's picture: its own if one was saved, else its folder's, else the household's. */
    var picture by mutableStateOf(store.pictureFor(video))

    /** The folder or share whose picture the video takes, if any has one. */
    var pictureFolder by mutableStateOf(store.pictureFolderOf(video))

    /** How the video's subtitles look: delay, size, raised, a box behind them — its own, else the household's. */
    var subtitleLook by mutableStateOf(store.subtitleLookFor(video))

    /** The speed chosen, before Even frames' small change. */
    var speed by mutableStateOf(settings.defaultSpeed)

    /** How the picture fills the screen, for this player only. */
    var fit by mutableStateOf(PictureFit.FIT)

    /** Whether subtitles show (the phone's switch; picked by track where PlayerPlatform.subtitlesByTrack). */
    var subtitlesOn by mutableStateOf(settings.subtitlesOn)

    /** Where the video stands, as last looked (twice a second): for the chapter it is in. */
    var positionMs by mutableLongStateOf(0L)

    /** Whether it plays now, for what shows only while paused. */
    var playing by mutableStateOf(false)

    /** The video's tracks as the engine last told them. */
    var tracks by mutableStateOf(emptyList<Track>())

    /** The current video's subtitle and sound files, with their locations, in the order they are merged in. */
    var sidecars by mutableStateOf(emptyList<Pair<Sidecars.Sidecar, String>>())

    /** Why the video cannot play, if it cannot. */
    var error by mutableStateOf<String?>(null)

    var panelOpen by mutableStateOf(panelAtStart)
    var infoOpen by mutableStateOf(infoAtStart)
    var chapterPicker by mutableStateOf(false)
    var trackPicker by mutableStateOf<TrackPicker?>(null)

    /** The statistics over the picture (What is playing › Advanced), for this player only. */
    var statisticsShown by mutableStateOf(false)
    val statistics = PlaybackStatistics()

    /** The histogram of the picture on screen, off until switched on; its last count. */
    var histogramShown by mutableStateOf(false)
    var histogram by mutableStateOf<Histogram?>(null)

    /** A short confirmation over the picture ("+15 s", a chapter's name); [feedbackBoosted] past a maximum. */
    var feedback by mutableStateOf<String?>(null)
        private set
    var feedbackBoosted by mutableStateOf(false)
        private set
    var feedbackStamp by mutableIntStateOf(0)
        private set

    /** The level a volume or brightness swipe set (0 to 2, 1 the device's maximum) and its side; null otherwise. */
    var level by mutableStateOf<Float?>(null)
        private set
    var levelSide by mutableStateOf(Side.RIGHT)
        private set

    fun show(text: String, boosted: Boolean = false) {
        feedback = text
        feedbackBoosted = boosted
        level = null
        feedbackStamp++
    }

    /** A swipe on [side] set [value] (PlaybackMath.boostedLevel): [text] in the middle, its level bar on that side. */
    fun showLevel(text: String, value: Float, side: Side) {
        show(text, PlaybackMath.isBoosted(value))
        level = value
        levelSide = side
    }

    /** The confirmation's time is over. */
    fun hideFeedback() {
        feedback = null
        level = null
    }

    /** Another video in the queue: its own picture and subtitles' look; remembered as the one played last. */
    fun videoChanged() {
        picture = store.pictureFor(video)
        pictureFolder = store.pictureFolderOf(video)
        subtitleLook = store.subtitleLookFor(video)
        error = null
        if (!demo && store.data.lastPlayed != video) {
            store.data.lastPlayed = video
            store.save()
        }
    }

    /** The picture as it stands now kept as the video's own (a slider let go). */
    fun keepPicture() = store.savePicture(video, picture)

    /** The video's own picture forgotten: it takes its folder's or the household's again. */
    fun resetPicture() {
        store.savePicture(video, null)
        picture = store.pictureFor(video)
    }

    /** The picture kept for the video's whole folder; the video follows it, its 3D eye and shape kept as its own. */
    fun useForFolder() {
        store.saveFolderPicture(store.folderOf(video), picture)
        store.savePicture(video, picture)
        picture = store.pictureFor(video)
        pictureFolder = store.pictureFolderOf(video)
    }

    /** The folder's picture forgotten: the video takes the household's (or its own, if it has one). */
    fun forgetFolder() {
        pictureFolder?.let { store.saveFolderPicture(it, null) }
        picture = store.pictureFor(video)
        pictureFolder = store.pictureFolderOf(video)
    }

    /** The folder the video is in, and the one whose picture it takes, named as the gear shows them. */
    val folderLabel: String get() = FolderDefaults.label(store.folderOf(video), store.sources)
    val pictureFolderLabel: String? get() = pictureFolder?.let { FolderDefaults.label(it, store.sources) }

    /** The video's subtitles' look changed, kept as its own; or forgotten, the household's again. */
    fun look(look: com.hawkynt.mediadroyd.player.SubtitleLook) {
        subtitleLook = look
        store.saveSubtitleLook(video, look)
    }

    fun resetLook() {
        store.saveSubtitleLook(video, null)
        subtitleLook = store.subtitleLookFor(video)
    }

    /**
     * The household's subtitle language a video starts in: by track ([byTrack], the desktop) none while subtitles are
     * off, there being no other switch; on the phone the language stays and its own switch hides them.
     */
    fun householdSubtitleLanguage(byTrack: Boolean): String =
        if (byTrack && !settings.subtitlesOn) "" else settings.subtitleLanguage

    /**
     * A track picked by hand. Subtitles by track ([byTrack]): the one picked is the household's language from now on,
     * none switches them off. On the phone: picking one shows subtitles, the household's language stays.
     */
    fun pick(kind: Track.Kind, id: String?, byTrack: Boolean) {
        session.manualTrackPick = true
        if (kind == Track.Kind.TEXT && !byTrack) {
            subtitlesOn = id != null || subtitlesOn
            engine.subtitlesOn = true
        }
        engine.select(kind, id)
        if (kind == Track.Kind.TEXT && byTrack) {
            val language = engine.tracks.firstOrNull { it.id == id }?.language.orEmpty()
            settings.subtitlesOn = language.isNotEmpty()
            if (language.isNotEmpty()) settings.subtitleLanguage = language
            store.save()
        }
    }

    /** The speed a step up ([steps] > 0) or down leaves: a twentieth each, within the slider's range. */
    fun steppedSpeed(steps: Int): Float =
        (round((speed + steps * SPEED_STEP) * 20f) / 20f).coerceIn(PlaybackMath.SPEED_MIN, PlaybackMath.SPEED_MAX)

    companion object {
        /** How much [ and ] change the speed. */
        const val SPEED_STEP = 0.05f

        /**
         * What to open for [video]: from [startMs], with the subtitle and sound files beside it ([sidecars], each
         * with its location), starting in the household's [audioLanguage] where it has a sound in it.
         */
        fun request(
            video: String,
            sidecars: List<Pair<Sidecars.Sidecar, String>>,
            startMs: Long,
            audioLanguage: String,
        ): MediaRequest {
            val (subtitles, audio) = sidecars.partition { (sidecar, _) -> sidecar.kind == Sidecars.Kind.SUBTITLES }
            return MediaRequest(
                path = video,
                subtitles = subtitles.map { (sidecar, path) ->
                    MediaRequest.SubtitleFile(
                        path, sidecar.mimeType, sidecar.language, sidecar.describe(), sidecar.forced,
                    )
                },
                audio = audio.map { it.second },
                startMs = startMs,
                audioLanguage = audioLanguage,
            )
        }
    }
}

/** Which track picker is open over the player. */
enum class TrackPicker { SOUND, SUBTITLES, SECOND }
