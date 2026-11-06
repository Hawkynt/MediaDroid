package com.hawkynt.mediadroid.desktop

import com.hawkynt.mediadroid.library.Source
import com.hawkynt.mediadroid.player.AspectOverride
import com.hawkynt.mediadroid.player.LogoMask
import com.hawkynt.mediadroid.player.NeuralEnlarging
import com.hawkynt.mediadroid.player.NeuralInterpolation
import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.player.ScreenSetup
import com.hawkynt.mediadroid.player.ScreenShape
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * The desktop app's own state, `settings.json` in [folder] (the app-data folder, AppFolder): the library folder, the
 * household's picture, sound, subtitle language and speed, the window, the network sources, and per video its resume
 * point, its subtitle delay and whether it was watched. Saved on every change, written aside and moved over the old
 * file, so a crash midway never leaves half a file. The `state.properties` of earlier versions is taken over once and
 * moved aside.
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

    /** The station logo hidden for [video]'s show (its folder), or null. */
    fun logoFor(video: String): LogoMask? = settings.logos[showOf(video)]?.toMask()

    /** Remembers [logo] (none: null) for [video]'s show. */
    fun saveLogo(video: String, logo: LogoMask?) = change {
        val show = showOf(video)
        copy(logos = if (logo == null) logos - show else logos + (show to LogoSettings.of(logo)))
    }

    private fun showOf(video: String): String = video.replace('\\', '/').substringBeforeLast('/', "")

    /** The household's subtitle language (ISO 639-1), or empty for subtitles off: the last one picked. */
    var subtitleLanguage: String
        get() = settings.subtitleLanguage
        set(value) = change { copy(subtitleLanguage = value) }

    /** The household's playback speed, 1 for as filmed: every video starts at it. */
    var speed: Float
        get() = settings.speed
        set(value) = change { copy(speed = value) }

    /** The household's sound: the loudness gain, even loudness and the lip-sync delay of this machine's output. */
    var sound: SoundSettings
        get() = settings.sound
        set(value) = change { copy(sound = value) }

    /** Where the window was and how large, as `x,y,width,height` in dp, and whether it was maximized. */
    var window: String?
        get() = settings.window
        set(value) = change { copy(window = value) }

    /** The network sources added, in the order they were added. */
    var sources: List<Source>
        get() = settings.sources
        set(value) = change { copy(sources = value) }

    /** What [network]'s self-test found on this computer once it passed; null before. */
    fun motionPassed(network: PictureAdjustment.Interpolation): NeuralInterpolation.Passed? =
        settings.neuralMotion[network.name]?.let(NeuralInterpolation.Passed::decode)

    /** What [choice]'s self-test found on this computer once it passed; null before. */
    fun enlargerPassed(choice: NeuralEnlarging.Choice): NeuralInterpolation.Passed? =
        settings.neuralEnlargers[choice.key]?.let(NeuralInterpolation.Passed::decode)

    /** Keeps [network]'s passed self-test. */
    fun keepMotion(network: PictureAdjustment.Interpolation, passed: NeuralInterpolation.Passed) =
        change { copy(neuralMotion = neuralMotion + (network.name to passed.encode())) }

    /** Keeps [choice]'s passed self-test. */
    fun keepEnlarger(choice: NeuralEnlarging.Choice, passed: NeuralInterpolation.Passed) =
        change { copy(neuralEnlargers = neuralEnlargers + (choice.key to passed.encode())) }

    /** Every neural smooth-motion network whose self-test passed, with what it found. */
    val neuralMotion: Map<PictureAdjustment.Interpolation, NeuralInterpolation.Passed>
        get() = PictureAdjustment.Interpolation.entries.mapNotNull { n -> motionPassed(n)?.let { n to it } }.toMap()

    /** What the colouriser's self-test found on this computer once it passed; null before. */
    val colorizerPassed: NeuralInterpolation.Passed?
        get() = settings.neuralColorizer?.let(NeuralInterpolation.Passed::decode)

    /** Keeps the colouriser's passed self-test. */
    fun keepColorizer(passed: NeuralInterpolation.Passed) = change { copy(neuralColorizer = passed.encode()) }

    /** Every neural upscaler's model whose self-test passed, with what it found. */
    val neuralEnlargers: Map<NeuralEnlarging.Choice, NeuralInterpolation.Passed>
        get() = settings.neuralEnlargers.entries.mapNotNull { (key, value) ->
            val passed = NeuralInterpolation.Passed.decode(value)
            NeuralEnlarging.choice(key)?.let { choice -> passed?.let { choice to it } }
        }.toMap()

    /** The shape [video]'s frame is shown at where its file's flag is wrong; [AspectOverride.AUTO] until set. */
    fun aspectOf(video: String): AspectOverride =
        AspectOverride.entries.firstOrNull { it.name == settings.aspects[video] } ?: AspectOverride.AUTO

    /** Keeps [aspect] for [video]; [AspectOverride.AUTO] drops its entry. */
    fun setAspect(video: String, aspect: AspectOverride) = change {
        copy(aspects = if (aspect == AspectOverride.AUTO) aspects - video else aspects + (video to aspect.name))
    }

    /** How the output screen [key] (a monitor, see Main) is set up; the default until it is set. */
    fun screenSetup(key: String): ScreenSetup = settings.screens[key]?.toSetup() ?: ScreenSetup.Default

    /** Keeps [setup] for the output screen [key]; the default drops its entry. */
    fun setScreenSetup(key: String, setup: ScreenSetup) = change {
        copy(screens = if (setup == ScreenSetup.Default) screens - key else screens + (key to ScreenKept.of(setup)))
    }

    fun resumeMs(video: String): Long = settings.resume[video] ?: 0

    fun setResume(video: String, positionMs: Long) = change {
        copy(resume = if (positionMs <= 0) resume - video else resume + (video to positionMs))
    }

    /** The subtitle delay a video keeps (positive: the lines later), as Android keeps it per video; 0 in sync. */
    fun subtitleDelayMs(video: String): Int = settings.subtitleDelays[video] ?: 0

    fun setSubtitleDelay(video: String, delayMs: Int) = change {
        copy(subtitleDelays = if (delayMs == 0) subtitleDelays - video else subtitleDelays + (video to delayMs))
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
    val sound: SoundSettings = SoundSettings(),
    val window: String? = null,
    val sources: List<Source> = emptyList(),
    /** Resume points in milliseconds, by video location. */
    val resume: Map<String, Long> = emptyMap(),
    /** The video locations watched to the end. */
    val watched: Set<String> = emptySet(),
    /** Neural smooth motion by network, once its self-test passed: NeuralInterpolation.Passed, encoded. */
    val neuralMotion: Map<String, String> = emptyMap(),
    /** Neural upscalers by name, once their self-test passed, the same way. */
    val neuralEnlargers: Map<String, String> = emptyMap(),
    /** AspectOverride names by video location, where a file's flag is wrong. */
    val aspects: Map<String, String> = emptyMap(),
    /** Each output screen's setup by its key (a monitor's id). */
    val screens: Map<String, ScreenKept> = emptyMap(),
    /** The colouriser, once its self-test passed, the same way. */
    val neuralColorizer: String? = null,
    /** Subtitle delays in milliseconds, by video location; a video in sync has none. */
    val subtitleDelays: Map<String, Int> = emptyMap(),
    /** A station logo hidden, by the videos' folder (one show): LogoMask's corner, size and mode. */
    val logos: Map<String, LogoSettings> = emptyMap(),
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

/** One output screen's setup as kept (see ScreenSetup): its choices by name. */
@Serializable
internal data class ScreenKept(
    val shape: String = ScreenShape.AUTO.name,
    val mirror: Boolean = false,
    val flip: Boolean = false,
    val keystoneVertical: Float = 0f,
    val keystoneHorizontal: Float = 0f,
) {
    fun toSetup(): ScreenSetup = ScreenSetup.of(shape, mirror, flip, keystoneVertical, keystoneHorizontal)

    companion object {
        fun of(setup: ScreenSetup) = ScreenKept(
            setup.shape.name, setup.mirror, setup.flip, setup.keystoneVertical, setup.keystoneHorizontal,
        )
    }
}

/**
 * The household's sound as kept, as Android's Settings and its gear keep it: the loudness gain in dB (-12..12),
 * even loudness from file to file, and the audio delay in ms (positive: the sound later). Night mode is not kept: as on
 * Android it is switched on for an evening, and the next player starts without it.
 */
@Serializable
data class SoundSettings(
    val loudnessDb: Float = 0f,
    val evenLoudness: Boolean = false,
    val audioDelayMs: Int = 0,
)

/** A station logo as kept: LogoMask's choices by name; one this version does not know is dropped. */
@Serializable
internal data class LogoSettings(val corner: String, val size: Float, val mode: String) {
    fun toMask(): LogoMask? = runCatching {
        LogoMask(LogoMask.Corner.valueOf(corner), size, LogoMask.Mode.valueOf(mode))
    }.getOrNull()

    companion object {
        fun of(m: LogoMask) = LogoSettings(m.corner.name, m.size, m.mode.name)
    }
}

/**
 * The household's picture as kept: PictureAdjustment's fields the desktop offers — not the 3D eye, a choice for one
 * video — the choices by name; a name this version does not know falls back to the neutral choice.
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
    /** Black bars cropped (AutoCrop): on for the household, see README "Picture". */
    val cropBlackBars: Boolean = true,
    val fieldRate: Boolean = PictureAdjustment.Neutral.fieldRate,
    val upscalerStrength: Float = PictureAdjustment.Neutral.upscalerStrength,
    val interpolation: String = PictureAdjustment.Neutral.interpolation.name,
    val chroma: String = PictureAdjustment.Neutral.chroma.name,
    /** The upscaler's model by its id (NeuralEnlarging.models), empty for its default. */
    val upscalerModel: String = PictureAdjustment.Neutral.upscalerModel,
    /** The upscaler's network run eight ways. */
    val upscalerTta: Boolean = PictureAdjustment.Neutral.upscalerTta,
    /** PictureAdjustment.Colorize name: colour for black-and-white pictures. */
    val colorize: String = PictureAdjustment.Neutral.colorize.name,
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
            cropBlackBars = cropBlackBars,
            fieldRate = fieldRate,
            upscalerStrength = upscalerStrength,
            interpolation = runCatching { PictureAdjustment.Interpolation.valueOf(interpolation) }
                .getOrDefault(p.interpolation),
            chroma = runCatching { PictureAdjustment.Chroma.valueOf(chroma) }.getOrDefault(p.chroma),
            upscalerModel = upscalerModel,
            upscalerTta = upscalerTta,
            colorize = runCatching { PictureAdjustment.Colorize.valueOf(colorize) }.getOrDefault(p.colorize),
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
            cropBlackBars = p.cropBlackBars,
            fieldRate = p.fieldRate,
            upscalerStrength = p.upscalerStrength,
            interpolation = p.interpolation.name,
            chroma = p.chroma.name,
            upscalerModel = p.upscalerModel,
            upscalerTta = p.upscalerTta,
            colorize = p.colorize.name,
        )
    }
}
