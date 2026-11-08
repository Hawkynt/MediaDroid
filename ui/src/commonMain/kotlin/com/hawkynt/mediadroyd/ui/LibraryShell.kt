package com.hawkynt.mediadroyd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import com.hawkynt.mediadroyd.library.BrowseFilter
import com.hawkynt.mediadroyd.library.Library
import com.hawkynt.mediadroyd.library.LibraryQuery
import com.hawkynt.mediadroyd.library.LibrarySection
import com.hawkynt.mediadroyd.library.MediaItem
import com.hawkynt.mediadroyd.library.Movie
import com.hawkynt.mediadroyd.library.MovieVersions
import com.hawkynt.mediadroyd.library.Show
import com.hawkynt.mediadroyd.library.SortOrder
import com.hawkynt.mediadroyd.library.Source
import com.hawkynt.mediadroyd.state.StateStore
import com.hawkynt.mediadroyd.ui.platform.StorageRoot

/**
 * Where the app is, the same on every platform: the screen shown, where Back leads from it (the wall last browsed,
 * or where a title's details were opened from), the walls' filter, their orders kept across launches, the search typed,
 * which section the places group, whether the sidebar is wide. Kept by each app above its first start, so a folder
 * chosen anew or a scan does not lose it.
 */
class ShellState(
    private val store: StateStore,
    start: Screen = Screen.Browse(LibrarySection.MOVIES),
    wide: Boolean = true,
) {
    var screen by mutableStateOf(start)
        private set

    /** The wall browsed last: where Back from a place, a tool or Settings leads. */
    var lastBrowse by mutableStateOf<Screen>(Screen.Browse(LibrarySection.MOVIES))
        private set

    /** Where Back from a title's details leads: the wall or the search it was opened from. */
    private var detailsReturn: Screen = Screen.Browse(LibrarySection.MOVIES)

    var filter by mutableStateOf(BrowseFilter())

    /** Each wall's own order, remembered across launches. */
    var sorts by mutableStateOf(LibrarySection.entries.associateWith { store.sortFor(it) })
        private set

    var searchQuery by mutableStateOf("")

    /** Which section the Genres and Years places group; follows the wall last browsed. */
    var placeSection by mutableStateOf(LibrarySection.MOVIES)

    var sidebarExpanded by mutableStateOf(store.data.sidebarExpanded ?: wide)
        private set

    fun navigate(target: Screen) {
        screen = target
        if (target is Screen.Browse) {
            lastBrowse = target
            placeSection = target.section
        }
    }

    fun openDetails(item: MediaItem) {
        if (screen !is Screen.Details) detailsReturn = screen
        screen = Screen.Details(item)
    }

    /** Back: from details where they were opened, from a home row's wall its home, from elsewhere the last wall. */
    fun back() {
        when (val current = screen) {
            is Screen.Details -> screen = detailsReturn
            is Screen.Browse -> if (current.row != null) navigate(Screen.Browse(current.section))
            else -> screen = lastBrowse
        }
    }

    /** Whether Back has somewhere to go in the shell (the walls' home has not). */
    val canGoBack: Boolean get() = screen.let { it !is Screen.Browse || it.row != null }

    /** The walls from their start, as after a media folder chosen anew. */
    fun home() = navigate(Screen.Browse(LibrarySection.MOVIES))

    fun sort(section: LibrarySection, order: SortOrder) {
        sorts = sorts + (section to order)
        store.saveSort(section, order)
    }

    fun toggleSidebar() {
        sidebarExpanded = !sidebarExpanded
        store.data.sidebarExpanded = sidebarExpanded
        store.save()
    }

    /**
     * What playing [item] plays: of a film's files the one asked for ([path]), else the version with a resume point,
     * else the best, its parts running on; a show's episodes in binge order from the one asked for, else the first
     * not watched, the next one following as binge-watching says. Null for a show without episodes.
     */
    fun playerFor(item: MediaItem, path: String?): Screen.Player? = when (item) {
        is Movie -> {
            val version = path?.let { p -> item.versions.firstOrNull { p in it.parts } }
                ?: MovieVersions.toPlay(item) { store.resumeFor(it) != null }
            val queue = version.playQueue()
            Screen.Player(queue, startOf(queue, path, store), autoAdvance = true)
        }
        is Show -> item.playQueue().takeIf { it.isNotEmpty() }?.let { queue ->
            Screen.Player(queue, startOf(queue, path, store), autoAdvance = store.data.settings.bingeWatching)
        }
    }

    /** The video played last, offered at the bottom of the sidebar; null when it is gone from [library] or none. */
    fun resumeSpot(library: Library, onPlay: (Screen.Player) -> Unit): ResumeSpot? {
        val path = store.data.lastPlayed.takeIf { it.isNotEmpty() } ?: return null
        val item = LibraryQuery.itemForVideo(library, path) ?: return null
        val resume = store.resumeFor(path)
        val progress = resume?.let { it.positionMs.toFloat() / it.durationMs }
        val resumeIt = { playerFor(item, path)?.let(onPlay); Unit }
        return when (item) {
            is Movie -> ResumeSpot(
                title = item.title,
                subtitle = item.parts.indexOf(path).takeIf { item.parts.size > 1 }
                    ?.let { "Part ${it + 1} of ${item.parts.size}" } ?: item.year?.toString(),
                picturePath = item.fanartPath ?: item.posterPath,
                videoPath = path,
                progress = progress,
                onResume = resumeIt,
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
                    progress = progress,
                    onResume = resumeIt,
                )
            }
        }
    }
}

/** The scroll places of the walls, kept while a title's details are open so Back lands where it was. */
class ShellGrids(val movies: LazyGridState, val shows: LazyGridState, val search: LazyGridState, val row: LazyGridState)

