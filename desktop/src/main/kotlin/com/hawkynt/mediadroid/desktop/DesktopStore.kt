package com.hawkynt.mediadroid.desktop

import com.hawkynt.mediadroid.library.Source
import com.hawkynt.mediadroid.player.PictureAdjustment
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * The desktop app's own state, `settings.json` in [folder] (the app-data folder, AppFolder): the library folder, the
 * household's picture, subtitle language and speed, the window, the network sources, and per video its resume point
 * and whether it was watched. Saved on every change, written aside and moved over the old file, so a crash midway
 * never leaves half a file. The `state.properties` of earlier versions is taken over once and moved aside.
 */
class DesktopStore(private val folder: File) {

    private val file = File(folder, FILE)
    private var settings: DesktopSettings = load()

    var libraryFolder: String?
        get() = settings.library
        set(value) = change { copy(library = value) }

    /** The household's picture: every video starts with it. */
    var picture: PictureAdjustment
        get() = settings.picture.toAdjustment()
        set(value) = change { copy(picture = PictureSettings.of(value)) }

    /** The household's subtitle language (ISO 639-1), or empty for subtitles off: the last one picked. */
    var subtitleLanguage: String
        get() = settings.subtitleLanguage
        set(value) = change { copy(subtitleLanguage = value) }

    /** The household's playback speed, 1 for as filmed: every video starts at it. */
    var speed: Float
        get() = settings.speed
        set(value) = change { copy(speed = value) }

    /** Where the window was and how large, as `x,y,width,height` in dp, and whether it was maximized. */
    var window: String?
        get() = settings.window
        set(value) = change { copy(window = value) }

    /** The network sources added, in the order they were added. */
    var sources: List<Source>
        get() = settings.sources
        set(value) = change { copy(sources = value) }

    fun resumeMs(video: String): Long = settings.resume[video] ?: 0

    fun setResume(video: String, positionMs: Long) = change {
        copy(resume = if (positionMs <= 0) resume - video else resume + (video to positionMs))
    }

    fun watched(video: String): Boolean = video in settings.watched

    fun markWatched(video: String) = change { copy(watched = watched + video, resume = resume - video) }

    private fun change(how: DesktopSettings.() -> DesktopSettings) {
        settings = settings.how()
        save()
    }

    private fun load(): DesktopSettings {
        if (file.exists()) {
            return DesktopSettings.decode(file.readText()) ?: DesktopSettings().also {
                // Unreadable: kept aside for whoever wants to look, the app starts afresh.
                file.copyTo(File(folder, "$FILE.damaged"), overwrite = true)
            }
        }
        val old = File(folder, OLD_FILE)
        if (!old.exists()) return DesktopSettings()
        val taken = DesktopSettings.fromProperties(Properties().apply { old.inputStream().use { load(it) } })
        save(taken)
        old.renameTo(File(folder, "$OLD_FILE.old"))
        return taken
    }

