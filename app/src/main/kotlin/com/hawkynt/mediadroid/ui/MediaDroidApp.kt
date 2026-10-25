package com.hawkynt.mediadroid.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawkynt.mediadroid.data.StateStore
import com.hawkynt.mediadroid.library.Episode
import com.hawkynt.mediadroid.library.Library
import com.hawkynt.mediadroid.library.LibraryScanner
import com.hawkynt.mediadroid.library.MediaItem
import com.hawkynt.mediadroid.library.Movie
import com.hawkynt.mediadroid.library.Show
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One thing the player can play, with enough context to label itself. */
data class Playable(
    val title: String,
    val subtitle: String?,
    val videoPath: String,
    val posterPath: String?,
)

sealed interface Screen {
    data object Movies : Screen
    data object Shows : Screen
    data object Search : Screen
    data object Filters : Screen
    data object Settings : Screen
    data class Details(val item: MediaItem) : Screen
    data class Player(val queue: List<Playable>, val startIndex: Int) : Screen
}

enum class Sort { TITLE, YEAR_NEW, YEAR_OLD }

data class FilterState(
    val genres: Set<String> = emptySet(),
    val decade: Int? = null,
    val sort: Sort = Sort.TITLE,
)

@Composable
fun MediaDroidApp(store: StateStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hasAccess by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasAccess = Environment.isExternalStorageManager()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var rootPath by remember { mutableStateOf(store.data.rootPath) }
    var library by remember { mutableStateOf<Library?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var screen by remember { mutableStateOf<Screen>(Screen.Movies) }
    var lastBrowse by remember { mutableStateOf<Screen>(Screen.Movies) }
    var filter by remember { mutableStateOf(FilterState()) }

    if (!hasAccess || library == null) {
        OnboardingScreen(
            hasAccess = hasAccess,
            rootPath = rootPath,
            onRootPathChange = { rootPath = it },
            scanning = scanning,
            scanError = scanError,
            onGrantAccess = {
                val intent = runCatching {
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    )
                }.getOrElse { Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION) }
                context.startActivity(intent)
            },
            onReturnCheck = { hasAccess = Environment.isExternalStorageManager() },
            onScan = {
                scanError = null
                scope.launch {
                    scanning = true
                    val result = withContext(Dispatchers.IO) {
                        runCatching { LibraryScanner.scan(File(rootPath)) }
                    }
                    store.data.rootPath = rootPath
                    store.save()
                    result.onSuccess {
                        library = it
                        screen = Screen.Movies
                    }.onFailure { scanError = "Could not scan $rootPath — ${it.message}" }
                    scanning = false
                }
            },
        )
        return
    }

    val lib = library!!

    fun openDetails(item: MediaItem) { screen = Screen.Details(item) }
    fun playMovie(movie: Movie) {
        screen = Screen.Player(listOf(movie.playable()), 0)
    }
    fun playShow(show: Show, fromEpisode: Episode? = null) {
        val queue = show.playQueue()
        if (queue.isEmpty()) return
        val index = fromEpisode?.let { target -> queue.indexOfFirst { it.videoPath == target.videoPath } }?.takeIf { it >= 0 } ?: 0
        screen = Screen.Player(queue, index)
    }

    fun browseTo(s: Screen) {
        screen = s
        if (s is Screen.Movies || s is Screen.Shows) lastBrowse = s
    }

    val showBar = screen is Screen.Movies || screen is Screen.Shows || screen is Screen.Search ||
        screen is Screen.Filters || screen is Screen.Settings
    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    NavigationBarItem(
                        selected = screen is Screen.Movies,
                        onClick = { browseTo(Screen.Movies) },
                        icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                        label = { Text("Movies", fontSize = 16.sp) },
                    )
                    NavigationBarItem(
                        selected = screen is Screen.Shows,
                        onClick = { browseTo(Screen.Shows) },
                        icon = { Icon(Icons.Filled.List, contentDescription = null) },
                        label = { Text("Shows", fontSize = 16.sp) },
                    )
                    NavigationBarItem(
                        selected = screen is Screen.Search,
                        onClick = { screen = Screen.Search },
                        icon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        label = { Text("Search", fontSize = 16.sp) },
                    )
                    NavigationBarItem(
                        selected = screen is Screen.Filters,
                        onClick = { screen = Screen.Filters },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text("Filters", fontSize = 16.sp) },
                    )
                    NavigationBarItem(
                        selected = screen is Screen.Settings,
                        onClick = { screen = Screen.Settings },
                        icon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                        label = { Text("Settings", fontSize = 16.sp) },
                    )
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val s = screen) {
                Screen.Movies, Screen.Shows -> BrowseScreen(
                    library = lib,
                    store = store,
                    filter = filter,
                    showsOnly = s is Screen.Shows,
                    onOpen = ::openDetails,
                    onPlay = { item ->
                        when (item) {
                            is Movie -> playMovie(item)
                            is Show -> playShow(item)
                        }
                    },
                )
                Screen.Search -> SearchScreen(lib, store, onOpen = ::openDetails)
                Screen.Filters -> FiltersScreen(lib, filter, onFilterChange = { filter = it })
                Screen.Settings -> SettingsScreen(store)
                is Screen.Details -> DetailsScreen(
                    item = s.item,
                    store = store,
                    onBack = { screen = lastBrowse },
                    onPlayMovie = ::playMovie,
                    onPlayShow = { show, episode -> playShow(show, episode) },
                )
                is Screen.Player -> PlayerScreen(
                    queue = s.queue,
                    startIndex = s.startIndex,
                    store = store,
                    onExit = { screen = lastBrowse },
                )
            }
        }
    }
}

fun Movie.playable() = Playable(title, year?.toString(), videoPath, posterPath)

/** All episodes in binge order, labelled `S04 · E09 · Title`. */
fun Show.playQueue(): List<Playable> =
    seasons.sortedBy { it.number }.flatMap { season ->
        season.episodes.sortedBy { it.number }.map { episode ->
            Playable(
                title = episode.title ?: "Episode ${episode.number}",
                subtitle = "S%02d · E%02d".format(season.number, episode.number),
                videoPath = episode.videoPath,
                posterPath = episode.thumbPath ?: posterPath,
            )
        }
    }

@Composable
private fun OnboardingScreen(
    hasAccess: Boolean,
    rootPath: String,
    onRootPathChange: (String) -> Unit,
    scanning: Boolean,
    scanError: String?,
    onGrantAccess: () -> Unit,
    onReturnCheck: () -> Unit,
    onScan: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("MediaDroid", fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your movies and shows, straight from the folders — covers, plots and all.",
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))

        if (!hasAccess) {
            Text(
                "MediaDroid needs permission to read your media folders.",
                fontSize = 18.sp,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onGrantAccess, modifier = Modifier.height(64.dp)) {
                Text("Allow file access", fontSize = 20.sp)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onReturnCheck) { Text("I've allowed it — check again", fontSize = 16.sp) }
            Spacer(Modifier.height(24.dp))
        }

        OutlinedTextField(
            value = rootPath,
            onValueChange = onRootPathChange,
            label = { Text("Media folder") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onScan,
            enabled = !scanning,
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) {
            if (scanning) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 3.dp, modifier = Modifier.width(28.dp))
                Spacer(Modifier.width(12.dp))
                Text("Scanning…", fontSize = 20.sp)
            } else {
                Text("Scan library", fontSize = 20.sp)
            }
        }
        scanError?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 16.sp)
        }
    }
}
