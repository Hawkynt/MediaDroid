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
import com.hawkynt.mediadroid.player.AspectOverride
import com.hawkynt.mediadroid.player.LogoMask
import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.player.ScreenSetup
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

/** A station logo hidden for every video in a folder (one show): PictureAdjustment's LogoMask by names. */
@Serializable
data class LogoOverride(val corner: String, val size: Float, val mode: String)

/** A picture correction stored for one video; see [PictureAdjustment] for the ranges. */
@Serializable
data class PictureOverride(
    val brightness: Float = 0f,
    val gamma: Float = 1f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val sharpness: Float = 0f,
    val clarity: Float = 0f,
    /** The old denoise step (0..3); read for pictures saved before [noise]. */
    val denoise: Int = 0,
    val noise: Float? = null,
    val deinterlace: String = "OFF",
    val upscaler: String = "STANDARD",
    val interpolation: String = "OFF",
    val fieldRate: Boolean = false,
    /** PictureAdjustment.Stereo name: one eye of a 3D video, kept per video only. */
    val stereo: String = "OFF",
    /** How much of the upscaler shows, 0..1. */
    val upscalerStrength: Float = 1f,
    /** Debanding, 0 (off) .. 1. */
    val deband: Float = 0f,
    /** Black bars cropped; pictures saved before it was offered take the household's default, on. */
    val cropBlackBars: Boolean = true,
    /** AspectOverride name: the frame's shape where the file's flag is wrong. */
    val aspect: String = "AUTO",
    /** PictureAdjustment.Chroma name: how the colour of a 4:2:0 picture is enlarged. */
    val chroma: String = "BILINEAR",
    /** The upscaler's model by its id (NeuralEnlarging.models), empty for its default. */
    val upscalerModel: String = "",
    /** The upscaler's network run eight ways. */
    val upscalerTta: Boolean = false,
    /** Deblocking and deringing, 0 (off) .. 1. */
    val deblock: Float = 0f,
    val dering: Float = 0f,
    /** An mpv user shader's file name in the shader folder, or null. */
    val shader: String? = null,
    /** Temporal denoising, 0 (off) .. 1. */
    val temporalDenoise: Float = 0f,
    /** Dot crawl and rainbows combed out, 0 (off) .. 1. */
    val dotCrawl: Float = 0f,
)

