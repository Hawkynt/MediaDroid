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

    /** The household's picture: every video starts with it (PictureAdjustment's fields, as text). */
    var picture: com.hawkynt.mediadroid.player.PictureAdjustment
        get() {
            val p = com.hawkynt.mediadroid.player.PictureAdjustment.Neutral
            fun f(key: String, default: Float) = properties.getProperty("picture.$key")?.toFloatOrNull() ?: default
            return p.copy(
                brightness = f("brightness", p.brightness),
                gamma = f("gamma", p.gamma),
                contrast = f("contrast", p.contrast),
                saturation = f("saturation", p.saturation),
                sharpness = f("sharpness", p.sharpness),
                clarity = f("clarity", p.clarity),
                noise = f("noise", p.noise),
                deinterlace = runCatching {
                    com.hawkynt.mediadroid.player.PictureAdjustment.Deinterlace.valueOf(
                        properties.getProperty("picture.deinterlace"),
                    )
                }.getOrDefault(p.deinterlace),
                upscaler = runCatching {
                    com.hawkynt.mediadroid.player.PictureAdjustment.Upscaler.valueOf(
                        properties.getProperty("picture.upscaler"),
                    )
                }.getOrDefault(p.upscaler),
            )
        }
        set(value) {
            mapOf(
                "brightness" to value.brightness, "gamma" to value.gamma, "contrast" to value.contrast,
                "saturation" to value.saturation, "sharpness" to value.sharpness, "clarity" to value.clarity,
                "noise" to value.noise, "deinterlace" to value.deinterlace.name, "upscaler" to value.upscaler.name,
            ).forEach { (k, v) -> properties.setProperty("picture.$k", "$v") }
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
