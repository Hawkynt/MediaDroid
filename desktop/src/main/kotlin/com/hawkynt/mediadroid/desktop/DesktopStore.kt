package com.hawkynt.mediadroid.desktop

import java.io.File
import java.util.Properties

/**
 * The desktop app's own state, a properties file in [folder]: the library folder, and per video its resume point
 * and whether it was watched. Saved on every change; small enough not to matter.
 */
class DesktopStore(private val folder: File) {

    private val file = File(folder, "state.properties")
    private val properties = Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }

    var libraryFolder: String?
        get() = properties.getProperty("library")
        set(value) {
            if (value == null) properties.remove("library") else properties.setProperty("library", value)
            save()
        }

    fun resumeMs(video: String): Long = properties.getProperty("resume.$video")?.toLongOrNull() ?: 0

    fun setResume(video: String, positionMs: Long) {
        if (positionMs <= 0) {
            properties.remove("resume.$video")
        } else {
            properties.setProperty("resume.$video", "$positionMs")
        }
        save()
    }

    fun watched(video: String): Boolean = properties.getProperty("watched.$video") == "1"

    fun markWatched(video: String) {
        properties.setProperty("watched.$video", "1")
        properties.remove("resume.$video")
        save()
    }

    private fun save() {
        folder.mkdirs()
        file.outputStream().use { properties.store(it, "MediaDroid") }
    }
}
