package com.hawkynt.mediadroyd.desktop

import com.hawkynt.mediadroyd.library.FolderDefaults
import com.hawkynt.mediadroyd.library.FolderPlace
import com.hawkynt.mediadroyd.library.Source
import com.hawkynt.mediadroyd.player.AspectOverride
import com.hawkynt.mediadroyd.player.LogoMask
import com.hawkynt.mediadroyd.player.NeuralEnlarging
import com.hawkynt.mediadroyd.player.NeuralInterpolation
import com.hawkynt.mediadroyd.player.PictureAdjustment
import com.hawkynt.mediadroyd.player.ScreenSetup
import com.hawkynt.mediadroyd.player.ScreenShape
import com.hawkynt.mediadroyd.state.LogoOverride
import com.hawkynt.mediadroyd.state.PRIMARY_SOURCE
import com.hawkynt.mediadroyd.state.ResumeInfo
import com.hawkynt.mediadroyd.state.StateFile
import com.hawkynt.mediadroyd.state.StateStore
import com.hawkynt.mediadroyd.state.SubtitleOverride
import com.hawkynt.mediadroyd.ui.platform.observedStateStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * The desktop app's memory: the household's [state] — the model the Android app keeps too (StateStore) — in
 * `state.json` in [folder] (the app-data folder, AppFolder), saved on every change, written aside and moved over the
 * old file, so a crash midway never leaves half a file. The `settings.json` of earlier versions, and the
 * `state.properties` before it, are taken over once and moved aside.
 *
 * What the desktop's screens ask for reads from that model: the library folder is its media folder, the network
 * sources the sources beside it, the household's picture, sound, subtitle language and speed its settings, a video's
 * subtitle delay its subtitle look, a video's shape and colour system its own picture.
 */
