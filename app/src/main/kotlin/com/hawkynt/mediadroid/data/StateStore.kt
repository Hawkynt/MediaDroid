package com.hawkynt.mediadroid.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.hawkynt.mediadroid.library.FileSort
import com.hawkynt.mediadroid.library.LibrarySection
import com.hawkynt.mediadroid.library.SortOrder
import com.hawkynt.mediadroid.library.Source
import com.hawkynt.mediadroid.library.SourceKind
import com.hawkynt.mediadroid.library.WatchMarks
import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.player.SubtitleLook
import com.hawkynt.mediadroid.ui.WallMode
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ResumeInfo(val positionMs: Long = 0, val durationMs: Long = 0)

/** A subtitle look stored for one video; see [SubtitleLook]. */
@Serializable
data class SubtitleOverride(
    val delayMs: Int = 0,
    val size: String = "NORMAL",
    val raised: Boolean = false,
    val box: Boolean = false,
)

/** A picture correction stored for one video; see [PictureAdjustment] for the ranges. */
@Serializable
data class PictureOverride(
    val brightness: Float = 0f,
    val gamma: Float = 1f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
)

/**
 * Parent-guarded playback and library preferences. Picture values are neutral
 * at 0/1: brightness is an overlay from -1..1, gamma/contrast/saturation are
 * multipliers around 1. Languages are ISO-639 codes or empty for "any".
 */
@Serializable
data class Settings(
    var defaultSpeed: Float = 1.0f,
    var loudness: Float = 0f,                 // dB-ish -10..10, mapped to volume
    var matchRefreshRate: Boolean = false,    // TV boxes: switch the display to a multiple of the film's frame rate
    var audioPassthrough: Boolean = false,    // TV boxes: compressed surround untouched to an AV receiver
    var judderLimitPercent: Int = 0,          // judder reduction: how far the speed may change, 0 = off
    var judderDirection: String = "NEAREST",  // PlaybackMath.JudderDirection name
    var askedForNotifications: Boolean = false, // the media notification's permission is asked once
    var brightness: Float = 0f,               // -1..1
    var gamma: Float = 1.0f,                  // 0.5..2
    var contrast: Float = 1.0f,               // 0.5..2
    var saturation: Float = 1.0f,             // 0..2
    var subtitlesOn: Boolean = true,
    var subtitleLanguage: String = "",
    var subtitleSize: String = "NORMAL",       // SubtitleLook.Size name, the household default
    var subtitleRaised: Boolean = false,
    var subtitleBox: Boolean = false,
    var audioLanguage: String = "",
    var maxAgeYears: Int = 0,                 // 0 = off, else hide content above
    var bingeWatching: Boolean = true,        // a finished episode starts the next one
    var skipIntroCredits: Boolean = true,     // jump over known intros, end at the credits
    var useCutout: Boolean = false,           // the player's picture may reach into the camera cutout

    var spatialAudio: Boolean = false,        // hand multichannel sound to the platform spatializer (Android 13+)
    var softwareVideoDecoding: Boolean = false, // FFmpeg before the device's decoders: last resort, drains the battery
)

@Serializable
data class AppData(
    var rootPath: String = "",                 // "" until a media folder is chosen
    val sources: MutableList<Source> = mutableListOf(), // empty: just rootPath, see StateStore.sources
    var sidebarExpanded: Boolean? = null,      // null: decided by the screen width
    var lastPlayed: String = "",               // the video played last, resumed from the sidebar
    val watched: MutableList<String> = mutableListOf(),
    val unwatched: MutableList<String> = mutableListOf(), // unticked in the app, beats an NFO playcount
    val resume: MutableMap<String, ResumeInfo> = mutableMapOf(),
    val picture: MutableMap<String, PictureOverride> = mutableMapOf(),
    val audioDelays: MutableMap<String, Int> = mutableMapOf(), // lip-sync per audio output, in ms
    val sorts: MutableMap<String, String> = mutableMapOf(), // SortOrder per LibrarySection, by name
    var wallMode: String = "",                 // WallMode by name; "" is posters
    var filesSort: String = "",                // FileSort by name; "" is by name
    val subtitles: MutableMap<String, SubtitleOverride> = mutableMapOf(),
    var settings: Settings = Settings(),
)

/**
 * The app's whole memory: watched set, resume positions, library root. One small
 * JSON file in private storage — no database, nothing to migrate, survives app
 * updates. Keys are absolute video paths, stable as long as the folder is.
 */
class StateStore(context: Context) {

