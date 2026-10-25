package com.hawkynt.mediadroid.data

import android.content.Context
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ResumeInfo(val positionMs: Long = 0, val durationMs: Long = 0)

@Serializable
data class AppData(
    var rootPath: String = "/sdcard/Media",
    val watched: MutableList<String> = mutableListOf(),
    val resume: MutableMap<String, ResumeInfo> = mutableMapOf(),
)

/**
 * The app's whole memory: watched set, resume positions, library root. One small
 * JSON file in private storage — no database, nothing to migrate, survives app
 * updates. Keys are absolute video paths, stable as long as the folder is.
 */
class StateStore(context: Context) {

    private val stateFile = File(context.filesDir, "state.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    var data: AppData = load()
        private set

    private fun load(): AppData = runCatching {
        json.decodeFromString(AppData.serializer(), stateFile.readText())
    }.getOrDefault(AppData())

    fun save() {
        runCatching { stateFile.writeText(json.encodeToString(AppData.serializer(), data)) }
    }

    fun isWatched(videoPath: String): Boolean = videoPath in data.watched

    fun markWatched(videoPath: String) {
        if (videoPath !in data.watched) {
            data.watched.add(videoPath)
            data.resume.remove(videoPath)
            save()
        }
    }

    fun setWatched(videoPath: String, watched: Boolean) {
        if (watched) markWatched(videoPath) else {
            data.watched.remove(videoPath)
            save()
        }
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
}