class DesktopStore internal constructor(
    /** The household's memory, as every platform keeps it. */
    val state: StateStore,
) {

    constructor(folder: File) : this(observedStateStore(DesktopStateFile(folder)))

    private val data get() = state.data

    var libraryFolder: String?
        get() = data.rootPath.ifEmpty { null }
        set(value) {
            data.rootPath = value.orEmpty()
            keepSources(sources)
        }

    /** Every place the library is read from: the media folder, then the network sources. */
    val allSources: List<Source> get() = state.sources

    /** The network sources (and further local folders) added, in the order they were added. */
    var sources: List<Source>
        get() = state.sources.filter { it.id != PRIMARY_SOURCE }
        set(value) = keepSources(value)

    // The media folder heads the list once anything is added beside it, as on Android (StateStore.sources).
    private fun keepSources(added: List<Source>) {
        val primary = libraryFolder?.let { Source.local(PRIMARY_SOURCE, MEDIA_FOLDER, it) }
        data.sources.clear()
        if (added.isNotEmpty()) data.sources.addAll(listOfNotNull(primary) + added)
        state.save()
    }

    /** The household's picture: every video starts with it. */
    var picture: PictureAdjustment
        get() = state.defaultPicture()
        set(value) = state.saveDefaultPicture(value)

    /** The station logo hidden for [video]'s show (its folder), or null. */
    fun logoFor(video: String): LogoMask? = state.logoFor(video)

    /** Remembers [logo] (none: null) for [video]'s show. */
    fun saveLogo(video: String, logo: LogoMask?) = state.saveLogo(video, logo)

    /**
     * The picture [video] plays with: its own, if it was changed in the player's gear, else its folder's — the nearest
     * folder or share above it with a picture of its own (FolderDefaults) — else the household's; with its show's
     * station logo hidden. As on Android (StateStore.pictureFor).
     */
    fun pictureFor(video: String): PictureAdjustment = state.pictureFor(video)

    /**
     * Keeps [picture] as [video]'s own (its logo for its show); what it would start with anyway, or null, forgets it.
     */
    fun savePicture(video: String, picture: PictureAdjustment?) = state.savePicture(video, picture)

    /** The folder [video] lies in, as FolderDefaults keeps it. */
    fun folderOf(video: String): FolderPlace = state.folderOf(video)

    /** The folder or share whose picture [video] takes, if any has one. */
    fun pictureFolderOf(video: String): FolderPlace? = state.pictureFolderOf(video)

    /** Every folder or share with a picture of its own. */
    val folderPictures: Set<FolderPlace> get() = state.folderPictures().keys

    /** Keeps [picture] as the one every video in [place] starts with; null forgets it. */
    fun setFolderPicture(place: FolderPlace, picture: PictureAdjustment?) = state.saveFolderPicture(place, picture)

    /** The household's subtitle language (ISO 639-1), or empty for subtitles off: the last one picked. */
    var subtitleLanguage: String
        get() = data.settings.let { if (it.subtitlesOn) it.subtitleLanguage else "" }
        set(value) {
            data.settings.subtitlesOn = value.isNotEmpty()
            if (value.isNotEmpty()) data.settings.subtitleLanguage = value
            state.save()
        }

    /** The household's playback speed, 1 for as filmed: every video starts at it. */
    var speed: Float
        get() = data.settings.defaultSpeed
        set(value) {
            data.settings.defaultSpeed = value
            state.save()
        }

    /** Software decoding first, as Android's setting: no graphics chip's decoder is tried (HardwareDecoding). */
    var softwareDecoding: Boolean
        get() = data.settings.softwareVideoDecoding
        set(value) {
            data.settings.softwareVideoDecoding = value
            state.save()
        }

    /** The household's sound: the loudness gain, even loudness and the lip-sync delay of this machine's output. */
    var sound: SoundSettings
        get() = SoundSettings(data.settings.loudness, data.settings.evenLoudness, state.audioDelayFor(AUDIO_OUTPUT))
        set(value) {
            data.settings.loudness = value.loudnessDb
            data.settings.evenLoudness = value.evenLoudness
            state.saveAudioDelay(AUDIO_OUTPUT, value.audioDelayMs)
        }

    /** Where the window was and how large, as `x,y,width,height` in dp, and whether it was maximized. */
    var window: String?
        get() = data.window
        set(value) {
            data.window = value
            state.save()
        }

    /** What [network]'s self-test found on this computer once it passed; null before. */
    fun motionPassed(network: PictureAdjustment.Interpolation): NeuralInterpolation.Passed? =
        data.settings.neuralMotionPassed(network)

    /** What [choice]'s self-test found on this computer once it passed; null before. */
    fun enlargerPassed(choice: NeuralEnlarging.Choice): NeuralInterpolation.Passed? =
        data.settings.neuralEnlargerPassed(choice)

    /** Keeps [network]'s passed self-test. */
    fun keepMotion(network: PictureAdjustment.Interpolation, passed: NeuralInterpolation.Passed) {
        data.settings.neuralMotion += network.name to passed.encode()
        state.save()
    }

    /** Keeps [choice]'s passed self-test. */
    fun keepEnlarger(choice: NeuralEnlarging.Choice, passed: NeuralInterpolation.Passed) {
        data.settings.neuralEnlargers += choice.key to passed.encode()
        state.save()
    }

    /** Every neural smooth-motion network whose self-test passed, with what it found. */
    val neuralMotion: Map<PictureAdjustment.Interpolation, NeuralInterpolation.Passed>
        get() = PictureAdjustment.Interpolation.entries.mapNotNull { n -> motionPassed(n)?.let { n to it } }.toMap()

    /** What the colouriser's self-test found on this computer once it passed; null before. */
    val colorizerPassed: NeuralInterpolation.Passed? get() = data.settings.colorizerPassed

    /** Keeps the colouriser's passed self-test. */
    fun keepColorizer(passed: NeuralInterpolation.Passed) {
        data.settings.neuralColorizer = passed.encode()
        state.save()
    }

    /** Every neural upscaler's model whose self-test passed, with what it found. */
    val neuralEnlargers: Map<NeuralEnlarging.Choice, NeuralInterpolation.Passed>
        get() = data.settings.neuralEnlargers.keys.mapNotNull { key ->
            NeuralEnlarging.choice(key)?.let { choice -> enlargerPassed(choice)?.let { choice to it } }
        }.toMap()

    /** How the output screen [key] (a monitor, see Main) is set up; the default until it is set. */
    fun screenSetup(key: String): ScreenSetup = state.screenSetup(key)

    /** Keeps [setup] for the output screen [key]; the default drops its entry. */
    fun setScreenSetup(key: String, setup: ScreenSetup) = state.saveScreenSetup(key, setup)

    fun resumeMs(video: String): Long = data.resume[video]?.positionMs ?: 0

    /**
     * Keeps where [video] was left, [positionMs] in, and how long it runs ([durationMs], the one known before where it
     * is not given) — the walls' progress bars need both; at 0 the point goes.
     */
    fun setResume(video: String, positionMs: Long, durationMs: Long = 0) {
        if (positionMs <= 0) {
            data.resume.remove(video)
        } else {
            val known = durationMs.takeIf { it > 0 } ?: data.resume[video]?.durationMs ?: 0
            data.resume[video] = ResumeInfo(positionMs, known)
        }
        state.save()
    }

    /** The subtitle delay a video keeps (positive: the lines later), as Android keeps it per video; 0 in sync. */
    fun subtitleDelayMs(video: String): Int = data.subtitles[video]?.delayMs ?: 0

    fun setSubtitleDelay(video: String, delayMs: Int) {
        val look = (data.subtitles[video] ?: SubtitleOverride()).copy(delayMs = delayMs)
        if (look == SubtitleOverride()) data.subtitles.remove(video) else data.subtitles[video] = look
        state.save()
    }

    fun watched(video: String): Boolean = state.isWatched(video)

    fun markWatched(video: String) = state.markWatched(video)

    companion object {
        /** The name the media folder goes by among the sources, as on Android. */
        const val MEDIA_FOLDER = "Media folder"

        /** The one audio output whose lip-sync delay is kept (StateStore keeps one per output). */
        const val AUDIO_OUTPUT = "desktop"
    }
}

