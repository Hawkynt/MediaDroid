package com.hawkynt.mediadroid.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.hawkynt.mediadroid.library.WatchMarks
import com.hawkynt.mediadroid.player.PictureAdjustment
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ResumeInfo(val positionMs: Long = 0, val durationMs: Long = 0)

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
    var brightness: Float = 0f,               // -1..1
    var gamma: Float = 1.0f,                  // 0.5..2
    var contrast: Float = 1.0f,               // 0.5..2
    var saturation: Float = 1.0f,             // 0..2
    var subtitlesOn: Boolean = true,
    var subtitleLanguage: String = "",
    var audioLanguage: String = "",
    var maxAgeYears: Int = 0,                 // 0 = off, else hide content above
    var bingeWatching: Boolean = true,        // a finished episode starts the next one
    var skipIntroCredits: Boolean = true,     // jump over known intros, end at the credits
    var askedForNotifications: Boolean = false, // the media notification's permission is asked once
    var softwareVideoDecoding: Boolean = false, // FFmpeg before the device's decoders: last resort, drains the battery
)

@Serializable
data class AppData(
    var rootPath: String = "",                 // "" until a media folder is chosen
    var sidebarExpanded: Boolean? = null,      // null: decided by the screen width
    val watched: MutableList<String> = mutableListOf(),
    val unwatched: MutableList<String> = mutableListOf(), // unticked in the app, beats an NFO playcount
    val resume: MutableMap<String, ResumeInfo> = mutableMapOf(),
    val picture: MutableMap<String, PictureOverride> = mutableMapOf(),
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
}
