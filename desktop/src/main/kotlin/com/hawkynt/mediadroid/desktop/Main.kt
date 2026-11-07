package com.hawkynt.mediadroid.desktop

import com.hawkynt.mediadroid.ui.theme.MediaDroidScheme
import com.hawkynt.mediadroid.ui.theme.MediaDroidTheme
import com.hawkynt.mediadroid.ui.theme.MediaDroidTypography
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import com.hawkynt.mediadroid.library.Library
import java.io.File

/** What the window shows: the library, or the player on a queue of videos. */
sealed interface Screen {
    data object Library : Screen
    /** [autoAdvance]: the next video starts when one ends (a film's parts always, episodes with binge-watching). */
    data class Player(
        val queue: List<com.hawkynt.mediadroid.ui.Playable>,
        val index: Int,
        val autoAdvance: Boolean = true,
    ) : Screen
}

/** The window as it was left (WindowPlace), else 1280 × 800 in the middle of the screen. */
private fun windowState(saved: String?): WindowState {
    val place = WindowPlace.parse(saved) ?: return WindowState(size = DpSize(1280.dp, 800.dp))
    return WindowState(
        placement = if (place.maximized) WindowPlacement.Maximized else WindowPlacement.Floating,
        position = WindowPosition(place.x.dp, place.y.dp),
        size = DpSize(place.width.dp, place.height.dp),
    )
}

// Full screen is the player's, not a place to come back to: the window as it was before is kept then (null).
private fun describe(state: WindowState): String? {
    if (state.placement == WindowPlacement.Fullscreen) return null
    val p = state.position
    return WindowPlace(
        x = if (p.isSpecified) p.x.value else 0f,
        y = if (p.isSpecified) p.y.value else 0f,
        width = state.size.width.value,
        height = state.size.height.value,
        maximized = state.placement == WindowPlacement.Maximized,
    ).toString()
}

/**
 * Starts the app; a video file given on the command line (or by the system, opening a file with it) plays at once.
 * `MEDIADROID_SNAPSHOT=<file.png>` writes the window's own picture to that file after `MEDIADROID_SNAPSHOT_MS`
 * (default 4000) milliseconds — for documentation and checks, never the rest of the screen.
 */
fun main(args: Array<String>) = application {
    val folder = remember { AppFolder.current() }
    val store = remember { DesktopStore(folder) }
    // The places media lives — this computer's folder and the network sources — and the files behind them.
    val secrets = remember { DesktopSecrets.forThisSystem(folder) }
    val places = remember { DesktopLibrary(store, secrets) }
    // The sources' passwords, connections and the SMB servers on the local network, for the shared sources section.
    val keeper = remember { DesktopSourceKeeper(places, secrets) }
    // The videos' lengths, tracks and stills, read by FFmpeg once and kept in the app-data folder.
    val probe = remember { DesktopMediaProbe(places, folder) }
    // The Files browser's folders, this computer's and the network sources'.
    val files = remember { DesktopMediaFiles(places, probe) }
    // The walls' pictures: this computer's as they are, the network sources' copied once into the app-data folder.
    val art = remember { DesktopMediaArt(places, File(folder, "art"), probe) }
    // What Settings shows here, and the mpv shaders its default picture offers, read once now.
    val settings = remember {
        ShaderFolder.load(ShaderFolder.of(store.state.data.settings.shaderFolder, folder))
        DesktopSettingsPlatform(store, folder)
    }
    // The media keys and the system's now-playing panel, for the app's whole life (MPRIS, SMTC).
    val mediaKeys = remember { MediaKeys() }
    val state = remember { windowState(store.window) }
    val opened = remember { args.firstOrNull()?.let(::File)?.takeIf { it.isFile } }
    var screen by remember {
        mutableStateOf(
            opened?.let {
                Screen.Player(listOf(com.hawkynt.mediadroid.ui.Playable(it.nameWithoutExtension, null, it.path, null)), 0)
            } ?: Screen.Library,
        )
    }
    var library by remember { mutableStateOf<Library?>(null) }
    val icon = remember {
        Thread.currentThread().contextClassLoader.getResourceAsStream("mediadroid.png")
            ?.use { androidx.compose.ui.graphics.painter.BitmapPainter(androidx.compose.ui.res.loadImageBitmap(it)) }
    }
    val close = {
        describe(state)?.let { store.window = it }
        mediaKeys.close()
        exitApplication()
    }
    Window(onCloseRequest = close, title = "MediaDroid", state = state, icon = icon) {
        System.getenv("MEDIADROID_SNAPSHOT")?.let { target ->
            androidx.compose.runtime.LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(System.getenv("MEDIADROID_SNAPSHOT_MS")?.toLongOrNull() ?: 4_000)
                Snapshot.of(window, File(target))
            }
        }
        // The Android app's theme (:ui), one for both.
        MediaDroidTheme {
            when (val s = screen) {
                Screen.Library -> LibraryScreen(
                    store = store,
                    places = places,
                    art = art,
                    keeper = keeper,
                    files = files,
                    settings = settings,
                    library = library,
                    onLibrary = { library = it },
                    onPlay = { queue, index, autoAdvance -> screen = Screen.Player(queue, index, autoAdvance) },
                )
                is Screen.Player -> {
                    val player = @androidx.compose.runtime.Composable { native: NativePicture? ->
                        PlayerScreen(
                            store = store,
                            places = places,
                            mediaKeys = mediaKeys,
                            queue = s.queue,
                            startIndex = s.index,
                            autoAdvance = s.autoAdvance,
                            fullScreen = state.placement == WindowPlacement.Fullscreen,
                            screen = { OutputScreen.of(window) },
                            onFullScreen = {
                                state.placement = if (state.placement == WindowPlacement.Fullscreen) {
                                    WindowPlacement.Floating
                                } else {
                                    WindowPlacement.Fullscreen
                                }
                            },
                            onExit = {
                                state.placement = WindowPlacement.Floating
                                screen = Screen.Library
                            },
                            native = native,
                        )
                    }
                    // The picture shown by the stage in a window of its own inside this one, the player's controls in
                    // a see-through window over it (NativePicture); where that cannot be had, or the window's own
                    // picture is to be written (MEDIADROID_SNAPSHOT), the player draws the picture read back here.
                    val native = remember {
                        if (System.getenv("MEDIADROID_SNAPSHOT") != null) null else NativePicture.of(window)
                    }
                    if (native == null) {
                        player(null)
                    } else {
                        androidx.compose.foundation.layout.Box(
                            androidx.compose.ui.Modifier.fillMaxSize()
                                .background(Color.Black),
                        )
                        PictureOverlay(native, window) {
                            // The scheme without the theme's surface: the controls' window is see-through.
                            MaterialTheme(colorScheme = MediaDroidScheme, typography = MediaDroidTypography) {
                                player(native)
                            }
                        }
                    }
                }
            }
        }
    }
}
