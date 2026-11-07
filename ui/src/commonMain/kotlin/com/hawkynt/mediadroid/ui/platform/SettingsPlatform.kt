package com.hawkynt.mediadroid.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.player.ScreenSetup
import com.hawkynt.mediadroid.state.BUILT_IN_SCREEN
import com.hawkynt.mediadroid.ui.PictureTry
import com.hawkynt.mediadroid.ui.PictureVerdict

/**
 * The settings only some platforms act on. Settings shows a row for one of these only where its platform offers it
 * ([SettingsPlatform.offered]): a switch that changes nothing on this device is not shown. Every other row — sources,
 * video decoding, default speed, loudness, the default picture, folder pictures, screens, subtitles, languages,
 * children — every platform acts on.
 */
enum class PlatformSetting {
    /** The next episode starts when one ends. */
    BINGE_WATCHING,

    /** Intros and credits jumped over by chapters or an .edl file. */
    SKIP_INTRO_CREDITS,

    /** The screen switched to the film's frame rate. */
    MATCH_REFRESH_RATE,

    /** The film run a little faster or slower so every frame stays on screen equally long. */
    EVEN_FRAMES,

    /** Surround sound handed untouched to an AV receiver. */
    AUDIO_PASSTHROUGH,

    /** HDR brought down by the app itself where the screen cannot show it. */
    HDR_TONE_MAPPING,

    /** How a video larger than 1080p is brought down before the picture filters. */
    LARGE_VIDEO,

    /** How many decoded pictures are kept ahead. */
    DECODED_BUFFER,

    /** The folder of mpv user shaders the default picture offers. */
    SHADER_FOLDER,

    /** The subtitles' size, height and box. */
    SUBTITLE_LOOK,

    /** The audio language a video starts in. */
    AUDIO_LANGUAGE,

    /** The test patterns shown from Settings → Screens. */
    TEST_PATTERNS,

    /** A projector's keystone corrected with the grid on its screen. */
    KEYSTONE_GRID,
}

/** An output screen whose setup Settings → Screens keeps: its [key] (StateStore.screenSetup) and its [name]. */
data class SetupScreen(val key: String, val name: String)

/** What the Settings screen needs of its platform: which rows it acts on, and the parts only it can do. */
interface SettingsPlatform {
    /** The rows of [PlatformSetting] this platform acts on. */
    val offered: Set<PlatformSetting> get() = emptySet()

    /** This device's memory in bytes, the decoded pictures' ceiling (DecodedBuffer.ceilingBytes) comes from. */
    val memoryBytes: Long get() = 0

    /** The check behind every choice of deinterlacing, upscaling and smooth motion in the default picture. */
    val pictureCheck: PictureTry get() = { _, _ -> PictureVerdict(true) }

    /** Whether a folder of mpv shaders is kept: picked once, or the platform's own. */
    val hasShaderFolder: Boolean get() = false

    /**
     * Picks or opens the folder of mpv shaders and reads its shaders (MpvShaders); [then] runs once they are read. The
     * row's button runs what this returns.
     */
    @Composable
    fun rememberShaderFolder(then: () -> Unit): () -> Unit = {}

    /** The screens with a setup of their own: this device's own first, then those attached now. */
    @Composable
    fun screens(): List<SetupScreen> = listOf(SetupScreen(BUILT_IN_SCREEN, "This device's screen"))

    /** The test patterns over the whole screen, drawn with [picture] and [setup], until [onClose]. */
    @Composable
    fun TestPatterns(picture: PictureAdjustment, setup: ScreenSetup, onClose: () -> Unit) {
        onClose()
    }

    /**
     * The grid on the screen kept under [key], drawn with [picture] and [setup], and the keystone's controls beside
     * it, every change handed to [keep], until [onClose].
     */
    @Composable
    fun KeystoneGrid(
        key: String,
        picture: PictureAdjustment,
        setup: ScreenSetup,
        keep: (ScreenSetup) -> Unit,
        onClose: () -> Unit,
    ) {
        onClose()
    }

    /** No row beyond those every platform acts on. */
    object None : SettingsPlatform
}

/** The platform's [SettingsPlatform]; each app provides its own at the root of its screens. */
val LocalSettingsPlatform = staticCompositionLocalOf<SettingsPlatform> { SettingsPlatform.None }
