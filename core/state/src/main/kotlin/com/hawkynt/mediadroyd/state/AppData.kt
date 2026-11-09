package com.hawkynt.mediadroyd.state

import com.hawkynt.mediadroyd.library.Source
import com.hawkynt.mediadroyd.player.NeuralEnlarging
import com.hawkynt.mediadroyd.player.NeuralInterpolation
import com.hawkynt.mediadroyd.player.PictureAdjustment
import kotlinx.serialization.Serializable

/** Where a video stopped, and how long it is (0 where that was not known when it stopped). */
@Serializable
data class ResumeInfo(val positionMs: Long = 0, val durationMs: Long = 0)

/** A subtitle look stored for one video; see SubtitleLook. */
@Serializable
data class SubtitleOverride(
    val delayMs: Int = 0,
    val size: String = "NORMAL",
    val raised: Boolean = false,
    val box: Boolean = false,
)

/** A station logo hidden for every video in a folder (one show): PictureAdjustment's LogoMask by names. */
/**
 * What looking at a video's sound found (IntroCredits, #480): where its intro runs and where its credits begin, each
 * null where nothing was found — kept even then, so the video is not looked at again. The intro is looked for first
 * (the entry's being there says so), the credits after ([creditsLooked]). Times in milliseconds.
 */
@Serializable
data class FoundSkip(
    val introStartMs: Long? = null,
    val introEndMs: Long? = null,
    val creditsStartMs: Long? = null,
    val creditsLooked: Boolean = false,
) {
    /** The intro found, or null. */
    val intro: com.hawkynt.mediadroyd.nfo.TimeRange?
        get() = if (introStartMs != null && introEndMs != null) {
            com.hawkynt.mediadroyd.nfo.TimeRange(introStartMs, introEndMs)
        } else {
            null
        }
}

@Serializable
data class LogoOverride(val corner: String, val size: Float, val mode: String)

/** A picture correction stored for one video or folder; see [PictureAdjustment] for the ranges. */
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
    /** PictureAdjustment.Colorize name: colour for a black-and-white video. */
    val colorize: String = "OFF",
    /** PictureAdjustment.ColourSystem name: a composite capture's colour system, kept per video or folder only. */
    val colourSystem: String = "AUTO",
)

/** How one output screen is set up (see [com.hawkynt.mediadroyd.player.ScreenSetup]); names, as kept. */
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
    var loudness: Float = 0f,                 // dB, -12..12
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
    var colorize: String = "OFF",             // PictureAdjustment.Colorize name: the household's colour (desktop)
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
    // DDColor's self-test where it passed, the same way ("GPU|256=…"); null before. Refused as too slow or wrong on
    // this device, colour is not offered again until the app or the model changes: the refusal is kept under
    // Colorizing.refusalKey, "" while there is none.
    var neuralColorizer: String? = null,
    var neuralColorizerRefusedFor: String = "",
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
    fun neuralMotionPassed(network: PictureAdjustment.Interpolation): NeuralInterpolation.Passed? =
        neuralMotion[network.name]?.let { NeuralInterpolation.Passed.decode(it) }

    /** What [choice]'s self-test found where it passed (a model of waifu2x, Real-CUGAN or RealSR); null otherwise. */
    fun neuralEnlargerPassed(choice: NeuralEnlarging.Choice): NeuralInterpolation.Passed? =
        neuralEnlargers[choice.key]?.let { NeuralInterpolation.Passed.decode(it) }

    /** What DDColor's self-test found where it passed; null otherwise. */
    val colorizerPassed: NeuralInterpolation.Passed?
        get() = neuralColorizer?.let { NeuralInterpolation.Passed.decode(it) }

    /** The upscalers' models on ncnn this household's device passed. */
    val neuralEnlargersOffered: Set<NeuralEnlarging.Choice>
        get() = neuralEnlargers.keys.mapNotNull { NeuralEnlarging.choice(it) }
            .filter { it.upscaler.isEnlarger && neuralEnlargerPassed(it) != null }.toSet()

    /** The neural smooth-motion choices this household's device passed. */
    val neuralMotionOffered: Set<PictureAdjustment.Interpolation>
        get() = PictureAdjustment.Interpolation.entries
            .filter { it.isNeural && neuralMotionPassed(it) != null }.toSet()
}

/**
 * Everything the app remembers, one model on every platform: the media folder and the sources, the watched marks and
 * resume points, the pictures and subtitle looks per video, the pictures per folder, the settings.
 */
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
    // Pictures per folder or share, by FolderPlace.key ("sourceId|folder"): what the videos in it start with.
    val folderPictures: MutableMap<String, PictureOverride> = mutableMapOf(),
    val audioDelays: MutableMap<String, Int> = mutableMapOf(), // lip-sync per audio output, in ms
    val sorts: MutableMap<String, String> = mutableMapOf(), // SortOrder per LibrarySection, by name
    var wallMode: String = "",                 // WallMode by name; "" is posters
    var filesSort: String = "",                // FileSort by name; "" is by name
    val subtitles: MutableMap<String, SubtitleOverride> = mutableMapOf(),
    val foundSkips: MutableMap<String, FoundSkip> = mutableMapOf(), // intros and credits found by their sound, by video
    var settings: Settings = Settings(),
    var window: String? = null,                // a desktop window's place: WindowPlace, "x,y,width,height,maximized"
)
