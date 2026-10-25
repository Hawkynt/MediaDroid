package com.hawkynt.mediadroid.ui

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawkynt.mediadroid.data.StateStore
import com.hawkynt.mediadroid.library.Episode
import com.hawkynt.mediadroid.library.Movie
import com.hawkynt.mediadroid.library.Show
import java.io.File

@Composable
fun DetailsScreen(
    item: com.hawkynt.mediadroid.library.MediaItem,
    store: StateStore,
    onBack: () -> Unit,
    onPlayMovie: (Movie) -> Unit,
    onPlayShow: (Show, Episode?) -> Unit,
) {
    val seasons = if (item is Show) item.seasons.sortedBy { it.number } else emptyList()
    var selected by remember { mutableStateOf(seasons.lastOrNull()?.number ?: 1) }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Box {
                val backdrop = when (item) {
                    is Movie -> item.fanartPath ?: item.posterPath
                    is Show -> item.fanartPath ?: item.posterPath
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    PosterImage(backdrop, contentScale = ContentScale.Crop)
                }
                IconButton(onClick = onBack, modifier = Modifier.padding(8.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        }
        item {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(item.title, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                val subtitle = buildString {
                    item.year?.let { append("$it  ·  ") }
                    append(item.genres.joinToString(" · "))
                }
                if (subtitle.isNotBlank()) {
                    Text(subtitle, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(16.dp))

                when (item) {
                    is Movie -> MovieActions(item, store, onPlayMovie)
                    is Show -> ShowHeaderActions(item, store, onPlayShow)
                }

                val plot = when (item) {
                    is Movie -> item.plot
                    is Show -> item.plot
                }
                plot?.let {
                    Spacer(Modifier.height(16.dp))
                    Text(it, fontSize = 17.sp)
                }
                if (item is Movie && item.cast.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Cast: ${item.cast.take(8).joinToString(", ")}",
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (item is Show) {
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    seasons.forEach { season ->
                        FilterChip(
                            selected = season.number == selected,
                            onClick = { selected = season.number },
                            label = { Text("Season ${season.number}", fontSize = 16.sp) },
                        )
                    }
                }
            }
            val episodes = seasons.firstOrNull { it.number == selected }?.episodes ?: emptyList()
            items(episodes, key = { it.videoPath }) { episode ->
                EpisodeRow(
                    season = selected,
                    episode = episode,
                    store = store,
                    onClick = { onPlayShow(item, episode) },
                )
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun MovieActions(movie: Movie, store: StateStore, onPlay: (Movie) -> Unit) {
    val watched = store.isWatched(movie.videoPath) || movie.playcount > 0
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = { onPlay(movie) },
            modifier = Modifier.height(64.dp),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (store.resumeFor(movie.videoPath) != null) "Resume" else "Play", fontSize = 22.sp)
        }
        Spacer(Modifier.width(16.dp))
        IconButton(
            onClick = { store.setWatched(movie.videoPath, !watched) },
            modifier = Modifier
                .width(56.dp)
                .height(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Icon(
                if (watched) Icons.Filled.CheckCircle else Icons.Filled.Check,
                contentDescription = if (watched) "Watched" else "Mark watched",
            )
        }
    }
    movie.runtimeMinutes?.let {
        Spacer(Modifier.height(8.dp))
        Text("$it minutes", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ShowHeaderActions(show: Show, store: StateStore, onPlay: (Show, Episode?) -> Unit) {
    Button(
        onClick = { onPlay(show, null) },
        modifier = Modifier.height(64.dp),
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Play next", fontSize = 22.sp)
    }
}

@Composable
private fun EpisodeRow(season: Int, episode: Episode, store: StateStore, onClick: () -> Unit) {
    val watched = episode.watched || store.isWatched(episode.videoPath)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(120.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (episode.thumbPath != null && File(episode.thumbPath).exists()) {
                PosterImage(episode.thumbPath, contentScale = ContentScale.Crop)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "E%02d — ${episode.title ?: "Episode ${episode.number}"}".format(episode.number),
                fontSize = 17.sp,
                fontWeight = if (watched) FontWeight.Normal else FontWeight.SemiBold,
            )
            episode.firstAired?.let {
                Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (watched) {
            Icon(Icons.Filled.CheckCircle, contentDescription = "Watched", modifier = Modifier.width(28.dp))
        }
    }
}
