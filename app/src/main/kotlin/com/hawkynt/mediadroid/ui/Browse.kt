package com.hawkynt.mediadroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.hawkynt.mediadroid.data.ArtCache
import com.hawkynt.mediadroid.data.StateStore
import com.hawkynt.mediadroid.data.ThumbnailStore
import com.hawkynt.mediadroid.library.BrowseFilter
import com.hawkynt.mediadroid.library.HomeRow
import com.hawkynt.mediadroid.library.HomeRows
import com.hawkynt.mediadroid.library.Library
import com.hawkynt.mediadroid.library.LibraryQuery
import com.hawkynt.mediadroid.library.LibrarySection
import com.hawkynt.mediadroid.library.MediaItem
import com.hawkynt.mediadroid.library.Movie
import com.hawkynt.mediadroid.library.Show
import com.hawkynt.mediadroid.library.SortOrder
import com.hawkynt.mediadroid.ui.theme.focusClickable
import java.io.File
import kotlinx.coroutines.launch

/**
 * One section's home, laid out like the streaming apps: a backdrop hero for one title, the
 * Continue watching row, then the poster wall — all in a single scroll.
 */
@Composable
fun BrowseScreen(
    library: Library,
    section: LibrarySection,
    store: StateStore,
    filter: BrowseFilter,
    rootPath: String,
    gridState: LazyGridState,
    onOpen: (MediaItem) -> Unit,
    onPlay: (item: MediaItem, path: String?) -> Unit,
    onClearFilter: () -> Unit,
    row: HomeRow? = null,
    onSeeAll: (HomeRow) -> Unit = {},
) {
    val maxAge = store.data.settings.maxAgeYears
    val wall = LibraryQuery.browse(library.itemsIn(section), filter, maxAge)
    val items = row?.let { HomeRows.items(it, wall, store.marks) } ?: wall
    val continueItems = rememberContinueItems(library, store).filter {
        when (section) {
            LibrarySection.MOVIES -> it.item is Movie
            LibrarySection.SHOWS -> it.item is Show
        }
    }
    val featured = LibraryQuery.featured(items, continueItems.map { it.item.key })
    val sectionName = when (section) {
        LibrarySection.MOVIES -> "Movies"
        LibrarySection.SHOWS -> "TV Shows"
    }
    val filtered = filter.copy(sort = SortOrder.TITLE) != BrowseFilter()
    // The home — hero, Continue watching and the rows — sits above the full wall only, unfiltered.
    val home = row == null && !filtered
    val homeRows = if (home) {
        HomeRows.rowsFor(section).map { it to HomeRows.items(it, wall, store.marks) }.filter { it.second.isNotEmpty() }
    } else {
        emptyList()
    }
    var mode by remember { mutableStateOf(store.wallMode) }

    // Phones get two or three posters across; tablets and TVs as many as fit.
    val compact = LocalConfiguration.current.screenWidthDp < 600
    val gutter = if (compact) 16.dp else 24.dp

    // Long walls in title order get an A–Z rail; a jump lands on the grid item after the home above it.
    val titleOrder = filter.sort == SortOrder.TITLE || filter.sort == SortOrder.TITLE_DESC
    val letters = if (titleOrder && (row == null || row == HomeRow.NOT_WATCHED) && items.size >= LETTER_RAIL_MIN) {
        LibraryQuery.letterIndex(items)
    } else {
        emptyList()
    }
    val leading = listOf(featured != null && home, continueItems.isNotEmpty() && home, true, items.isEmpty())
        .count { it } + homeRows.size
    val scope = rememberCoroutineScope()
    var railLetter by remember { mutableStateOf<Char?>(null) }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(
                minSize = when (mode) {
                    WallMode.POSTERS -> if (compact) 104.dp else 140.dp
                    WallMode.LIST -> 340.dp
                    WallMode.DETAILS -> 480.dp
                },
            ),
            contentPadding = PaddingValues(
                start = gutter,
                end = gutter + if (letters.isEmpty()) 0.dp else LetterRailWidth,
                top = 16.dp,
                bottom = 32.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (featured != null && home) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "hero") {
                    Hero(featured, onPlay = { onPlay(featured, null) }, onInfo = { onOpen(featured) })
                }
            }
            if (continueItems.isNotEmpty() && home) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "continue") {
                    Column {
                        RowTitle("Continue watching")
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items(continueItems, key = { it.playable.videoPath }) { entry ->
                                ContinueCard(entry, onClick = { onPlay(entry.item, entry.playable.videoPath) })
                            }
                        }
                    }
                }
            }
            homeRows.forEach { (homeRow, rowItems) ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "row-${homeRow.name}") {
                    Column {
                        RowTitle(homeRow.label)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(rowItems.take(HOME_ROW_LENGTH), key = { it.key }) { item ->
                                PosterCard(
                                    item,
                                    onClick = { onOpen(item) },
                                    watched = isWatched(item, store),
                                    progress = resumeProgress(item, store),
                                    modifier = Modifier.width(if (compact) 112.dp else 140.dp),
                                )
                            }
                            item(key = "see-all") {
                                SeeAllCard(onClick = { onSeeAll(homeRow) }, width = if (compact) 112.dp else 140.dp)
                            }
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    RowTitle(
                        when {
                            row != null -> "${row.label} · $sectionName"
                            filtered -> "$sectionName · filtered"
                            else -> "All $sectionName"
                        },
                    )
                    Text(
                        "${items.size}",
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    if (filtered) {
                        Spacer(Modifier.width(16.dp))
                        AssistChip(
                            onClick = onClearFilter,
                            label = { Text("Clear filters", fontSize = 15.sp) },
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f).widthIn(min = 12.dp))
                    WallModeSwitch(
                        mode,
                        onChange = {
                            mode = it
                            store.saveWallMode(it)
                        },
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
            if (items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                    Text(
                        if (filtered) {
                            "Nothing matches these filters."
                        } else {
                            "No ${sectionName.lowercase()} found in $rootPath."
                        },
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 32.dp),
                    )
                }
            }
            items(items, key = { it.key }) { item ->
                val watched = isWatched(item, store)
                val progress = resumeProgress(item, store)
                when (mode) {
                    WallMode.POSTERS -> PosterCard(
                        item,
                        onClick = { onOpen(item) },
                        watched = watched,
                        progress = progress,
                    )
                    else -> WallRow(
                        item,
                        watched,
                        progress,
                        withPlot = mode == WallMode.DETAILS,
                        onClick = { onOpen(item) },
                    )
                }
            }
        }
        if (letters.isNotEmpty()) {
            LetterRail(
                letters,
                onLetter = { letter, index ->
                    railLetter = letter
                    if (index != null) scope.launch { gridState.scrollToItem(leading + index) }
                },
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 16.dp),
            )
        }
        railLetter?.let {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(120.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(it.toString(), fontSize = 72.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** From how many titles on a wall the A–Z rail shows. */
private const val LETTER_RAIL_MIN = 30

private val LetterRailWidth = 28.dp

/**
 * The A–Z rail along a wall's edge. Touching or dragging along it reports the letter under the finger
 * and the index of its first title; lifting the finger reports null so the big letter goes away.
 */
@Composable
private fun LetterRail(
    letters: List<Pair<Char, Int>>,
    onLetter: (letter: Char?, index: Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var height by remember { mutableIntStateOf(1) }
    Column(
        modifier = modifier
            .width(LetterRailWidth)
            .onSizeChanged { height = it.height.coerceAtLeast(1) }
            .pointerInput(letters) {
                var last: Char? = null
                fun pick(y: Float) {
                    val (letter, index) = letters[(y / height * letters.size).toInt().coerceIn(0, letters.lastIndex)]
                    if (letter != last) {
                        last = letter
                        onLetter(letter, index)
                    }
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    pick(down.position.y)
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        pick(change.position.y)
                    }
                    last = null
                    onLetter(null, null)
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        letters.forEach { (letter, _) ->
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    letter.toString(),
                    fontSize = 11.sp,
                    lineHeight = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** How many titles a home row shows before its "See all". */
private const val HOME_ROW_LENGTH = 20

/** The last card of a home row: opens the whole row as a wall. */
@Composable
private fun SeeAllCard(onClick: () -> Unit, width: Dp) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .width(width)
            .aspectRatio(2f / 3f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .focusClickable(shape = shape, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
            )
            Text("See all", fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** Posters, a compact list, or a list with two lines of plot — the toggle at the head of the wall. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WallModeSwitch(mode: WallMode, onChange: (WallMode) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier) {
        WallMode.entries.forEachIndexed { i, entry ->
            SegmentedButton(
                selected = mode == entry,
                onClick = { onChange(entry) },
                shape = SegmentedButtonDefaults.itemShape(i, WallMode.entries.size),
                icon = {},
                label = { Text(entry.label, fontSize = 14.sp, maxLines = 1) },
            )
        }
    }
}

/** One title as a list row: small poster, title with its watched tick, year and runtime, badges, plot. */
@Composable
private fun WallRow(item: MediaItem, watched: Boolean, progress: Float?, withPlot: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .focusClickable(shape = shape, onClick = onClick)
            .padding(6.dp),
    ) {
        Box(
            modifier = Modifier
                .width(if (withPlot) 72.dp else 52.dp)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            PosterImage(
                item.posterPath,
                fallbackLetter = item.title.take(1),
                videoFallback = videoOf(item),
                contentScale = ContentScale.Crop,
            )
            progress?.let { ResumeBar(it, Modifier.align(Alignment.BottomCenter)) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (watched) {
                    Spacer(Modifier.width(8.dp))
                    WatchedTick(size = 20.dp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                item.minAge?.let {
                    AgeBadge(it, size = 18.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    metaLine(item),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item is Movie) QualityChips(rememberQuality(item.videoPath, item.quality))
            if (withPlot) {
                plotOf(item)?.let { Text(it, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
private fun RowTitle(text: String) {
    Text(
        text,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(end = 12.dp, top = 16.dp, bottom = 10.dp),
    )
}

/** The featured title over its backdrop, with Play and More info — the streaming-app front page. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Hero(item: MediaItem, onPlay: () -> Unit, onInfo: () -> Unit) {
    val background = MaterialTheme.colorScheme.background
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val compact = maxWidth < 600.dp
        // At least 16:9 so the backdrop reads as a backdrop; taller when the text needs it.
        val minHeight = min(maxWidth * 9 / 16, 460.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.BottomStart,
        ) {
            Box(Modifier.matchParentSize()) {
                PosterImage(
                    item.fanartPath ?: item.posterPath,
                    videoFallback = videoOf(item),
                    contentScale = ContentScale.Crop,
                )
            }
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(0.3f to Color.Transparent, 1f to background),
                ),
            )
            Box(
                Modifier.matchParentSize().background(
                    Brush.horizontalGradient(0f to background.copy(alpha = 0.85f), 0.7f to Color.Transparent),
                ),
            )
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .padding(start = 20.dp, end = 20.dp, top = 48.dp, bottom = 20.dp),
            ) {
                Text(
                    item.title,
                    fontSize = if (compact) 26.sp else 34.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = metaLine(item)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    item.minAge?.let {
                        AgeBadge(it, size = 24.dp)
                        Spacer(Modifier.width(10.dp))
                    }
                    if (meta.isNotEmpty()) {
                        Text(meta, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                plotOf(item)?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(16.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(onClick = onPlay, modifier = Modifier.height(52.dp)) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Play", fontSize = 18.sp)
                    }
                    FilledTonalButton(onClick = onInfo, modifier = Modifier.height(52.dp)) {
                        Icon(Icons.Filled.Info, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("More info", fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

/** Year, seasons or runtime, and up to three genres, the way a card's second line reads. */
private fun metaLine(item: MediaItem): String = listOfNotNull(
    item.year?.toString(),
    (item as? Show)?.seasons?.size?.let { if (it == 1) "1 season" else "$it seasons" },
    (item as? Movie)?.runtimeMinutes?.let { "$it min" },
    item.genres.take(3).joinToString(" · ").ifEmpty { null },
).joinToString("  ·  ")

private fun plotOf(item: MediaItem): String? = when (item) {
    is Movie -> item.plot
    is Show -> item.plot
}

private fun videoOf(item: MediaItem): String? = when (item) {
    is Movie -> item.videoPath
    is Show -> item.representativeVideo
}

/** One card of the Continue watching row: what it resumes, how it is labelled, how far in it is. */
data class ContinueEntry(
    val item: MediaItem,
    val playable: Playable,
    val title: String,
    val detail: String?,
    val progress: Float,
)

@Composable
private fun rememberContinueItems(library: Library, store: StateStore): List<ContinueEntry> {
    val resume = store.data.resume
    val maxAge = store.data.settings.maxAgeYears
    return resume.entries.mapNotNull { (path, info) ->
        val progress = progressOf(info.positionMs, info.durationMs)
        val movie = library.movies.firstOrNull { path in it.allParts }
        if (movie != null) {
            val version = movie.versions.firstOrNull { path in it.parts } ?: movie
            val playable = version.playQueue().first { it.videoPath == path }
            ContinueEntry(movie, playable, movie.title, playable.subtitle, progress)
        } else {
            val show = library.shows.firstOrNull { s ->
                s.seasons.any { se -> se.episodes.any { it.videoPath == path } }
            }
            val season = show?.seasons?.firstOrNull { se -> se.episodes.any { it.videoPath == path } }
            val episode = season?.episodes?.firstOrNull { it.videoPath == path }
            if (show != null && season != null && episode != null) {
                ContinueEntry(
                    item = show,
                    playable = Playable(
                        episode.title ?: "Episode ${episode.number}",
                        null,
                        path,
                        episode.thumbPath ?: season.posterPath ?: show.posterPath,
                    ),
                    title = show.title,
                    detail = buildString {
                        append("S%02d · E%02d".format(season.number, episode.number))
                        episode.title?.let { append(" · $it") }
                    },
                    progress = progress,
                )
            } else {
                null
            }
        }
    }
        .filter { entry ->
            // The max-age mode hides over-cap titles even from the resume row.
            val age = entry.item.minAge
            maxAge <= 0 || age == null || age <= maxAge
        }
}

/** Whether [item] counts as watched — for a show, every episode. */
fun isWatched(item: MediaItem, store: StateStore): Boolean = when (item) {
    is Movie -> store.marks.isWatched(item)
    is Show -> store.marks.isWatched(item)
}

/**
 * How far in playback stopped, for the bar along a card's picture: the movie part or the show's
 * episode that has a resume point (the latest in the show's order); null when nothing is under way.
 */
fun resumeProgress(item: MediaItem, store: StateStore): Float? {
    val paths = when (item) {
        is Movie -> item.allParts
        is Show -> item.seasons.sortedBy { it.number }.flatMap { season ->
            season.episodes.sortedBy { it.number }.map { it.videoPath }
        }
    }
    val info = paths.asReversed().firstNotNullOfOrNull { store.resumeFor(it) } ?: return null
    return progressOf(info.positionMs, info.durationMs).takeIf { it > 0f }
}

/** A thin bar along the bottom of a picture showing how much of it is watched. */
@Composable
internal fun ResumeBar(progress: Float, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { progress },
        color = MaterialTheme.colorScheme.primary,
        trackColor = Color.Black.copy(alpha = 0.55f),
        drawStopIndicator = {},
        gapSize = 0.dp,
        modifier = modifier.fillMaxWidth().height(4.dp),
    )
}

internal fun progressOf(positionMs: Long, durationMs: Long): Float =
    if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

@Composable
private fun ContinueCard(entry: ContinueEntry, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(modifier = Modifier.width(240.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .focusClickable(shape = shape, onClick = onClick),
        ) {
            PosterImage(
                entry.playable.posterPath,
                videoFallback = entry.playable.videoPath,
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Color.White)
            }
        }
        // How much is watched, as a bar right under the picture.
        LinearProgressIndicator(
            progress = { entry.progress },
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            drawStopIndicator = {},
            gapSize = 0.dp,
            modifier = Modifier.padding(top = 6.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
        )
        Text(
            entry.title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        entry.detail?.let {
            Text(
                it,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun PosterCard(
    item: MediaItem,
    onClick: () -> Unit,
    watched: Boolean = false,
    progress: Float? = null,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .focusClickable(shape = shape, onClick = onClick),
        ) {
            PosterImage(
                item.posterPath,
                fallbackLetter = item.title.take(1),
                videoFallback = videoOf(item),
                contentScale = ContentScale.Crop,
            )
            item.minAge?.let {
                AgeBadge(it, size = 24.dp, modifier = Modifier.align(Alignment.TopStart).padding(6.dp))
            }
            if (watched) WatchedTick(Modifier.align(Alignment.TopEnd).padding(6.dp))
            val copies = (item as? Movie)?.versions?.size ?: 0
            if (copies > 1) {
                Text(
                    "$copies files",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 10.dp, end = 6.dp)
                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            progress?.let { ResumeBar(it, Modifier.align(Alignment.BottomCenter)) }
        }
        Text(
            item.title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        item.year?.let {
            Text("$it", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun PosterImage(
    path: String?,
    fallbackLetter: String? = null,
    videoFallback: String? = null,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val context = LocalContext.current
    // A picture on a network source shows from its cached copy, fetched once.
    val remote = path != null && !path.startsWith("/")
    val cached by produceState<File?>(null, path) {
        if (remote) value = ArtCache.fileFor(context, path!!)
    }
    val picture = if (remote) cached else path?.let(::File)?.takeIf { it.exists() }
    if (picture != null) {
        AsyncImage(
            model = picture,
            contentDescription = null,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(),
        )
    } else if (videoFallback != null) {
        VideoFrameImage(videoFallback, fallbackLetter = fallbackLetter, contentScale = contentScale)
    } else {
        LetterBox(fallbackLetter)
    }
}

/**
 * A still from the video itself, loaded as a promise through [ThumbnailStore]
 * and cached on disk — the picture a poster would have been.
 */
@Composable
fun VideoFrameImage(videoPath: String, fallbackLetter: String? = null, contentScale: ContentScale = ContentScale.Crop) {
    val context = LocalContext.current
    var frame by remember(videoPath) { mutableStateOf<File?>(null) }
    LaunchedEffect(videoPath) {
        frame = ThumbnailStore.of(context).thumbnailFor(videoPath)
    }
    if (frame != null) {
        AsyncImage(
            model = frame,
            contentDescription = null,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        LetterBox(fallbackLetter)
    }
}

@Composable
private fun LetterBox(fallbackLetter: String?) {
    Box(
        modifier = Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surface)),
        ),
        contentAlignment = Alignment.Center,
    ) {
        fallbackLetter?.let {
            Text(it, fontSize = 48.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
        }
    }
}

/** How the wall lays out its titles. */
enum class WallMode(val label: String) { POSTERS("Posters"), LIST("List"), DETAILS("Details") }

