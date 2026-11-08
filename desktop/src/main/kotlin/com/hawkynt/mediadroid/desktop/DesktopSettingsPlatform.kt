package com.hawkynt.mediadroid.desktop

import androidx.compose.runtime.Composable
import com.hawkynt.mediadroid.engine.desktop.DesktopNeural
import com.hawkynt.mediadroid.player.PictureTrial
import com.hawkynt.mediadroid.ui.PictureTry
import com.hawkynt.mediadroid.ui.platform.PlatformSetting
import com.hawkynt.mediadroid.ui.platform.SettingsPlatform
import com.hawkynt.mediadroid.ui.platform.SetupScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser

/**
 * Settings on this computer. The rows only some platforms act on show where the desktop player does: the folder of
 * mpv shaders (ShaderFolder), binge-watching and skipping intros and credits (the player's PlayerSession, shared with
 * the phone), even frames (EvenFrames: at the monitor's refresh as it is), the subtitles' look (the player sizes,
 * raises and boxes its lines by it) and the audio language (the engine starts a video in it).
 *
 * Match the refresh rate shows where the system lets the app switch a monitor's mode ([displays]: Windows, X11 with
 * xrandr — RefreshMatcher); under Wayland it stays hidden, the compositor keeping the outputs' modes to itself, and
 * Even frames keeps the frames even at the mode the monitor has.
 *
 * Not shown, because on a desktop they would change nothing:
 * - Audio passthrough: the sound goes out as PCM through the system's mixer (AudioSink); handing AC-3 or DTS untouched
 *   to a receiver needs an exclusive digital output (IEC 61937), which the system's sound API in use does not offer.
 * - HDR tone mapping: the desktop shows SDR, so the stage always brings HDR down; there is no HDR screen to leave it
 *   to, and a switch to leave it alone would only wash the picture out.
 * - Large videos: the desktop's stage filters a picture at its own size on a graphics card that keeps up with it; the
 *   dry run refuses a choice that does not.
 * - The decoded buffer: the desktop sizes its queue of decoded pictures itself, by the sound's delay, within a memory
 *   ceiling of its own (DesktopPlaybackEngine.PICTURE_BYTES_AHEAD).
 *
 * The default picture's choices are tried as the player tries them (DesktopTrial), the screens are the monitors
 * attached now (OutputScreen), each under the key the player keeps its setup by; the test patterns are in the
 * player's gear, over the video.
 */
class DesktopSettingsPlatform(
    private val store: DesktopStore,
    private val folder: File,
    displays: DisplaySystem,
) : SettingsPlatform {
    override val offered: Set<PlatformSetting> =
        if (displays.unavailable == null) OFFERED + PlatformSetting.MATCH_REFRESH_RATE else OFFERED

    private val trial by lazy { DesktopTrial(store, DesktopNeural(File(folder, "models"))) }

    override val pictureCheck: PictureTry = { picture, say ->
        withContext(Dispatchers.Default) { PictureTrial.check(picture, trial, say) }
    }

    // The app-data folder's shaders/ is always there, when nothing else was picked.
    override val hasShaderFolder: Boolean get() = true

    @Composable
    override fun rememberShaderFolder(then: () -> Unit): () -> Unit = {
        val settings = store.state.data.settings
        val chooser = JFileChooser(ShaderFolder.of(settings.shaderFolder, folder)).apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = "Choose the folder of mpv shaders"
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            settings.shaderFolder = chooser.selectedFile.path
            store.state.save()
        }
        ShaderFolder.load(ShaderFolder.of(settings.shaderFolder, folder))
        then()
    }

    @Composable
    override fun rememberBackupSaver(done: (Boolean?) -> Unit): (name: String, text: String) -> Unit = { name, text ->
        val chooser = JFileChooser().apply {
            dialogTitle = "Keep the backup"
            selectedFile = java.io.File(name)
        }
        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
            done(runCatching { chooser.selectedFile.writeText(text) }.isSuccess)
        } else {
            done(null)
        }
    }

    @Composable
    override fun rememberBackupOpener(read: (String?) -> Unit): () -> Unit = {
        val chooser = JFileChooser().apply { dialogTitle = "Choose a backup" }
        read(
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                runCatching { chooser.selectedFile.readText() }.getOrNull()
            } else {
                null
            },
        )
    }

    @Composable
    override fun screens(): List<SetupScreen> = OutputScreen.all().map { SetupScreen(it.key, it.name) }

    companion object {
        /** The rows only some platforms act on that the desktop player does wherever it runs. */
        val OFFERED: Set<PlatformSetting> = setOf(
            PlatformSetting.SHADER_FOLDER,
            PlatformSetting.BINGE_WATCHING,
            PlatformSetting.SKIP_INTRO_CREDITS,
            PlatformSetting.EVEN_FRAMES,
            PlatformSetting.SUBTITLE_LOOK,
            PlatformSetting.AUDIO_LANGUAGE,
        )
    }
}
