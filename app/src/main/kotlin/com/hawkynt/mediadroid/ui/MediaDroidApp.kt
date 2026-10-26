package com.hawkynt.mediadroid.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hawkynt.mediadroid.data.StateStore
import com.hawkynt.mediadroid.library.BrowseFilter
import com.hawkynt.mediadroid.library.Episode
import com.hawkynt.mediadroid.library.Library
import com.hawkynt.mediadroid.library.LibraryScanner
import com.hawkynt.mediadroid.library.LibrarySection
import com.hawkynt.mediadroid.library.MediaItem
import com.hawkynt.mediadroid.library.Movie
import com.hawkynt.mediadroid.library.Show
import com.hawkynt.mediadroid.library.StorageVolumes
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One thing the player can play, with enough context to label itself and skip its intro. */
data class Playable(
    val title: String,
    val subtitle: String?,
    val videoPath: String,
    val posterPath: String?,
    val intro: com.hawkynt.mediadroid.nfo.TimeRange? = null,
    val credits: com.hawkynt.mediadroid.nfo.TimeRange? = null,
)

sealed interface Screen {
    data class Browse(val section: LibrarySection) : Screen
    data object Search : Screen
    data object Filters : Screen
    data object Settings : Screen
    data class Details(val item: MediaItem) : Screen
    data class Player(val queue: List<Playable>, val startIndex: Int) : Screen
}

/** Authorities whose tree document ids [StorageVolumes] can turn back into file paths. */
private val LocalStorageAuthorities = setOf(
    "com.android.externalstorage.documents",
    "com.android.providers.downloads.documents",
)

/**
 * The app shell. There is no library database: the folders are re-read whenever the app starts or
 * comes back to the foreground, and the previous result stays on screen until the new one is in,
 * so a rescan never blanks the wall. Watched marks and resume positions live in [StateStore].
 */