    private fun save(what: DesktopSettings = settings) {
        folder.mkdirs()
        val aside = File(folder, "$FILE.new")
        aside.writeText(DesktopSettings.encode(what))
        runCatching {
            Files.move(
                aside.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(aside.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val FILE = "settings.json"
        const val OLD_FILE = "state.properties"
    }
}

/** What `settings.json` holds; every field has a default, so a file from an older version reads. */
@Serializable
internal data class DesktopSettings(
    val library: String? = null,
    val picture: PictureSettings = PictureSettings(),
    val subtitleLanguage: String = "",
    val speed: Float = 1f,
    val window: String? = null,
    val sources: List<Source> = emptyList(),
    /** Resume points in milliseconds, by video location. */
    val resume: Map<String, Long> = emptyMap(),
    /** The video locations watched to the end. */
    val watched: Set<String> = emptySet(),
) {
    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        }

        fun encode(settings: DesktopSettings): String = json.encodeToString(serializer(), settings) + "\n"

        /** The settings in [text]; null when it is not JSON or not these settings. */
        fun decode(text: String): DesktopSettings? =
            runCatching { json.decodeFromString(serializer(), text) }.getOrNull()

        /** The settings as the `state.properties` of earlier versions kept them. */
        fun fromProperties(p: Properties): DesktopSettings {
            fun f(key: String) = p.getProperty(key)?.toFloatOrNull()
            val neutral = PictureSettings()
            val keys = p.stringPropertyNames()
            return DesktopSettings(
                library = p.getProperty("library"),
                picture = PictureSettings(
                    brightness = f("picture.brightness") ?: neutral.brightness,
                    gamma = f("picture.gamma") ?: neutral.gamma,
                    contrast = f("picture.contrast") ?: neutral.contrast,
                    saturation = f("picture.saturation") ?: neutral.saturation,
                    sharpness = f("picture.sharpness") ?: neutral.sharpness,
                    clarity = f("picture.clarity") ?: neutral.clarity,
                    noise = f("picture.noise") ?: neutral.noise,
                    deband = f("picture.deband") ?: neutral.deband,
                    deinterlace = p.getProperty("picture.deinterlace") ?: neutral.deinterlace,
                    upscaler = p.getProperty("picture.upscaler") ?: neutral.upscaler,
                ),
                subtitleLanguage = p.getProperty("subtitles.language").orEmpty(),
                speed = f("speed") ?: 1f,
                window = p.getProperty("window"),
                sources = keys.filter { it.startsWith("source.") }.sorted()
                    .mapNotNull { runCatching { Source.decode(p.getProperty(it)) }.getOrNull() },
                resume = keys.filter { it.startsWith("resume.") }
                    .mapNotNull { key -> p.getProperty(key).toLongOrNull()?.let { key.removePrefix("resume.") to it } }
                    .toMap(),
                watched = keys.filter { it.startsWith("watched.") && p.getProperty(it) == "1" }
                    .map { it.removePrefix("watched.") }.toSet(),
            )
        }
    }
}

/**
 * The household's picture as kept: PictureAdjustment's fields the desktop offers, the choices by name — a name this
 * version does not know falls back to the neutral choice.
 */
@Serializable
internal data class PictureSettings(
    val brightness: Float = PictureAdjustment.Neutral.brightness,
    val gamma: Float = PictureAdjustment.Neutral.gamma,
    val contrast: Float = PictureAdjustment.Neutral.contrast,
    val saturation: Float = PictureAdjustment.Neutral.saturation,
    val sharpness: Float = PictureAdjustment.Neutral.sharpness,
    val clarity: Float = PictureAdjustment.Neutral.clarity,
    val noise: Float = PictureAdjustment.Neutral.noise,
    val deband: Float = PictureAdjustment.Neutral.deband,
    val deinterlace: String = PictureAdjustment.Neutral.deinterlace.name,
    val upscaler: String = PictureAdjustment.Neutral.upscaler.name,
) {
    fun toAdjustment(): PictureAdjustment {
        val p = PictureAdjustment.Neutral
        return p.copy(
            brightness = brightness,
            gamma = gamma,
            contrast = contrast,
            saturation = saturation,
            sharpness = sharpness,
            clarity = clarity,
            noise = noise,
            deband = deband,
            deinterlace = runCatching { PictureAdjustment.Deinterlace.valueOf(deinterlace) }
                .getOrDefault(p.deinterlace),
            upscaler = runCatching { PictureAdjustment.Upscaler.valueOf(upscaler) }.getOrDefault(p.upscaler),
        )
    }

    companion object {
        fun of(p: PictureAdjustment) = PictureSettings(
            brightness = p.brightness,
            gamma = p.gamma,
            contrast = p.contrast,
            saturation = p.saturation,
            sharpness = p.sharpness,
            clarity = p.clarity,
            noise = p.noise,
            deband = p.deband,
            deinterlace = p.deinterlace.name,
            upscaler = p.upscaler.name,
        )
    }
}