/** The shell's state for [store], made once; the sidebar starts wide on a [wide] screen unless the household chose. */
@Composable
fun rememberShellState(
    store: StateStore,
    start: Screen = Screen.Browse(LibrarySection.MOVIES),
    wide: Boolean = true,
): ShellState = remember(store) { ShellState(store, start, wide) }

/**
 * The library as both apps show it: the sidebar beside the walls (Movies and TV Shows with their home rows), Genres and
 * Years, the Files browser over [roots], Search, Filters and Settings; a title opens its details over all of it, Back
 * returns as [ShellState.back] says. What plays goes to [onPlay]; the app shows the player. Titles whose every file
 * lies on a source among [unreachable] are greyed. The platform's own: [onRefresh] scans again, [onChangeFolder] and
 * [onAddDeviceFolder] pick folders, [playingCorner] is where the phone's small player sits.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun LibraryShell(
    state: ShellState,
    library: Library,
    store: StateStore,
    scanning: Boolean,
    rootPath: String,
    roots: List<StorageRoot>,
    onRefresh: () -> Unit,
    onPlay: (Screen.Player) -> Unit,
    onChangeFolder: () -> Unit,
    onAddDeviceFolder: () -> Unit,
    unreachable: Set<String> = emptySet(),
    playingCorner: ((androidx.compose.ui.geometry.Rect) -> Unit)? = null,
    settingsUnlockedAtStart: Boolean = false,
    grids: ShellGrids = ShellGrids(
        rememberLazyGridState(), rememberLazyGridState(), rememberLazyGridState(), rememberLazyGridState(),
    ),
) {
    fun play(item: MediaItem, path: String?) {
        state.playerFor(item, path)?.let(onPlay)
    }

    val details = state.screen as? Screen.Details
    if (details != null) {
        BackHandler(onBack = state::back)
        DetailsScreen(
            item = details.item,
            store = store,
            onBack = state::back,
            onPlayMovie = { play(it, null) },
            onPlayShow = { show, episode -> play(show, episode?.videoPath) },
            onPlayTrailer = { movie ->
                movie.trailerPath?.let { path ->
                    val trailer = Playable("${movie.title} · Trailer", null, path, movie.posterPath)
                    onPlay(Screen.Player(listOf(trailer), 0, autoAdvance = false, returnTo = details))
                }
            },
        )
        return
    }
    if (state.canGoBack) BackHandler(onBack = state::back)

    // Titles whose every file lies on a source that cannot be reached right now are shown greyed out.
    val away: (MediaItem) -> Boolean = remember(unreachable, store.sources) {
        val sources = store.sources
        val gone = unreachable
        { item -> gone.isNotEmpty() && locationsOf(item).all { Source.owning(sources, it)?.id in gone } }
    }
    CompositionLocalProvider(LocalAway provides away) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .safeDrawingPadding(),
        ) {
            Sidebar(
                expanded = state.sidebarExpanded,
                current = state.screen,
                scanning = scanning,
                onToggle = state::toggleSidebar,
                onNavigate = state::navigate,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxHeight(),
                resume = state.resumeSpot(library, onPlay),
                playingCorner = playingCorner,
            )
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                when (val s = state.screen) {
                    is Screen.Browse -> BrowseScreen(
                        library = library,
                        section = s.section,
                        store = store,
                        filter = state.filter.copy(sort = state.sorts.getValue(s.section)),
                        rootPath = rootPath,
                        row = s.row,
                        gridState = when {
                            s.row != null -> grids.row
                            s.section == LibrarySection.MOVIES -> grids.movies
                            else -> grids.shows
                        },
                        onOpen = state::openDetails,
                        onPlay = ::play,
                        onClearFilter = { state.filter = BrowseFilter() },
                        onSeeAll = { state.navigate(Screen.Browse(s.section, it)) },
                    )
                    is Screen.Places -> PlacesScreen(
                        library = library,
                        store = store,
                        kind = s.kind,
                        section = state.placeSection,
                        onSectionChange = { state.placeSection = it },
                        onOpen = { section, placeFilter ->
                            state.filter = placeFilter
                            state.navigate(Screen.Browse(section))
                        },
                    )
                    is Screen.Files -> FilesScreen(
                        store = store,
                        roots = roots,
                        dir = s.dir,
                        onOpen = { state.navigate(Screen.Files(it)) },
                        onPlay = { queue, start ->
                            onPlay(Screen.Player(queue, start, store.data.settings.bingeWatching, returnTo = s))
                        },
                    )
                    Screen.Search -> SearchScreen(
                        library = library,
                        store = store,
                        query = state.searchQuery,
                        onQueryChange = { state.searchQuery = it },
                        gridState = grids.search,
                        onOpen = state::openDetails,
                    )
                    Screen.Filters -> FiltersScreen(
                        library = library,
                        filter = state.filter,
                        onFilterChange = { state.filter = it },
                        sorts = state.sorts,
                        onSortChange = state::sort,
                    )
                    Screen.Settings -> SettingsScreen(
                        store,
                        onChangeFolder = onChangeFolder,
                        unreachable = unreachable,
                        onAddDeviceFolder = onAddDeviceFolder,
                        onSourcesChanged = onRefresh,
                        unlockedAtStart = settingsUnlockedAtStart,
                    )
                    else -> Unit
                }
            }
        }
    }
}

/** Every video an item plays from. */
private fun locationsOf(item: MediaItem): List<String> = when (item) {
    is Movie -> item.allParts
    is Show -> item.seasons.flatMap { s -> s.episodes.map { it.videoPath } }
}