/** How one output screen is set up (see [com.hawkynt.mediadroid.player.ScreenSetup]); names, as kept. */
@Serializable
data class ScreenOverride(
    /** ScreenShape name: the shape the screen is seen at, an anamorphic lens. */
    val shape: String = "AUTO",
    /** A projector's picture mirrored (rear projection) and upside down (ceiling mount). */
    val mirror: Boolean = false,
    val flip: Boolean = false,
    /** Keystone correction, -0.4..0.4 each (see Keystone). */
    val keystoneVertical: Float = 0f,
    val keystoneHorizontal: Float = 0f,
    /** An OLED screen: the picture and the overlays drift (BurnInDrift). */
    val burnInCare: Boolean = false,
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
    var evenLoudness: Boolean = false,        // every file at the same loudness: ReplayGain, else measured (R128)
    var matchRefreshRate: Boolean = false,    // TV boxes: switch the display to a multiple of the film's frame rate
    var audioPassthrough: Boolean = false,    // TV boxes: compressed surround untouched to an AV receiver
    var judderLimitPercent: Int = 0,          // judder reduction: how far the speed may change, 0 = off
    var judderDirection: String = "NEAREST",  // PlaybackMath.JudderDirection name
    var askedForNotifications: Boolean = false, // the media notification's permission is asked once
    var brightness: Float = 0f,               // -1..1
    var gamma: Float = 1.0f,                  // 0.5..2
    var contrast: Float = 1.0f,               // 0.5..2
    var saturation: Float = 1.0f,             // 0..2
    var sharpness: Float = 0f,                // 0..1, the picture filters' household default
    var clarity: Float = 0f,                  // 0..1
    var denoise: Int = 0,                     // 0..3, before the noise slider
    var noise: Float? = null,                 // -1 (removed) .. 1 (grain)
    var deinterlace: String = "OFF",          // PictureAdjustment.Deinterlace name
    var upscaler: String = "STANDARD",        // PictureAdjustment.Upscaler name
    var interpolation: String = "OFF",        // PictureAdjustment.Interpolation name
    var fieldRate: Boolean = false,           // YADIF/BWDIF show every field
    var upscalerStrength: Float = 1f,         // 0..1, how much of the upscaler shows over plain scaling
    var deband: Float = 0f,                   // 0 (off) .. 1, gradients' 8-bit steps smoothed
    var cropBlackBars: Boolean = true,        // letterbox and pillarbox bars found as it plays and cropped (AutoCrop)
    var chroma: String = "BILINEAR",          // PictureAdjustment.Chroma name, the colour's upscaling
    var upscalerModel: String = "",           // the upscaler's model by its id (NeuralEnlarging.models), "" its default
    var upscalerTta: Boolean = false,         // the upscaler's network run eight ways
    var deblock: Float = 0f,                  // 0 (off) .. 1, MPEG's block edges smoothed
    var dering: Float = 0f,                   // 0 (off) .. 1, the ripples beside edges smoothed
    var shader: String? = null,               // an mpv user shader's file name in shaderFolder (MpvShaders)
    var shaderFolder: String = "",            // the folder of mpv user shaders, a document tree URI; "" none
    var temporalDenoise: Float = 0f,          // 0 (off) .. 1, grain averaged with the frames either side
    var dotCrawl: Float = 0f,                 // 0 (off) .. 1, a composite capture's dots and rainbows combed out
    var subtitlesOn: Boolean = true,
    var subtitleLanguage: String = "",
    var subtitleSize: String = "NORMAL",       // SubtitleLook.Size name, the household default
    var subtitleRaised: Boolean = false,
    var subtitleBox: Boolean = false,
    var audioLanguage: String = "",
    var maxAgeYears: Int = 0,                 // 0 = off, else hide content above
    var hideUnrated: Boolean = false,         // with an age limit, hide content with no certification too
    var neuralUpscaling: Boolean = false,     // Real-ESRGAN offered as an upscaler; on only once the self-test passed
    var neuralUnit: String = "",              // where the self-test found it fastest: NPU, GPU or CPU
    var neuralMs: Float = 0f,                 // what one 576p picture took there in the self-test
    // Neural smooth motion by network (RIFE, DAIN, XVFI): offered once its self-test passed, kept as where it ran
    // fastest and what an in-between took there per tested size ("GPU|480=12.5;576=…", see
    // NeuralInterpolation.Passed).
    var neuralMotion: Map<String, String> = emptyMap(),
    // The upscalers' models on ncnn whose self-test passed, by NeuralEnlarging.Choice.key ("WAIFU2X",
    // "REALCUGAN:se-up4x-denoise3x+tta"), the same way: "GPU|576=…".
    var neuralEnlargers: Map<String, String> = emptyMap(),
    var bingeWatching: Boolean = true,        // a finished episode starts the next one
    var skipIntroCredits: Boolean = true,     // jump over known intros, end at the credits
    var useCutout: Boolean = false,           // the player's picture may reach into the camera cutout

    var spatialAudio: Boolean = false,        // hand multichannel sound to the platform spatializer (Android 13+)
    var bufferUnit: String = "SECONDS",      // DecodedBuffer.Unit name: how the buffer ahead is set
    var bufferValue: Float = 5f,              // in bufferUnit
    var downscaler: String = "BILINEAR",     // Downscaler name: how video larger than 1080p is brought down
    var softwareVideoDecoding: Boolean = false, // FFmpeg before the device's decoders: last resort, drains the battery
    var hdrToneMapping: Boolean = true,       // HDR on a screen without HDR tone-mapped by the app's stage, not the device
    var screens: Map<String, ScreenOverride> = emptyMap(), // per output screen (see screenKey): lens, projector
) {
    /** What [network]'s self-test found where it passed; null while it is off. */
    fun neuralMotionPassed(
        network: com.hawkynt.mediadroid.player.PictureAdjustment.Interpolation,
    ): com.hawkynt.mediadroid.player.NeuralInterpolation.Passed? =
        neuralMotion[network.name]?.let { com.hawkynt.mediadroid.player.NeuralInterpolation.Passed.decode(it) }

    /** What [choice]'s self-test found where it passed (a model of waifu2x, Real-CUGAN or RealSR); null otherwise. */
    fun neuralEnlargerPassed(
        choice: com.hawkynt.mediadroid.player.NeuralEnlarging.Choice,
    ): com.hawkynt.mediadroid.player.NeuralInterpolation.Passed? =
        neuralEnlargers[choice.key]?.let { com.hawkynt.mediadroid.player.NeuralInterpolation.Passed.decode(it) }

    /** The upscalers' models on ncnn this household's device passed. */
    val neuralEnlargersOffered: Set<com.hawkynt.mediadroid.player.NeuralEnlarging.Choice>
        get() = neuralEnlargers.keys.mapNotNull { com.hawkynt.mediadroid.player.NeuralEnlarging.choice(it) }
            .filter { it.upscaler.isEnlarger && neuralEnlargerPassed(it) != null }.toSet()

    /** The neural smooth-motion choices this household's device passed. */
    val neuralMotionOffered: Set<com.hawkynt.mediadroid.player.PictureAdjustment.Interpolation>
        get() = com.hawkynt.mediadroid.player.PictureAdjustment.Interpolation.entries
            .filter { it.isNeural && neuralMotionPassed(it) != null }.toSet()
}

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
    val logos: MutableMap<String, LogoOverride> = mutableMapOf(), // a station logo hidden, by the videos' folder
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
        PictureAdjustment(
            brightness, gamma, contrast, saturation, sharpness, clarity,
            noise ?: PictureAdjustment.noiseOfLevel(denoise), deinterlaceOf(deinterlace),
            upscalerOf(upscaler), interpolationOf(interpolation), fieldRate,
            upscalerStrength = upscalerStrength,
            deband = deband,
            cropBlackBars = cropBlackBars,
            chroma = chromaOf(chroma),
            upscalerModel = upscalerModel,
            upscalerTta = upscalerTta,
            deblock = deblock,
            dering = dering,
            shader = shader,
            temporalDenoise = temporalDenoise,
            dotCrawl = dotCrawl,
        )
    }

    private fun chromaOf(name: String) =
        runCatching { PictureAdjustment.Chroma.valueOf(name) }.getOrDefault(PictureAdjustment.Chroma.BILINEAR)

    private fun interpolationOf(name: String) = runCatching { PictureAdjustment.Interpolation.valueOf(name) }
        .getOrDefault(PictureAdjustment.Interpolation.OFF)

    private fun upscalerOf(name: String) =
        runCatching { PictureAdjustment.Upscaler.valueOf(name) }.getOrDefault(PictureAdjustment.Upscaler.STANDARD)

    private fun deinterlaceOf(name: String) =
        runCatching { PictureAdjustment.Deinterlace.valueOf(name) }.getOrDefault(PictureAdjustment.Deinterlace.OFF)

    /** The picture for [videoPath]: its own correction if one was set, else the default. */
    fun pictureFor(videoPath: String): PictureAdjustment = ownPictureFor(videoPath).copy(logo = logoFor(videoPath))

    /** The folder a video's station logo is remembered for: its show's. */
    private fun showOf(videoPath: String): String = videoPath.substringBeforeLast('/', "")

    /** The station logo hidden for [videoPath]'s show, or null. */
    fun logoFor(videoPath: String): LogoMask? = data.logos[showOf(videoPath)]?.let {
        runCatching {
            LogoMask(LogoMask.Corner.valueOf(it.corner), it.size, LogoMask.Mode.valueOf(it.mode))
        }.getOrNull()
    }

    private fun ownPictureFor(videoPath: String): PictureAdjustment =
        data.picture[videoPath]?.let {
            PictureAdjustment(
                it.brightness,
                it.gamma,
                it.contrast,
                it.saturation,
                it.sharpness,
                it.clarity,
                it.noise ?: PictureAdjustment.noiseOfLevel(it.denoise),
                deinterlaceOf(it.deinterlace),
                upscalerOf(it.upscaler),
                interpolationOf(it.interpolation),
                it.fieldRate,
                runCatching { PictureAdjustment.Stereo.valueOf(it.stereo) }.getOrDefault(PictureAdjustment.Stereo.OFF),
                it.upscalerStrength,
                it.deband,
                it.cropBlackBars,
                AspectOverride.entries.firstOrNull { a -> a.name == it.aspect } ?: AspectOverride.AUTO,
                chroma = chromaOf(it.chroma),
                upscalerModel = it.upscalerModel,
                upscalerTta = it.upscalerTta,
                deblock = it.deblock,
                dering = it.dering,
                shader = it.shader,
                temporalDenoise = it.temporalDenoise,
                dotCrawl = it.dotCrawl,
            )
        }
            ?: defaultPicture()

    /** Remembers [picture] for [videoPath]; the default (or null) drops the video's own entry. */
    fun savePicture(videoPath: String, picture: PictureAdjustment?) {
        // The logo is the show's, kept for its folder; the rest is the video's own.
        val logo = picture?.logo
        if (logo == null) {
            data.logos.remove(showOf(videoPath))
        } else {
            data.logos[showOf(videoPath)] = LogoOverride(logo.corner.name, logo.size, logo.mode.name)
        }
        savePictureOwn(videoPath, picture?.copy(logo = null))
    }

    private fun savePictureOwn(videoPath: String, picture: PictureAdjustment?) {
        if (picture == null || picture == defaultPicture()) {
            data.picture.remove(videoPath)
        } else {
            data.picture[videoPath] = PictureOverride(
                picture.brightness,
                picture.gamma,
                picture.contrast,
                picture.saturation,
                picture.sharpness,
                picture.clarity,
                0,
                picture.noise,
                picture.deinterlace.name,
                picture.upscaler.name,
                picture.interpolation.name,
                picture.fieldRate,
                picture.stereo.name,
                picture.upscalerStrength,
                picture.deband,
                picture.cropBlackBars,
                picture.aspect.name,
                chroma = picture.chroma.name,
                upscalerModel = picture.upscalerModel,
                upscalerTta = picture.upscalerTta,
                deblock = picture.deblock,
                dering = picture.dering,
                shader = picture.shader,
                temporalDenoise = picture.temporalDenoise,
                dotCrawl = picture.dotCrawl,
            )
        }
        save()
    }

    /** How the output screen [key] (see [screenKey]) is set up; the default until it is set. */
    fun screenSetup(key: String): ScreenSetup =
        data.settings.screens[key]?.let {
            ScreenSetup.of(it.shape, it.mirror, it.flip, it.keystoneVertical, it.keystoneHorizontal, it.burnInCare)
        } ?: ScreenSetup.Default

    /** Keeps [setup] for the output screen [key]; the default drops its entry. */
    fun saveScreenSetup(key: String, setup: ScreenSetup) {
        data.settings.screens = if (setup == ScreenSetup.Default) {
            data.settings.screens - key
        } else {
            data.settings.screens + (
                key to ScreenOverride(
                    setup.shape.name, setup.mirror, setup.flip, setup.keystoneVertical, setup.keystoneHorizontal,
                    setup.burnInCare,
                )
            )
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

/**
 * The key an output screen's setup is kept under: the device's own screen as one, every other by its name — the TV
 * or projector it is — so each keeps its own lens and projector setup.
 */
fun screenKey(display: android.view.Display?): String =
    if (display == null || display.displayId == android.view.Display.DEFAULT_DISPLAY) BUILT_IN_SCREEN
    else "display:${display.name}"

/** The key of the device's own screen (see [screenKey]). */
const val BUILT_IN_SCREEN = "built-in"

/** The id the media folder chosen at the start keeps as a source. */
const val PRIMARY_SOURCE = "primary"
