package com.hawkynt.mediadroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.hawkynt.mediadroid.data.StateStore
import com.hawkynt.mediadroid.data.ThumbnailStore
import com.hawkynt.mediadroid.library.Library
import com.hawkynt.mediadroid.library.LibraryQuery
import com.hawkynt.mediadroid.library.MediaItem
import com.hawkynt.mediadroid.library.Movie
import com.hawkynt.mediadroid.library.Show
import java.io.File

@Composable
fun BrowseScreen(
    library: Library,
    store: StateStore,
    filter: FilterState,
    showsOnly: Boolean,
    onOpen: (MediaItem) -> Unit,
    onPlay: (MediaItem) -> Unit,
) {
    var items = if (showsOnly) library.shows.toList() else library.movies.toList()
    items = LibraryQuery.filterByAge(items, store.data.settings.maxAgeYears)
    if (filter.genres.isNotEmpty()) items = LibraryQuery.filterByGenres(items, filter.genres)
    if (filter.decade != null) items = LibraryQuery.filterByYear(items, filter.decade, filter.decade + 9)
    items = when (filter.sort) {
        Sort.TITLE -> items.sortedBy { it.title.lowercase() }
        Sort.YEAR_NEW -> items.sortedByDescending { it.year ?: 0 }
        Sort.YEAR_OLD -> items.sortedBy { it.year ?: 9999 }
    }

    val continueItems = rememberContinueItems(library, store)

    Column(modifier = Modifier.fillMaxSize()) {
        if (continueItems.isNotEmpty()) {
            Text(
                "Continue watching",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
            )
            LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
                items(continueItems, key = { it.playable.videoPath }) { entry ->
                    ContinueCard(entry, onClick = { onPlay(entry.item) })
                }
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 130.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items, key = { it.path }) { item ->
                PosterCard(item, onClick = { onOpen(item) })
            }
        }
    }
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
            } else null
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
    Column(
        modifier = Modifier
            .padding(end = 12.dp)
            .width(200.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            PosterImage(entry.playable.posterPath, videoFallback = entry.playable.videoPath, contentScale = ContentScale.Crop)
            Column(modifier = Modifier.align(Alignment.BottomCenter)) {
                LinearProgressIndicator(
                    progress = { entry.progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
            }
        }
        Text(entry.playable.title, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        entry.playable.subtitle?.let {
            Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun PosterCard(item: MediaItem, onClick: () -> Unit) {
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            PosterImage(
                item.posterPath,
                fallbackLetter = item.title.take(1),
                videoFallback = when (item) {
                    is Movie -> item.videoPath
                    is Show -> item.seasons.firstOrNull()?.episodes?.firstOrNull()?.videoPath
                    else -> null
                },
            )
        }
        Text(
            item.title,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
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
        modifier = Modifier.fillMaxSize().background(Color.DarkGray),
        contentAlignment = Alignment.Center,
    ) {
        fallbackLetter?.let {
            Text(it, fontSize = 48.sp, color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}
