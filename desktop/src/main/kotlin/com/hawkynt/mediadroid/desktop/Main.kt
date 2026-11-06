package com.hawkynt.mediadroid.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
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
    data class Player(val queue: List<Playing>, val index: Int) : Screen
}

/** A video to play, and the title to show over it. */
data class Playing(val title: String, val video: String)

/** The app's colours: the Android app's dark navy and amber. */
val MediaDroidColors = darkColorScheme(
    primary = Color(0xFFFFB74D),
    onPrimary = Color(0xFF1A1A2E),
    background = Color(0xFF12121F),
    surface = Color(0xFF1A1A2E),
    onSurface = Color(0xFFEDEDF5),
    onBackground = Color(0xFFEDEDF5),
)

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
    val places = remember {
        DesktopLibrary(store, DesktopSecrets.forThisSystem(folder)).also { places ->
            Art.reader = { location -> places.file(location).open().use { it.readBytes() } }
        }
    }
    // The media keys and the system's now-playing panel, for the app's whole life (MPRIS, SMTC).
    val mediaKeys = remember { MediaKeys() }
    val state = remember { windowState(store.window) }
    val opened = remember { args.firstOrNull()?.let(::File)?.takeIf { it.isFile } }
    var screen by remember {
        mutableStateOf(
            opened?.let { Screen.Player(listOf(Playing(it.nameWithoutExtension, it.path)), 0) } ?: Screen.Library,
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
        MaterialTheme(colorScheme = MediaDroidColors) {
            when (val s = screen) {
                Screen.Library -> LibraryScreen(
                    store = store,
                    places = places,
                    library = library,
                    onLibrary = { library = it },
                    onPlay = { queue, index -> screen = Screen.Player(queue, index) },
                )
                is Screen.Player -> PlayerScreen(
                    store = store,
                    places = places,
                    mediaKeys = mediaKeys,
                    queue = s.queue,
                    startIndex = s.index,
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
                )
            }
        }
    }
}
