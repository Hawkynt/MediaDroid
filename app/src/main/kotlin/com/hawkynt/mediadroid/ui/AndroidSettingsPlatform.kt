package com.hawkynt.mediadroid.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.hawkynt.mediadroid.data.MpvShaderFolder
import com.hawkynt.mediadroid.data.screenKey
import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.player.ScreenSetup
import com.hawkynt.mediadroid.state.BUILT_IN_SCREEN
import com.hawkynt.mediadroid.state.StateStore
import com.hawkynt.mediadroid.ui.platform.PlatformSetting
import com.hawkynt.mediadroid.ui.platform.SettingsPlatform
import com.hawkynt.mediadroid.ui.platform.SetupScreen

/**
 * Settings on Android: every row — the player here binges, skips intros, matches the TV's refresh rate, hands surround
 * sound through, tone-maps HDR, brings large video down, keeps decoded pictures ahead, draws subtitles its own way and
 * picks the audio language. The mpv shaders' folder is picked through the system's folder picker and its permission
 * kept; the screens are this device's and the presentation display attached now; the test patterns run on the stage.
 */
class AndroidSettingsPlatform(private val context: Context, private val store: StateStore) : SettingsPlatform {
    override val offered: Set<PlatformSetting> = PlatformSetting.entries.toSet()

    override val memoryBytes: Long
        get() = ActivityManager.MemoryInfo()
            .also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
            .totalMem

    override val pictureCheck: PictureTry = pictureCheck(context, store)

    override val hasShaderFolder: Boolean get() = store.data.settings.shaderFolder.isNotEmpty()

    @Composable
    override fun rememberShaderFolder(then: () -> Unit): () -> Unit {
        val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            uri ?: return@rememberLauncherForActivityResult
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            store.data.settings.shaderFolder = uri.toString()
            store.save()
            MpvShaderFolder.load(context, uri.toString())
            then()
        }
        return { pick.launch(null) }
    }

    @Composable
    override fun screens(): List<SetupScreen> {
        val external = rememberExternalDisplay()
        return listOfNotNull(
            SetupScreen(BUILT_IN_SCREEN, "This device's screen"),
            external?.let { SetupScreen(screenKey(it), it.name) },
        )
    }

    @Composable
    override fun TestPatterns(picture: PictureAdjustment, setup: ScreenSetup, onClose: () -> Unit) =
        TestPatternScreen(picture, setup, onClose = onClose)

    // This device's screen shows the grid itself; a projector attached as a second screen shows it there.
    @Composable
    override fun KeystoneGrid(
        key: String,
        picture: PictureAdjustment,
        setup: ScreenSetup,
        keep: (ScreenSetup) -> Unit,
        onClose: () -> Unit,
    ) {
        val external = rememberExternalDisplay()?.takeIf { screenKey(it) == key }
        if (key == BUILT_IN_SCREEN || external == null) {
            TestPatternScreen(picture, setup, keystone = keep, onClose = onClose)
        } else {
            KeystoneOnProjector(external, picture, setup, keep, onClose)
        }
    }
}

/**
 * The keystone set with the grid on a projector attached as [display]: the grid there (PatternPresentation), drawn with
 * [setup] as it changes, and the keystone's controls on the phone, each change handed to [keep].
 */
@Composable
private fun KeystoneOnProjector(
    display: android.view.Display,
    picture: PictureAdjustment,
    setup: ScreenSetup,
    keep: (ScreenSetup) -> Unit,
    onClose: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var live by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(setup) }
    val presentation = androidx.compose.runtime.remember(display) {
        PatternPresentation(context, display, picture, setup)
    }
    androidx.compose.runtime.DisposableEffect(presentation) {
        presentation.show()
        onDispose { presentation.release() }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        KeystoneControls(
            live,
            onChange = {
                live = it
                presentation.show(it)
                keep(it)
            },
            onDone = onClose,
        )
    }
}
