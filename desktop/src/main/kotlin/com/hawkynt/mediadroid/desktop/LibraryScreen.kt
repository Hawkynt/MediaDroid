package com.hawkynt.mediadroid.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawkynt.mediadroid.library.Library
import com.hawkynt.mediadroid.library.LibraryQuery
import com.hawkynt.mediadroid.library.MediaItem
import com.hawkynt.mediadroid.library.Movie
import com.hawkynt.mediadroid.library.Show
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.swing.JFileChooser

/** The library: pick a folder once, then every movie and show as a wall of covers. */
@Composable
fun LibraryScreen(
    store: DesktopStore,
    places: DesktopLibrary,
    library: Library?,
    onLibrary: (Library) -> Unit,
    onPlay: (List<Playing>, Int) -> Unit,
) {
    var folder by remember { mutableStateOf(store.libraryFolder) }
    var scanning by remember { mutableStateOf(false) }
    var openShow by remember { mutableStateOf<Show?>(null) }
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
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when {
            nothingYet -> Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Your movies and shows are already organized.", fontSize = 24.sp, color = Color.White)
                Spacer(Modifier.padding(8.dp))
                Button(onClick = {
                    chooseFolder()?.let {
                        store.libraryFolder = it
                        folder = it
                        generation++
                    }
                }) {
                    Icon(Icons.Default.Folder, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Choose media folder", fontSize = 18.sp)
                }
                TextButton(onClick = { showSources = true }) { Text("Or add a network source", fontSize = 16.sp) }
            }
            scanning || library == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            openShow != null -> ShowScreen(store, openShow!!, onBack = { openShow = null }, onPlay = onPlay)
            else -> Wall(store, library, onOpen = { item ->
                when (item) {
                    is Movie -> onPlay(listOf(Playing(item.title, item.videoPath)), 0)
                    is Show -> openShow = item
                }
            }, onSources = { showSources = true }, onFolder = {
                chooseFolder()?.let {
                    store.libraryFolder = it
                    folder = it
                    generation++
                }
            })
        }
        if (showSources) {
            SourcesPanel(places, onChanged = { generation++ }, onClose = { showSources = false })
        }
    }
}

@Composable
private fun Wall(
    store: DesktopStore,
    library: Library,
    onOpen: (MediaItem) -> Unit,
    onSources: () -> Unit,
    onFolder: () -> Unit,
) {
    val all: List<MediaItem> = remember(library) { (library.movies + library.shows).sortedBy { it.title.lowercase() } }
    var query by remember { mutableStateOf("") }
    var genres by remember { mutableStateOf(emptySet<String>()) }
    val facets = remember(all) { LibraryQuery.genreFacets(all) }
    val items = remember(all, query, genres) { LibraryQuery.filterByGenres(LibraryQuery.search(all, query), genres) }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "MediaDroid",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(24.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search titles") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                modifier = Modifier.width(360.dp),
            )
            Spacer(Modifier.weight(1f))
            Text("${library.movies.size} movies · ${library.shows.size} shows", color = Color.Gray)
            TextButton(onClick = onSources) { Text("Network sources") }
            TextButton(onClick = onFolder) {
                Text(if (store.libraryFolder == null) "Choose folder" else "Change folder")
            }
        }
        if (facets.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                items(facets) { (genre, count) ->
                    FilterChip(
                        selected = genre in genres,
                        onClick = { genres = if (genre in genres) genres - genre else genres + genre },
                        label = { Text("$genre · $count") },
                    )
                }
            }
        }
        Spacer(Modifier.padding(8.dp))
        if (items.isEmpty()) Text("Nothing matches.", color = Color.Gray, fontSize = 18.sp)
        LazyVerticalGrid(GridCells.Adaptive(170.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(items, key = { it.key }) { item ->
                Cover(item, watched = item is Movie && store.watched(item.videoPath)) { onOpen(item) }
            }
        }
    }
}

@Composable
private fun Cover(item: MediaItem, watched: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2A2A40)),
        ) {
            val art = Art.load(item.posterPath)
            if (art != null) {
                Image(art, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Text(item.title, Modifier.align(Alignment.Center).padding(12.dp), color = Color.White, fontSize = 18.sp)
            }
            if (watched) Icon(Icons.Default.CheckCircle, "Watched", Modifier.align(Alignment.TopEnd).padding(6.dp),
                tint = MaterialTheme.colorScheme.primary)
        }
        Text(item.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp,
            modifier = Modifier.padding(top = 6.dp))
        item.year?.let { Text("$it", color = Color.Gray, fontSize = 13.sp) }
    }
}

@Composable
private fun ShowScreen(store: DesktopStore, show: Show, onBack: () -> Unit, onPlay: (List<Playing>, Int) -> Unit) {
    val episodes = show.seasons.flatMap { season -> season.episodes.map { season.number to it } }
    val queue = episodes.map { (season, e) ->
        Playing("${show.title} · S%02dE%02d".format(season, e.number), e.videoPath)
    }
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
