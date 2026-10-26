package com.hawkynt.mediadroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.hawkynt.mediadroid.data.StateStore
import com.hawkynt.mediadroid.data.ThumbnailStore
import com.hawkynt.mediadroid.library.BrowseFilter
import com.hawkynt.mediadroid.library.Library
import com.hawkynt.mediadroid.library.LibraryQuery
import com.hawkynt.mediadroid.library.LibrarySection
import com.hawkynt.mediadroid.library.MediaItem
import com.hawkynt.mediadroid.library.Movie
import com.hawkynt.mediadroid.library.Show
import com.hawkynt.mediadroid.ui.theme.focusClickable
import java.io.File

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
    onOpen: (MediaItem) -> Unit,
    onPlay: (MediaItem) -> Unit,
    onClearFilter: () -> Unit,
) {
    val maxAge = store.data.settings.maxAgeYears
    val items = LibraryQuery.browse(library.itemsIn(section), filter, maxAge)
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
    val filtered = filter != BrowseFilter()

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (featured != null && !filtered) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "hero") {
                Hero(featured, onPlay = { onPlay(featured) }, onInfo = { onOpen(featured) })
            }
        }
        if (continueItems.isNotEmpty() && !filtered) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "continue") {
                Column {
                    RowTitle("Continue watching")
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(continueItems, key = { it.playable.videoPath }) { entry ->
                            ContinueCard(entry, onClick = { onPlay(entry.item) })
                        }
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                RowTitle(if (filtered) "$sectionName · filtered" else "All $sectionName")
                Text(
                    "${items.size}",
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
                if (filtered) {
                    Spacer(Modifier.width(16.dp))
                    AssistChip(onClick = onClearFilter, label = { Text("Clear filters", fontSize = 15.sp) }, modifier = Modifier.padding(top = 16.dp))
                }
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
            PosterCard(
                item = item,
                watched = item is Movie && store.isWatched(item.videoPath),
                onClick = { onOpen(item) },
            )
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
@Composable
private fun Hero(item: MediaItem, onPlay: () -> Unit, onInfo: () -> Unit) {
    val background = MaterialTheme.colorScheme.background
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val height = min(maxWidth * 9 / 16, 460.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            PosterImage(
                item.fanartPath ?: item.posterPath,
                videoFallback = videoOf(item),
                contentScale = ContentScale.Crop,
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0.35f to Color.Transparent, 1f to background),
                ),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(0f to background.copy(alpha = 0.85f), 0.6f to Color.Transparent),
                ),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .widthIn(max = 560.dp)
                    .padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
            ) {
                Text(
                    item.title,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(
                    item.year?.toString(),
                    (item as? Show)?.seasons?.size?.let { if (it == 1) "1 season" else "$it seasons" },
                    (item as? Movie)?.runtimeMinutes?.let { "$it min" },
                    item.genres.take(3).joinToString(" · ").ifEmpty { null },
                ).joinToString("  ·  ")
                if (meta.isNotEmpty()) {
                    Text(meta, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val plot = when (item) {
                    is Movie -> item.plot
                    is Show -> item.plot
                }
                plot?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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

private fun videoOf(item: MediaItem): String? = when (item) {
    is Movie -> item.videoPath
    is Show -> item.seasons.firstOrNull()?.episodes?.firstOrNull()?.videoPath
}

data class ContinueEntry(val item: MediaItem, val playable: Playable, val progress: Float)

@Composable
private fun rememberContinueItems(library: Library, store: StateStore): List<ContinueEntry> {
    val resume = store.data.resume
    val maxAge = store.data.settings.maxAgeYears
    return resume.entries.mapNotNull { (path, info) ->
        val movie = library.movies.firstOrNull { it.videoPath == path }
        if (movie != null) {
            ContinueEntry(movie, movie.playable(), progressOf(info.positionMs, info.durationMs))
        } else {
            val show = library.shows.firstOrNull { s -> s.seasons.any { se -> se.episodes.any { it.videoPath == path } } }
            val episode = show?.seasons?.flatMap { it.episodes }?.firstOrNull { it.videoPath == path }
            if (show != null && episode != null) {
                ContinueEntry(
                    item = show,
                    playable = Playable(episode.title ?: episode.number.toString(), show.title, path, show.posterPath),
                    progress = progressOf(info.positionMs, info.durationMs),
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

private fun progressOf(positionMs: Long, durationMs: Long): Float =
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
            PosterImage(entry.playable.posterPath, videoFallback = entry.playable.videoPath, contentScale = ContentScale.Crop)
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Color.White)
            }
            LinearProgressIndicator(
                progress = { entry.progress },
                trackColor = Color.Black.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(5.dp),
            )
        }
        Text(
            entry.playable.title,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        entry.playable.subtitle?.let {
            Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
fun PosterCard(item: MediaItem, onClick: () -> Unit, watched: Boolean = false, modifier: Modifier = Modifier) {
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
            if (watched) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(26.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "Watched",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
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
    if (path != null && File(path).exists()) {
        AsyncImage(
            model = File(path),
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
