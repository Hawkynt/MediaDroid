package com.hawkynt.mediadroid.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawkynt.mediadroid.library.BrowseFilter
import com.hawkynt.mediadroid.library.Library
import com.hawkynt.mediadroid.library.LibrarySection
import com.hawkynt.mediadroid.library.MediaItem
import com.hawkynt.mediadroid.library.Movie
import com.hawkynt.mediadroid.library.Show
import com.hawkynt.mediadroid.player.BingeQueue
import com.hawkynt.mediadroid.ui.BrowseScreen
import com.hawkynt.mediadroid.ui.FiltersScreen
import com.hawkynt.mediadroid.ui.PlacesScreen
import com.hawkynt.mediadroid.ui.SearchScreen
import com.hawkynt.mediadroid.ui.Sidebar
import com.hawkynt.mediadroid.ui.platform.LocalMediaArt
import com.hawkynt.mediadroid.ui.platform.MediaArt
import com.hawkynt.mediadroid.ui.playQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.swing.JFileChooser
import com.hawkynt.mediadroid.ui.Screen as Shown

/**
 * The library: pick a folder once, then the walls both apps share (:ui) — Movies and TV Shows with their home rows,
 * Genres and Years, Search and Filters, beside the sidebar. A movie plays at once, a show opens its episodes; the
 * media folder and the network sources are under Settings.
 */
@Composable
fun LibraryScreen(
    store: DesktopStore,
    places: DesktopLibrary,
    art: MediaArt,
    library: Library?,
    onLibrary: (Library) -> Unit,
    onPlay: (List<Playing>, Int) -> Unit,
) {
    CompositionLocalProvider(LocalMediaArt provides art) {
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
            nothingYet -> Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Your movies and shows are already organized.", fontSize = 24.sp, color = Color.White)
                Spacer(Modifier.padding(8.dp))
                Button(onClick = changeFolder) {
                    Icon(Icons.Default.Folder, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Choose media folder", fontSize = 18.sp)
                }
                TextButton(onClick = { showSources = true }) { Text("Or add a network source", fontSize = 16.sp) }
            }
            library == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            else -> Walls(
                store = store,
                places = places,
                library = library,
                scanning = scanning,
                onRefresh = { generation++ },
                onPlay = onPlay,
                onSources = { showSources = true },
                onFolder = changeFolder,
            )
        }
        if (showSources) {
            SourcesPanel(places, onChanged = { generation++ }, onClose = { showSources = false })
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
    onSources: () -> Unit,
    onFolder: () -> Unit,
) {
    val state = store.state
    var screen by remember { mutableStateOf<Shown>(Shown.Browse(LibrarySection.MOVIES)) }
    var openShow by remember { mutableStateOf<Show?>(null) }
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

    openShow?.let { show ->
        ShowScreen(store, show, onBack = { openShow = null }, onPlay = onPlay)
        return
    }

    fun navigate(target: Shown) {
        screen = target
        if (target is Shown.Browse) placeSection = target.section
    }

    // A movie plays from the file asked for (else its first), a show from its episode asked for (else it opens).
    fun play(item: MediaItem, path: String?) {
        when (item) {
            is Movie -> {
                val queue = item.playQueue()
                onPlay(queue.map { Playing(item.title, it.videoPath) }, queue.indexOfFirst { it.videoPath == path }
                    .coerceAtLeast(0))
            }
            is Show -> {
                val queue = showQueue(item)
                val start = queue.indexOfFirst { it.video == path }
                if (start >= 0) onPlay(queue, start) else openShow = item
            }
        }
    }

    fun open(item: MediaItem) = when (item) {
        is Movie -> play(item, null)
        is Show -> openShow = item
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
            files = false,
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
                Shown.Settings -> MediaPlaces(store, places, onSources = onSources, onFolder = onFolder)
                else -> Unit
            }
        }
    }
}

/** Where the library is read from: the media folder and the network sources, each changed from here. */
@Composable
private fun MediaPlaces(store: DesktopStore, places: DesktopLibrary, onSources: () -> Unit, onFolder: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Media folder", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(store.libraryFolder ?: "None chosen yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onFolder) {
            Text(if (store.libraryFolder == null) "Choose folder" else "Change folder")
        }
        Spacer(Modifier.padding(4.dp))
        Text("Network sources", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        val sources = places.networkSources
        Text(
            if (sources.isEmpty()) "None added yet." else sources.joinToString { it.name },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onSources) { Text("Network sources") }
    }
}

/**
 * Every episode of [show] in the order a binge plays them (BingeQueue: seasons, then episodes), the watched ones
 * kept — the one picked plays, and the rest of the show after it.
 */
internal fun showQueue(show: Show): List<Playing> = BingeQueue.queue(show.seasons, skipWatched = false).map {
    Playing("${show.title} · S%02dE%02d".format(it.season, it.episode.number), it.episode.videoPath)
}

@Composable
private fun ShowScreen(store: DesktopStore, show: Show, onBack: () -> Unit, onPlay: (List<Playing>, Int) -> Unit) {
    val episodes = BingeQueue.queue(show.seasons, skipWatched = false).map { it.season to it.episode }
    val queue = showQueue(show)
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Text(show.title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        show.plot?.let { Text(it, color = Color.LightGray, modifier = Modifier.padding(vertical = 12.dp)) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(episodes.indices.toList()) { i ->
                val (season, episode) = episodes[i]
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF22223A))
                    .clickable { onPlay(queue, i) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("S%02d E%02d".format(season, episode.number), color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(96.dp))
                    Text(
                        episode.title ?: "Episode ${episode.number}",
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                    )
                    if (store.watched(episode.videoPath)) Icon(Icons.Default.CheckCircle, "Watched",
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
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
