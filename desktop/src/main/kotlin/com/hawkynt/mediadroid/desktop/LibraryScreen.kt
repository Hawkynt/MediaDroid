package com.hawkynt.mediadroid.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hawkynt.mediadroid.library.Library
import com.hawkynt.mediadroid.library.LibrarySection
import com.hawkynt.mediadroid.library.Source
import com.hawkynt.mediadroid.library.SourceKind
import com.hawkynt.mediadroid.ui.Playable
import com.hawkynt.mediadroid.ui.OnboardingScreen
import com.hawkynt.mediadroid.ui.SourcesSection
import com.hawkynt.mediadroid.ui.platform.LocalSettingsPlatform
import com.hawkynt.mediadroid.ui.platform.SettingsPlatform
import com.hawkynt.mediadroid.ui.LibraryShell
import com.hawkynt.mediadroid.ui.rememberShellState
import com.hawkynt.mediadroid.ui.platform.LocalMediaArt
import com.hawkynt.mediadroid.ui.platform.LocalMediaFiles
import com.hawkynt.mediadroid.ui.platform.LocalSourceKeeper
import com.hawkynt.mediadroid.ui.platform.MediaArt
import com.hawkynt.mediadroid.ui.platform.MediaFiles
import com.hawkynt.mediadroid.ui.platform.SourceKeeper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.swing.JFileChooser
import com.hawkynt.mediadroid.ui.Screen as Shown

/**
 * The library: pick a folder once, then the walls both apps share (:ui) — Movies and TV Shows with their home rows,
 * Genres and Years, the Files browser, Search and Filters, beside the sidebar; a title opens its details. The media
 * folder and the network sources are under Settings.
 */
@Composable
fun LibraryScreen(
    store: DesktopStore,
    places: DesktopLibrary,
    art: MediaArt,
    keeper: SourceKeeper,
    files: MediaFiles,
    settings: SettingsPlatform,
    library: Library?,
    onLibrary: (Library) -> Unit,
    onPlay: (List<Playable>, Int, Boolean) -> Unit,
) {
    CompositionLocalProvider(
        LocalMediaArt provides art,
        LocalSourceKeeper provides keeper,
        LocalMediaFiles provides files,
        LocalSettingsPlatform provides settings,
    ) {
        Library(store, places, library, onLibrary, onPlay)
    }
}

@Composable
private fun Library(
    store: DesktopStore,
    places: DesktopLibrary,
    library: Library?,
    onLibrary: (Library) -> Unit,
    onPlay: (List<Playable>, Int, Boolean) -> Unit,
) {
    var folder by remember { mutableStateOf(store.libraryFolder) }
    var scanning by remember { mutableStateOf(false) }
    // MEDIADROID_SHOW=sources: the network sources open from the start (documentation and checks).
    var showSources by remember { mutableStateOf(System.getenv("MEDIADROID_SHOW") == "sources") }
    // Moves on whenever the places change: the library is scanned again.
    var generation by remember { mutableIntStateOf(0) }
    // A typed media folder that is not there, said on the first start.
    var typedWrong by remember { mutableStateOf<String?>(null) }
    val nothingYet = folder == null && places.networkSources.isEmpty()
    // MEDIADROID_SHOW=settings: Settings open from the start, past its gate (documentation and checks).
    val settingsShown = remember { System.getenv("MEDIADROID_SHOW") == "settings" }
    // Where the app is (the shared shell's state), kept across a folder chosen anew.
    val shell = rememberShellState(
        store.state,
        start = if (settingsShown) Shown.Settings else Shown.Browse(LibrarySection.MOVIES),
    )
    LaunchedEffect(generation) {
        if (folder == null && places.networkSources.isEmpty()) return@LaunchedEffect
        if (library != null && generation == 0) return@LaunchedEffect
        scanning = true
        onLibrary(withContext(Dispatchers.IO) { places.scan() })
        scanning = false
    }
    val changeFolder: () -> Unit = {
        chooseFolder()?.let {
            store.libraryFolder = it
            folder = it
            generation++
        }
    }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when {
            nothingYet -> OnboardingScreen(
                hasAccess = true,
                currentPath = "",
                scanning = false,
                scanError = typedWrong,
                canCancel = false,
                onGrantAccess = {},
                onPickFolder = changeFolder,
                onUsePath = { typed ->
                    val path = typed.trim()
                    if (File(path).isDirectory) {
                        store.libraryFolder = path
                        folder = path
                        generation++
                    } else {
                        typedWrong = "There is no folder at $path."
                    }
                },
                onCancel = {},
                onNetworkSource = { showSources = true },
            )
            library == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            // The walls and places beside the sidebar, as the Android app shows them (LibraryShell).
            else -> LibraryShell(
                state = shell,
                library = library,
                store = store.state,
                scanning = scanning,
                rootPath = store.libraryFolder ?: places.networkSources.joinToString { it.name },
                roots = DesktopMediaFiles.roots(store),
                onRefresh = { generation++ },
                onPlay = { onPlay(it.queue, it.startIndex, it.autoAdvance) },
                onChangeFolder = changeFolder,
                onAddDeviceFolder = { addDeviceFolder(store) { generation++ } },
                settingsUnlockedAtStart = settingsShown,
            )
        }
        if (showSources) {
            Column(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                    .verticalScroll(rememberScrollState()).padding(24.dp),
            ) {
                Sources(store, onFolder = changeFolder, onChanged = { generation++ })
                Spacer(Modifier.height(16.dp))
                Button(onClick = { showSources = false }) { Text("Done") }
            }
        }
    }
}

/**
 * Where the library is read from, as Android's Settings shows it (SourcesSection): the media folder, further folders
 * of this computer and the network sources, each added, changed or removed from here.
 */
@Composable
private fun Sources(store: DesktopStore, onFolder: () -> Unit, onChanged: () -> Unit) {
    SourcesSection(
        store = store.state,
        unreachable = emptySet(),
        onChangeMainFolder = onFolder,
        onAddDeviceFolder = { addDeviceFolder(store, onChanged) },
        onChanged = onChanged,
    )
}

/** A further folder of this computer, chosen now, added beside the media folder unless it is there already. */
private fun addDeviceFolder(store: DesktopStore, onChanged: () -> Unit) {
    chooseFolder()?.let { path ->
        val state = store.state
        if (state.sources.none { it.kind == SourceKind.LOCAL && it.path == path }) {
            val name = File(path).name.ifEmpty { path }
            state.saveSources(state.sources + Source.local(UUID.randomUUID().toString(), name, path))
            onChanged()
        }
    }
}

private fun chooseFolder(): String? {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Choose media folder"
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.path else null
}
