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
 * the phone). Refresh-rate matching, even frames, passthrough, HDR tone mapping, large-video scaling, the decoded
 * buffer, the subtitles' look and the audio language are the Android player's alone so far, and so are not shown. The default picture's choices are tried as the player tries them
 * (DesktopTrial), the screens are the monitors attached now (OutputScreen), each under the key the player keeps its
 * setup by; the test patterns are in the player's gear, over the video.
 */
class DesktopSettingsPlatform(private val store: DesktopStore, private val folder: File) : SettingsPlatform {
    override val offered: Set<PlatformSetting> = OFFERED

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
    override fun screens(): List<SetupScreen> = OutputScreen.all().map { SetupScreen(it.key, it.name) }

    companion object {
        /** The rows only some platforms act on that the desktop player does. */
        val OFFERED: Set<PlatformSetting> = setOf(
            PlatformSetting.SHADER_FOLDER,
            PlatformSetting.BINGE_WATCHING,
            PlatformSetting.SKIP_INTRO_CREDITS,
        )
    }
}
