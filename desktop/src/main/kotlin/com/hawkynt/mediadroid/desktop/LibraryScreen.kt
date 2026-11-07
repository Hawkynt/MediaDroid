package com.hawkynt.mediadroid.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawkynt.mediadroid.library.BrowseFilter
import com.hawkynt.mediadroid.library.Library
import com.hawkynt.mediadroid.library.LibrarySection
import com.hawkynt.mediadroid.library.MediaItem
import com.hawkynt.mediadroid.library.Movie
import com.hawkynt.mediadroid.library.Show
import com.hawkynt.mediadroid.library.Source
import com.hawkynt.mediadroid.library.SourceKind
import com.hawkynt.mediadroid.player.BingeQueue
import com.hawkynt.mediadroid.ui.BrowseScreen
import com.hawkynt.mediadroid.ui.DetailsScreen
import com.hawkynt.mediadroid.ui.FilesScreen
import com.hawkynt.mediadroid.ui.FiltersScreen
import com.hawkynt.mediadroid.ui.OnboardingScreen
import com.hawkynt.mediadroid.ui.PlacesScreen
import com.hawkynt.mediadroid.ui.SearchScreen
import com.hawkynt.mediadroid.ui.SourcesSection
import com.hawkynt.mediadroid.ui.SettingsScreen
import com.hawkynt.mediadroid.ui.platform.LocalSettingsPlatform
import com.hawkynt.mediadroid.ui.platform.SettingsPlatform
import com.hawkynt.mediadroid.ui.Sidebar
import com.hawkynt.mediadroid.ui.platform.LocalMediaArt
import com.hawkynt.mediadroid.ui.platform.LocalMediaFiles
import com.hawkynt.mediadroid.ui.platform.LocalSourceKeeper
import com.hawkynt.mediadroid.ui.platform.MediaArt
import com.hawkynt.mediadroid.ui.platform.MediaFiles
import com.hawkynt.mediadroid.ui.platform.SourceKeeper
import com.hawkynt.mediadroid.ui.playQueue
import com.hawkynt.mediadroid.ui.startOf
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
    onPlay: (List<Playing>, Int) -> Unit,
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
    onPlay: (List<Playing>, Int) -> Unit,
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
            else -> Walls(
                store = store,
                places = places,
                library = library,
                scanning = scanning,
                onRefresh = { generation++ },
                onPlay = onPlay,
                onFolder = changeFolder,
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

/** The shared walls and places beside the shared sidebar, as the Android app shows them. */
@Composable
private fun Walls(
    store: DesktopStore,
    places: DesktopLibrary,
    library: Library,
    scanning: Boolean,
    onRefresh: () -> Unit,
    onPlay: (List<Playing>, Int) -> Unit,
    onFolder: () -> Unit,
) {
    val state = store.state
    // MEDIADROID_SHOW=settings: Settings open from the start, past its gate (documentation and checks).
    val settingsShown = remember { System.getenv("MEDIADROID_SHOW") == "settings" }
    var screen by remember {
        mutableStateOf<Shown>(if (settingsShown) Shown.Settings else Shown.Browse(LibrarySection.MOVIES))
    }
    // A title's details over the walls, as on Android; Back returns to where it was opened.
    var details by remember { mutableStateOf<MediaItem?>(null) }
    var filter by remember { mutableStateOf(BrowseFilter()) }
    // Each wall keeps its own order, remembered across launches.
    var sorts by remember { mutableStateOf(LibrarySection.entries.associateWith { state.sortFor(it) }) }
    var searchQuery by remember { mutableStateOf("") }
    // Which section the Genres and Years places group; follows the wall last browsed.
    var placeSection by remember { mutableStateOf(LibrarySection.MOVIES) }
    // Scroll positions outlive a visit to a show's episodes, so Back lands where you were.
    val movieGrid = rememberLazyGridState()
    val showGrid = rememberLazyGridState()
    val searchGrid = rememberLazyGridState()
    val rowGrid = rememberLazyGridState()

    fun navigate(target: Shown) {
        screen = target
        if (target is Shown.Browse) placeSection = target.section
    }

    // Where a queue picks up is the Android app's rule (startOf): the file asked for, else one with a resume point,
    // else the first not yet watched.
    fun play(item: MediaItem, path: String?) {
        when (item) {
            is Movie -> {
                val queue = item.playQueue()
                onPlay(queue.map { Playing(item.title, it.videoPath) }, startOf(queue, path, state))
            }
            is Show -> {
                val queue = showQueue(item)
                val from = item.playQueue().let { it.getOrNull(startOf(it, path, state)) }?.videoPath
                onPlay(queue, queue.indexOfFirst { it.video == from }.coerceAtLeast(0))
            }
        }
    }

    fun open(item: MediaItem) {
        details = item
    }

    details?.let { item ->
        DetailsScreen(
            item = item,
            store = state,
            onBack = { details = null },
            onPlayMovie = { play(it, null) },
            onPlayShow = { show, episode -> play(show, episode?.videoPath) },
            onPlayTrailer = { movie ->
                movie.trailerPath?.let { onPlay(listOf(Playing("${movie.title} · Trailer", it)), 0) }
            },
        )
        return
    }

    Row(Modifier.fillMaxSize()) {
        Sidebar(
            expanded = state.data.sidebarExpanded ?: true,
            current = screen,
            scanning = scanning,
            onToggle = {
                state.data.sidebarExpanded = !(state.data.sidebarExpanded ?: true)
                state.save()
            },
            onNavigate = ::navigate,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxHeight(),
        )
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (val s = screen) {
                is Shown.Browse -> BrowseScreen(
                    library = library,
                    section = s.section,
                    store = state,
                    filter = filter.copy(sort = sorts.getValue(s.section)),
                    rootPath = store.libraryFolder ?: places.networkSources.joinToString { it.name },
                    row = s.row,
                    gridState = when {
                        s.row != null -> rowGrid
                        s.section == LibrarySection.MOVIES -> movieGrid
                        else -> showGrid
                    },
                    onOpen = ::open,
                    onPlay = ::play,
                    onClearFilter = { filter = BrowseFilter() },
                    onSeeAll = { navigate(Shown.Browse(s.section, it)) },
                )
                is Shown.Places -> PlacesScreen(
                    library = library,
                    store = state,
                    kind = s.kind,
                    section = placeSection,
                    onSectionChange = { placeSection = it },
                    onOpen = { section, placeFilter ->
                        filter = placeFilter
                        navigate(Shown.Browse(section))
                    },
                )
                is Shown.Files -> FilesScreen(
                    store = state,
                    roots = DesktopMediaFiles.roots(store),
                    dir = s.dir,
                    onOpen = { screen = Shown.Files(it) },
                    onPlay = { queue, start -> onPlay(queue.map { Playing(it.title, it.videoPath) }, start) },
                )
                Shown.Search -> SearchScreen(
                    library = library,
                    store = state,
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    gridState = searchGrid,
                    onOpen = ::open,
                )
                Shown.Filters -> FiltersScreen(
                    library = library,
                    filter = filter,
                    onFilterChange = { filter = it },
                    sorts = sorts,
                    onSortChange = { section, order ->
                        sorts = sorts + (section to order)
                        state.saveSort(section, order)
                    },
                )
                Shown.Settings -> SettingsScreen(
                    store = state,
                    onChangeFolder = onFolder,
                    onAddDeviceFolder = { addDeviceFolder(store, onRefresh) },
                    onSourcesChanged = onRefresh,
                    unlockedAtStart = settingsShown,
                )
                else -> Unit
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

/**
 * Every episode of [show] in the order a binge plays them (BingeQueue: seasons, then episodes), the watched ones
 * kept — the one picked plays, and the rest of the show after it.
 */
internal fun showQueue(show: Show): List<Playing> = BingeQueue.queue(show.seasons, skipWatched = false).map {
    Playing("${show.title} · S%02dE%02d".format(it.season, it.episode.number), it.episode.videoPath)
}

private fun chooseFolder(): String? {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Choose media folder"
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.path else null
}