    private val stateFile = File(context.filesDir, "state.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    /** Bumped on every save; reading [data] subscribes a screen to it, so a change shows at once. */
    private var revision by mutableIntStateOf(0)

    private val current: AppData = load()

    val data: AppData
        get() {
            revision
            return current
        }

    private fun load(): AppData = runCatching {
        json.decodeFromString(AppData.serializer(), stateFile.readText())
    }.getOrDefault(AppData())

    fun save() {
        runCatching { stateFile.writeText(json.encodeToString(AppData.serializer(), current)) }
        revision++
    }

    /** The household's watched marks over what the NFOs say; reading it subscribes a screen to changes. */
    val marks: WatchMarks get() = WatchMarks(data.watched, data.unwatched)

    /** Whether [videoPath] counts as watched; [nfoWatched] is what its NFO says. */
    fun isWatched(videoPath: String, nfoWatched: Boolean = false): Boolean = marks.isWatched(videoPath, nfoWatched)

    /** Marks a video watched as it finishes playing; cheap to call again and again. */
    fun markWatched(videoPath: String) {
        if (videoPath !in current.watched) setWatched(listOf(videoPath), true)
    }

    /** Ticks [videoPaths] off as watched (dropping their resume points) or unticks them. */
    fun setWatched(videoPaths: Collection<String>, watched: Boolean) {
        WatchMarks(current.watched, current.unwatched).set(videoPaths, watched)
        if (watched) videoPaths.forEach { current.resume.remove(it) }
        save()
    }

    fun saveResume(videoPath: String, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0 || positionMs <= 0) return
        // Near the end counts as finished; no point resuming the last seconds.
        // Half a percent of the runtime, capped at 30s, so short clips still resume.
        val tail = minOf(30_000L, durationMs / 20)
        if (durationMs - positionMs < tail) {
            data.resume.remove(videoPath)
        } else {
            data.resume[videoPath] = ResumeInfo(positionMs, durationMs)
        }
        save()
    }

    fun resumeFor(videoPath: String): ResumeInfo? = data.resume[videoPath]

    /** The lip-sync delay remembered for the audio output [outputKey], in ms. */
    fun audioDelayFor(outputKey: String): Int = data.audioDelays[outputKey] ?: 0

    fun saveAudioDelay(outputKey: String, delayMs: Int) {
        if (delayMs == 0) data.audioDelays.remove(outputKey) else data.audioDelays[outputKey] = delayMs
        save()
    }

    /** The order the [section]'s wall was last sorted by; A–Z until one is chosen. */
    fun sortFor(section: LibrarySection): SortOrder =
        data.sorts[section.name]?.let { name -> SortOrder.entries.firstOrNull { it.name == name } } ?: SortOrder.TITLE

    /** Posters, list or details, as the walls were last laid out. */
    val wallMode: WallMode get() = WallMode.entries.firstOrNull { it.name == data.wallMode } ?: WallMode.POSTERS

    /** How the Files browser orders videos, as last chosen. */
    val filesSort: FileSort get() = FileSort.entries.firstOrNull { it.name == data.filesSort } ?: FileSort.NAME

    /**
     * Every place the library is read from. Until a second one is added, that is the media folder
     * chosen at the start, as a local source.
     */
    val sources: List<Source>
        get() = data.sources.ifEmpty {
            listOfNotNull(
                data.rootPath.takeIf { it.isNotEmpty() }?.let { Source.local(PRIMARY_SOURCE, "Media folder", it) },
            )
        }

    fun saveSources(sources: List<Source>) {
        data.sources.clear()
        data.sources.addAll(sources)
        sources.firstOrNull { it.kind == SourceKind.LOCAL }?.let { data.rootPath = it.path }
        save()
    }

    fun saveFilesSort(sort: FileSort) {
        data.filesSort = sort.name
        save()
    }

    fun saveWallMode(mode: WallMode) {
        data.wallMode = mode.name
        save()
    }

    fun saveSort(section: LibrarySection, order: SortOrder) {
        data.sorts[section.name] = order.name
        save()
    }

    /** The household's default picture, from the guarded settings. */
    fun defaultPicture(): PictureAdjustment = with(data.settings) {
        PictureAdjustment(brightness, gamma, contrast, saturation)
    }

    /** The picture for [videoPath]: its own correction if one was set, else the default. */
    fun pictureFor(videoPath: String): PictureAdjustment =
        data.picture[videoPath]?.let { PictureAdjustment(it.brightness, it.gamma, it.contrast, it.saturation) }
            ?: defaultPicture()

    /** Remembers [picture] for [videoPath]; the default (or null) drops the video's own entry. */
    fun savePicture(videoPath: String, picture: PictureAdjustment?) {
        if (picture == null || picture == defaultPicture()) {
            data.picture.remove(videoPath)
        } else {
            data.picture[videoPath] =
                PictureOverride(picture.brightness, picture.gamma, picture.contrast, picture.saturation)
        }
        save()
    }

    /** The household's subtitle look, from the guarded settings. */
    fun defaultSubtitleLook(): SubtitleLook = SubtitleLook(
        size = runCatching { SubtitleLook.Size.valueOf(data.settings.subtitleSize) }
            .getOrDefault(SubtitleLook.Size.NORMAL),
        raised = data.settings.subtitleRaised,
        box = data.settings.subtitleBox,
    )

    fun subtitleLookFor(videoPath: String): SubtitleLook = data.subtitles[videoPath]?.let {
        SubtitleLook(
            delayMs = it.delayMs,
            size = runCatching { SubtitleLook.Size.valueOf(it.size) }.getOrDefault(SubtitleLook.Size.NORMAL),
            raised = it.raised,
            box = it.box,
        )
    } ?: defaultSubtitleLook()

    /** Remembers [look] for [videoPath]; the household default (or null) drops the video's own entry. */
    fun saveSubtitleLook(videoPath: String, look: SubtitleLook?) {
        if (look == null || look == defaultSubtitleLook()) {
            data.subtitles.remove(videoPath)
        } else {
            data.subtitles[videoPath] = SubtitleOverride(look.delayMs, look.size.name, look.raised, look.box)
        }
        save()
    }
}

/** The id the media folder chosen at the start keeps as a source. */
const val PRIMARY_SOURCE = "primary"