@Composable
fun MediaDroidApp(store: StateStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hasAccess by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    var rootPath by remember { mutableStateOf(store.data.rootPath) }
    var library by remember { mutableStateOf<Library?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var scanGeneration by remember { mutableIntStateOf(0) }
    var choosingFolder by remember { mutableStateOf(false) }
    var screen by remember { mutableStateOf<Screen>(Screen.Browse(LibrarySection.MOVIES)) }
    var lastBrowse by remember { mutableStateOf<Screen>(Screen.Browse(LibrarySection.MOVIES)) }
    var filter by remember { mutableStateOf(BrowseFilter()) }
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    var sidebarExpanded by remember { mutableStateOf(store.data.sidebarExpanded ?: wide) }

    fun scan(path: String, adopt: Boolean = false) {
        val generation = ++scanGeneration
        scanError = null
        scanning = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val root = File(path)
                    require(root.isDirectory) { "the folder is not there — is the SD card or USB drive plugged in?" }
                    LibraryScanner.scan(root)
                }
            }
            if (generation != scanGeneration) return@launch
            scanning = false
            result.onSuccess {
                library = it
                if (adopt) {
                    rootPath = path
                    store.data.rootPath = path
                    store.save()
                    choosingFolder = false
                    screen = Screen.Browse(LibrarySection.MOVIES)
                    lastBrowse = screen
                }
            }.onFailure { scanError = "Could not read $path: ${it.message}" }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAccess = Environment.isExternalStorageManager()
                // Back in the foreground: re-read the folders, they are the only truth.
                if (hasAccess && rootPath.isNotEmpty() && !choosingFolder) scan(rootPath)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) {
            // Backed out of the picker: with a library already on screen, simply go back to it.
            if (library != null) choosingFolder = false
            return@rememberLauncherForActivityResult
        }
        val path = if (uri.authority in LocalStorageAuthorities) {
            StorageVolumes.pathForTreeDocumentId(
                DocumentsContract.getTreeDocumentId(uri),
                Environment.getExternalStorageDirectory().absolutePath,
            )
        } else {
            null
        }
        if (path == null) {
            scanError = "That folder is not on this device's storage. Pick one on the internal storage, an SD card or a USB drive."
        } else {
            scan(path, adopt = true)
        }
    }

    fun launchPicker() {
        choosingFolder = true
        try {
            pickFolder.launch(null)
        } catch (_: ActivityNotFoundException) {
            scanError = "This device has no folder picker. Type the folder's path instead."
        }
    }

    val needsSetup = !hasAccess || rootPath.isEmpty() || choosingFolder || (library == null && scanError != null)
    if (needsSetup) {
        OnboardingScreen(
            hasAccess = hasAccess,
            currentPath = rootPath,
            scanning = scanning,
            scanError = scanError,
            canCancel = library != null && hasAccess,
            onGrantAccess = {
                val intent = runCatching {
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    )
                }.getOrElse { Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION) }
                context.startActivity(intent)
            },
            onPickFolder = ::launchPicker,
            onUsePath = { scan(it.trim(), adopt = true) },
            onCancel = {
                choosingFolder = false
                scanError = null
            },
        )
        return
    }

    val lib = library
    if (lib == null) {
        LoadingScreen()
        // First start with a saved folder: kick off the scan once, the resume observer may have
        // run before access was known.
        LaunchedEffect(Unit) { if (!scanning) scan(rootPath) }
        return
    }

    fun openDetails(item: MediaItem) { screen = Screen.Details(item) }
    fun playMovie(movie: Movie) {
        screen = Screen.Player(listOf(movie.playable()), 0)
    }
    fun playShow(show: Show, fromEpisode: Episode? = null) {
        val queue = show.playQueue()
        if (queue.isEmpty()) return
        val index = fromEpisode?.let { target -> queue.indexOfFirst { it.videoPath == target.videoPath } }
            ?.takeIf { it >= 0 } ?: 0
        screen = Screen.Player(queue, index)
    }
    fun play(item: MediaItem) {
        when (item) {
            is Movie -> playMovie(item)
            is Show -> playShow(item)
        }
    }
    fun navigate(target: Screen) {
        screen = target
        if (target is Screen.Browse) lastBrowse = target
    }

    when (val s = screen) {
        is Screen.Details -> {
            BackHandler { screen = lastBrowse }
            DetailsScreen(
                item = s.item,
                store = store,
                onBack = { screen = lastBrowse },
                onPlayMovie = ::playMovie,
                onPlayShow = { show, episode -> playShow(show, episode) },
            )
            return
        }
        is Screen.Player -> {
            PlayerScreen(
                queue = s.queue,
                startIndex = s.startIndex,
                store = store,
                onExit = { screen = lastBrowse },
            )
            return
        }
        else -> Unit
    }

    if (screen !is Screen.Browse) BackHandler { screen = lastBrowse }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        Sidebar(
            expanded = sidebarExpanded,
            current = screen,
            scanning = scanning,
            onToggle = {
                sidebarExpanded = !sidebarExpanded
                store.data.sidebarExpanded = sidebarExpanded
                store.save()
            },
            onNavigate = ::navigate,
            onRefresh = { scan(rootPath) },
            modifier = Modifier.fillMaxHeight(),
        )
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            when (val s = screen) {
                is Screen.Browse -> BrowseScreen(
                    library = lib,
                    section = s.section,
                    store = store,
                    filter = filter,
                    rootPath = rootPath,
                    onOpen = ::openDetails,
                    onPlay = ::play,
                    onClearFilter = { filter = BrowseFilter() },
                )
                Screen.Search -> SearchScreen(lib, store, onOpen = ::openDetails)
                Screen.Filters -> FiltersScreen(lib, filter, onFilterChange = { filter = it })
                Screen.Settings -> SettingsScreen(store, rootPath = rootPath, onChangeFolder = ::launchPicker)
                else -> Unit
            }
        }
    }
}

fun Movie.playable() = Playable(title, year?.toString(), videoPath, posterPath, intro, credits)

/** All episodes in binge order, labelled `S04 · E09 · Title`. */
fun Show.playQueue(): List<Playable> =
    seasons.sortedBy { it.number }.flatMap { season ->
        season.episodes.sortedBy { it.number }.map { episode ->
            Playable(
                title = episode.title ?: "Episode ${episode.number}",
                subtitle = "S%02d · E%02d".format(season.number, episode.number),
                videoPath = episode.videoPath,
                posterPath = episode.thumbPath ?: posterPath,
                intro = episode.intro,
                credits = episode.credits,
            )
        }
    }
