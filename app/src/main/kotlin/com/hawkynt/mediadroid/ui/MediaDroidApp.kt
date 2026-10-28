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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import com.hawkynt.mediadroid.data.SourceAccess
import com.hawkynt.mediadroid.data.StateStore
import com.hawkynt.mediadroid.data.StorageRoot
import com.hawkynt.mediadroid.data.StorageRoots
import com.hawkynt.mediadroid.library.BrowseFilter
import com.hawkynt.mediadroid.library.Episode
import com.hawkynt.mediadroid.library.HomeRow
import com.hawkynt.mediadroid.library.Library
import com.hawkynt.mediadroid.library.LibraryQuery
import com.hawkynt.mediadroid.library.LibraryScanner
import com.hawkynt.mediadroid.library.LibrarySection
import com.hawkynt.mediadroid.library.MediaItem
import com.hawkynt.mediadroid.library.Movie
import com.hawkynt.mediadroid.library.MovieVersions
import com.hawkynt.mediadroid.library.Show
import com.hawkynt.mediadroid.library.Source
import com.hawkynt.mediadroid.library.SourceKind
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
    /** What the NFO says: watched unless the household unticked it. */
    val nfoWatched: Boolean = false,
)

sealed interface Screen {
    /** A wall; with a [row], the full wall of one home row ("See all") instead of the home. */
    data class Browse(val section: LibrarySection, val row: HomeRow? = null) : Screen
    data class Places(val kind: PlaceKind) : Screen
    /** The Files browser at [dir]; null lists the storage volumes. */
    data class Files(val dir: String? = null) : Screen
    data object Search : Screen
    data object Filters : Screen
    data object Settings : Screen
    data class Details(val item: MediaItem) : Screen
    /** The player; leaving it goes to [returnTo], else to the wall last browsed. */
    data class Player(
        val queue: List<Playable>,
        val startIndex: Int,
        val autoAdvance: Boolean,
        val returnTo: Screen? = null,
    ) : Screen
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
 *
 * [demo] is the documentation mode the README screenshots are taken in: the library is the generated
 * demo folder in [StateStore.data]'s root, file access counts as granted and the player does not play
 * its placeholder videos.
 */
@Composable
fun MediaDroidApp(store: StateStore, demo: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Stored locations on network sources resolve against the saved sources, for player and pictures.
    remember(store) {
        val app = context.applicationContext
        SourceAccess.sources = { store.sources }
        com.hawkynt.mediadroid.player.AviAudioFraming.remote = { SourceAccess.resolve(app, it) }
        com.hawkynt.mediadroid.player.ownedLocation = { Source.owning(store.sources, it) != null }
    }

    fun storageAccess() = demo || Environment.isExternalStorageManager()
    var hasAccess by remember { mutableStateOf(storageAccess()) }
    var rootPath by remember { mutableStateOf(store.data.rootPath) }
    var library by remember { mutableStateOf<Library?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    // Sources that could not be read on the last scan, by id: their titles are missing until they are back.
    var unreachable by remember { mutableStateOf(emptySet<String>()) }
    var scanGeneration by remember { mutableIntStateOf(0) }
    var choosingFolder by remember { mutableStateOf(false) }
    var screen by remember { mutableStateOf<Screen>(Screen.Browse(LibrarySection.MOVIES)) }
    var lastBrowse by remember { mutableStateOf<Screen>(Screen.Browse(LibrarySection.MOVIES)) }
    var filter by remember { mutableStateOf(BrowseFilter()) }
    // Each wall keeps its own order, remembered across launches.
    var sorts by remember { mutableStateOf(LibrarySection.entries.associateWith { store.sortFor(it) }) }
    var searchQuery by remember { mutableStateOf("") }
    // Where Back from a title's details leads: the wall or the search it was opened from.
    var detailsReturn by remember { mutableStateOf<Screen>(Screen.Browse(LibrarySection.MOVIES)) }
    // Scroll positions outlive a visit to a title's details, so Back lands where you were.
    val movieGrid = rememberLazyGridState()
    val showGrid = rememberLazyGridState()
    val searchGrid = rememberLazyGridState()
    val rowGrid = rememberLazyGridState()
    // The demo has no real volumes: its library stands in for the internal storage.
    val storageRoots = remember(demo) {
        if (demo) {
            listOf(StorageRoot("Internal storage", store.data.rootPath, removable = false))
        } else {
            StorageRoots.list(context)
        }
    }
    // Which section the Genres and Years places group; follows the wall last browsed.
    var placeSection by remember { mutableStateOf(LibrarySection.MOVIES) }
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
                    // The chosen folder first, then every other saved source; one that cannot be read
                    // is left out of this scan rather than failing it.
                    val others = store.sources.filter { it.kind != SourceKind.LOCAL || it.path != path }
                    val files = SourceAccess.of(context)
                    val scans = others.associate { source ->
                        source.id to runCatching { files.root(source)?.let { LibraryScanner.scan(it) } }.getOrNull()
                    }
                    Library.merge(listOf(LibraryScanner.scan(root)) + scans.values.filterNotNull()) to
                        scans.filterValues { it == null }.keys
                }
            }
            if (generation != scanGeneration) return@launch
            scanning = false
            result.onSuccess { (scanned, missing) ->
                library = scanned
                unreachable = missing
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
                hasAccess = storageAccess()
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
            scanError = "That folder is not on this device's storage. " +
                "Pick one on the internal storage, an SD card or a USB drive."
        } else {
            scan(path, adopt = true)
        }
    }

    // A further folder on the device, kept as a source of its own beside the media folder.
    val addFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val path = if (uri.authority in LocalStorageAuthorities) {
            StorageVolumes.pathForTreeDocumentId(
                DocumentsContract.getTreeDocumentId(uri),
                Environment.getExternalStorageDirectory().absolutePath,
            )
        } else {
            null
        }
        if (path != null && store.sources.none { it.kind == SourceKind.LOCAL && it.path == path }) {
            val added = Source.local(java.util.UUID.randomUUID().toString(), File(path).name.ifEmpty { path }, path)
            store.saveSources(store.sources + added)
            scan(rootPath)
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

    fun openDetails(item: MediaItem) {
        if (screen !is Screen.Details) detailsReturn = screen
        screen = Screen.Details(item)
    }

    /**
     * Where a queue picks up: the file asked for, else one with a resume point, else the first not
     * yet watched — so Play on a show continues it instead of starting at the pilot again.
     */
    fun startOf(queue: List<Playable>, path: String?): Int =
        path?.let { p -> queue.indexOfFirst { it.videoPath == p } }?.takeIf { it >= 0 }
            ?: queue.indexOfFirst { store.resumeFor(it.videoPath) != null }.takeIf { it >= 0 }
            ?: queue.indexOfFirst { !store.isWatched(it.videoPath, it.nfoWatched) }.takeIf { it >= 0 }
            ?: 0

    fun playMovie(movie: Movie, path: String? = null) {
        // Of several files of one film: the one asked for, else the one with a resume point, else the best.
        val version = path?.let { p -> movie.versions.firstOrNull { p in it.parts } }
            ?: MovieVersions.toPlay(movie) { store.resumeFor(it) != null }
        val queue = version.playQueue()
        // The parts of one movie always run on; binge mode is about episodes.
        screen = Screen.Player(queue, startOf(queue, path), autoAdvance = true)
    }
    fun playShow(show: Show, fromEpisode: Episode? = null, path: String? = null) {
        val queue = show.playQueue()
        if (queue.isEmpty()) return
        screen = Screen.Player(
            queue,
            startOf(queue, fromEpisode?.videoPath ?: path),
            autoAdvance = store.data.settings.bingeWatching,
        )
    }
    fun play(item: MediaItem, path: String? = null) {
        when (item) {
            is Movie -> playMovie(item, path)
            is Show -> playShow(item, path = path)
        }
    }
    /** The last video played, offered at the bottom of the sidebar; null when it is gone or finished. */
    fun resumeSpot(library: Library): ResumeSpot? {
        val path = store.data.lastPlayed.takeIf { it.isNotEmpty() } ?: return null
        val item = LibraryQuery.itemForVideo(library, path) ?: return null
        val resume = store.resumeFor(path)
        return when (item) {
            is Movie -> ResumeSpot(
                title = item.title,
                subtitle = item.parts.indexOf(path).takeIf { item.parts.size > 1 }
                    ?.let { "Part ${it + 1} of ${item.parts.size}" } ?: item.year?.toString(),
                picturePath = item.fanartPath ?: item.posterPath,
                videoPath = path,
                progress = resume?.let { it.positionMs.toFloat() / it.durationMs },
                onResume = { playMovie(item, path) },
            )
            is Show -> {
                val (season, episode) = item.seasons.firstNotNullOf { s ->
                    s.episodes.firstOrNull { it.videoPath == path }?.let { s to it }
                }
                ResumeSpot(
                    title = item.title,
                    subtitle = "S%02d · E%02d".format(season.number, episode.number) +
                        (episode.title?.let { " · $it" } ?: ""),
                    picturePath = episode.thumbPath ?: item.fanartPath ?: item.posterPath,
                    videoPath = path,
                    progress = resume?.let { it.positionMs.toFloat() / it.durationMs },
                    onResume = { playShow(item, path = path) },
                )
            }
        }
    }
    fun navigate(target: Screen) {
        screen = target
        if (target is Screen.Browse) {
            lastBrowse = target
            placeSection = target.section
        }
    }

    when (val s = screen) {
        is Screen.Details -> {
            BackHandler { screen = detailsReturn }
            DetailsScreen(
                item = s.item,
                store = store,
                onBack = { screen = detailsReturn },
                onPlayMovie = { playMovie(it) },
                onPlayShow = { show, episode -> playShow(show, episode) },
                onPlayTrailer = { movie ->
                    movie.trailerPath?.let { path ->
                        val trailer = Playable("${movie.title} · Trailer", null, path, movie.posterPath)
                        screen = Screen.Player(listOf(trailer), 0, autoAdvance = false, returnTo = s)
                    }
                },
            )
            return
        }
        is Screen.Player -> {
            PlayerScreen(
                queue = s.queue,
                startIndex = s.startIndex,
                store = store,
                autoAdvance = s.autoAdvance,
                onExit = { screen = s.returnTo ?: lastBrowse },
                demo = demo,
            )
            return
        }
        else -> Unit
    }

    val current = screen
    if (current is Screen.Browse && current.row != null) {
        BackHandler { navigate(Screen.Browse(current.section)) }
    } else if (current !is Screen.Browse) {
        BackHandler { screen = lastBrowse }
    }

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
            resume = resumeSpot(lib),
        )
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            when (val s = screen) {
                is Screen.Browse -> BrowseScreen(
                    library = lib,
                    section = s.section,
                    store = store,
                    filter = filter.copy(sort = sorts.getValue(s.section)),
                    rootPath = rootPath,
                    row = s.row,
                    gridState = when {
                        s.row != null -> rowGrid
                        s.section == LibrarySection.MOVIES -> movieGrid
                        else -> showGrid
                    },
                    onOpen = ::openDetails,
                    onPlay = { item, path -> play(item, path) },
                    onClearFilter = { filter = BrowseFilter() },
                    onSeeAll = { navigate(Screen.Browse(s.section, it)) },
                )
                is Screen.Places -> PlacesScreen(
                    library = lib,
                    store = store,
                    kind = s.kind,
                    section = placeSection,
                    onSectionChange = { placeSection = it },
                    onOpen = { section, placeFilter ->
                        filter = placeFilter
                        navigate(Screen.Browse(section))
                    },
                )
                is Screen.Files -> FilesScreen(
                    store = store,
                    roots = storageRoots,
                    dir = s.dir,
                    onOpen = { screen = Screen.Files(it) },
                    onPlay = { queue, start ->
                        screen = Screen.Player(
                            queue,
                            start,
                            autoAdvance = store.data.settings.bingeWatching,
                            returnTo = s,
                        )
                    },
                )
                Screen.Search -> SearchScreen(
                    library = lib,
                    store = store,
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    gridState = searchGrid,
                    onOpen = ::openDetails,
                )
                Screen.Filters -> FiltersScreen(
                    library = lib,
                    filter = filter,
                    onFilterChange = { filter = it },
                    sorts = sorts,
                    onSortChange = { section, order ->
                        sorts = sorts + (section to order)
                        store.saveSort(section, order)
                    },
                )
                Screen.Settings -> SettingsScreen(
                    store,
                    rootPath = rootPath,
                    onChangeFolder = ::launchPicker,
                    unreachable = unreachable,
                    onAddDeviceFolder = { addFolder.launch(null) },
                    onSourcesChanged = { scan(rootPath) },
                )
                else -> Unit
            }
        }
    }
}

/** The movie's files in order; a multi-part movie labels them `Part 1 of 2`. */
fun Movie.playQueue(): List<Playable> = parts.mapIndexed { i, part ->
    Playable(
        title = title,
        subtitle = if (parts.size > 1) "Part ${i + 1} of ${parts.size}" else year?.toString(),
        videoPath = part,
        posterPath = posterPath,
        intro = intro.takeIf { i == 0 },
        credits = credits.takeIf { i == parts.lastIndex },
        nfoWatched = playcount > 0,
    )
}

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
                nfoWatched = episode.watched,
            )
        }
    }