/**
 * `state.json` in [folder], written aside and moved over; before there is one, the `settings.json` of earlier versions
 * (or the `state.properties` before that) is taken over once and moved aside. A file that cannot be read is kept aside
 * for whoever wants to look, and the app starts afresh.
 */
internal class DesktopStateFile(private val folder: File) : StateFile {

    private val file = File(folder, FILE)

    override fun read(): String? {
        if (file.exists()) {
            val text = file.readText()
            if (StateStore.decode(text) != null) return text
            file.copyTo(File(folder, "$FILE.damaged"), overwrite = true)
            return null
        }
        val earlier = earlierSettings() ?: return null
        val text = takeOver(earlier)
        write(text)
        return text
    }

    override fun write(text: String) {
        folder.mkdirs()
        val aside = File(folder, "$FILE.new")
        aside.writeText(text)
        runCatching {
            Files.move(
                aside.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(aside.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    // The settings of an earlier version, its file moved aside; null when there is none.
    private fun earlierSettings(): DesktopSettings? {
        val json = File(folder, OLD_JSON)
        if (json.exists()) {
            val settings = DesktopSettings.decode(json.readText())
            if (settings == null) json.copyTo(File(folder, "$OLD_JSON.damaged"), overwrite = true)
            json.renameTo(File(folder, "$OLD_JSON.old"))
            return settings
        }
        val properties = File(folder, OLD_PROPERTIES)
        if (!properties.exists()) return null
        val settings = DesktopSettings.fromProperties(Properties().apply { properties.inputStream().use { load(it) } })
        properties.renameTo(File(folder, "$OLD_PROPERTIES.old"))
        return settings
    }

    private companion object {
        const val FILE = "state.json"
        const val OLD_JSON = "settings.json"
        const val OLD_PROPERTIES = "state.properties"
    }
}

/** [old] as the household's memory (StateStore): each setting where the model keeps it, as the text kept. */
internal fun takeOver(old: DesktopSettings): String {
    var written = ""
    val memory = object : StateFile {
        override fun read(): String? = null

        override fun write(text: String) {
            written = text
        }
    }
    DesktopStore(StateStore(memory)).apply {
        libraryFolder = old.library
        sources = old.sources
        picture = old.picture.toAdjustment()
        subtitleLanguage = old.subtitleLanguage
        speed = old.speed
        softwareDecoding = old.softwareDecoding
        sound = old.sound
        window = old.window
        state.data.settings.neuralMotion = old.neuralMotion
        state.data.settings.neuralEnlargers = old.neuralEnlargers
        state.data.settings.neuralColorizer = old.neuralColorizer
        old.logos.forEach { (show, logo) -> state.data.logos[show] = LogoOverride(logo.corner, logo.size, logo.mode) }
        old.screens.forEach { (key, kept) -> setScreenSetup(key, kept.toSetup()) }
        old.resume.forEach { (video, ms) -> setResume(video, ms) }
        state.setWatched(old.watched, true)
        old.subtitleDelays.forEach { (video, ms) -> setSubtitleDelay(video, ms) }
        // A folder of this computer was kept by its whole path; now it lies under the media folder, as on Android.
        old.folderPictures.forEach { (key, kept) ->
            val place = FolderPlace.of(key) ?: return@forEach
            val now = if (place.source.isEmpty()) FolderDefaults.placeOf(allSources, place.folder, true) else place
            setFolderPicture(now, kept.toAdjustment())
        }
        // A video's shape and colour system are its own picture's now, as on Android.
        old.aspects.forEach { (video, name) ->
            AspectOverride.entries.firstOrNull { it.name == name }?.let {
                savePicture(video, pictureFor(video).copy(aspect = it))
            }
        }
        old.colourSystems.forEach { (video, name) ->
            runCatching { PictureAdjustment.ColourSystem.valueOf(name) }.getOrNull()?.let {
                savePicture(video, pictureFor(video).copy(colourSystem = it))
            }
        }
        state.save()
    }
    return written
}

/**
 * What the `settings.json` of earlier versions holds, read once to be taken over (DesktopStateFile); every field has a
 * default, so a file from any earlier version reads.
 */
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
    /** PictureAdjustment.ColourSystem names, by video location: a composite capture's, chosen by hand. */
    val colourSystems: Map<String, String> = emptyMap(),
    /** A station logo hidden, by the videos' folder (one show): LogoMask's corner, size and mode. */
    val logos: Map<String, LogoSettings> = emptyMap(),
    /** Pictures per folder or share, by FolderPlace.key: what the videos in it start with. */
    val folderPictures: Map<String, PictureSettings> = emptyMap(),
    /** Software decoding first: no graphics chip's decoder tried. */
    val softwareDecoding: Boolean = false,
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
    val burnInCare: Boolean = false,
) {
    fun toSetup(): ScreenSetup = ScreenSetup.of(shape, mirror, flip, keystoneVertical, keystoneHorizontal, burnInCare)

    companion object {
        fun of(setup: ScreenSetup) = ScreenKept(
            setup.shape.name, setup.mirror, setup.flip, setup.keystoneVertical, setup.keystoneHorizontal,
            setup.burnInCare,
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
    val deblock: Float = PictureAdjustment.Neutral.deblock,
    val dering: Float = PictureAdjustment.Neutral.dering,
    /** An mpv user shader's file name in the shaders folder (MpvShaders), or null. */
    val shader: String? = null,
    val temporalDenoise: Float = PictureAdjustment.Neutral.temporalDenoise,
    val dotCrawl: Float = PictureAdjustment.Neutral.dotCrawl,
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
            deblock = deblock,
            dering = dering,
            shader = shader,
            temporalDenoise = temporalDenoise,
            dotCrawl = dotCrawl,
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
            deblock = p.deblock,
            dering = p.dering,
            shader = p.shader,
            temporalDenoise = p.temporalDenoise,
            dotCrawl = p.dotCrawl,
        )
    }
}
