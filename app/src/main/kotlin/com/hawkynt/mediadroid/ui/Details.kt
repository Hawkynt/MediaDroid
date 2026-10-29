package com.hawkynt.mediadroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawkynt.mediadroid.data.StateStore
import com.hawkynt.mediadroid.library.Episode
import com.hawkynt.mediadroid.library.Movie
import com.hawkynt.mediadroid.library.Show
import com.hawkynt.mediadroid.ui.theme.focusClickable
import java.io.File

/** The largest share of the screen height the backdrop on top of the details may take. */
private const val BACKDROP_SHARE = 0.4f

@Composable
fun DetailsScreen(
    item: com.hawkynt.mediadroid.library.MediaItem,
    store: StateStore,
    onBack: () -> Unit,
    onPlayMovie: (Movie) -> Unit,
    onPlayShow: (Show, Episode?) -> Unit,
    onPlayTrailer: (Movie) -> Unit = {},
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
                // 16:9 across the width, but never more than [BACKDROP_SHARE] of the screen height: in
                // landscape a full-width backdrop is taller than the screen and hides that the page goes on.
                val screenHeight = LocalConfiguration.current.screenHeightDp.dp
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(minOf(maxWidth * 9f / 16f, screenHeight * BACKDROP_SHARE))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        PosterImage(
                            backdrop,
                            videoFallback = when (item) {
                                is Movie -> item.videoPath
                                is Show -> item.representativeVideo
                            },
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                IconButton(onClick = onBack, modifier = Modifier.padding(8.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        }
        item {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(item.title, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                AwayNote(item)
                val subtitle = buildString {
                    item.year?.let { append("$it  ·  ") }
                    append(item.genres.joinToString(" · "))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    item.minAge?.let {
                        AgeBadge(it)
                        Spacer(Modifier.width(10.dp))
                    }
                    if (subtitle.isNotBlank()) {
                        Text(subtitle, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val rating = when (item) {
                    is Movie -> item.rating
                    is Show -> item.rating
                }
                val premiered = when (item) {
                    is Movie -> item.premiered
                    is Show -> item.premiered
                }
                val dates = listOfNotNull(
                    rating?.let { "★ %.1f".format(it) },
                    premiered?.let { releaseDate(it) },
                )
                if (dates.isNotEmpty()) {
                    Text(
                        dates.joinToString("  ·  "),
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (item is Movie) {
                    LanguageFlags(item.audioLanguages, item.subtitleLanguages, Modifier.padding(top = 8.dp))
                }
                Spacer(Modifier.height(16.dp))

                when (item) {
                    is Movie -> MovieActions(item, store, onPlayMovie, onPlayTrailer)
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
                val credits = listOfNotNull(
                    (item as? Movie)?.directors?.ifEmpty { null }?.let { "Director: ${it.joinToString(", ")}" },
                    (item as? Movie)?.cast?.ifEmpty { null }?.let { "Cast: ${it.take(8).joinToString(", ")}" },
                    when (item) {
                        is Movie -> item.studios
                        is Show -> item.studios
                    }.ifEmpty { null }?.let { "Studio: ${it.joinToString(", ")}" },
                )
                if (credits.isNotEmpty()) Spacer(Modifier.height(16.dp))
                credits.forEach {
                    Text(it, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (item is Movie) FileSection(item.videoPath)
            }
        }
        if (item is Show) {
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(androidx.compose.foundation.rememberScrollState())
                        .padding(horizontal = 16.dp),
                ) {
                    seasons.forEach { season ->
                        FilterChip(
                            selected = season.number == selected,
                            onClick = { selected = season.number },
                            label = { Text(seasonName(season.number), fontSize = 16.sp) },
                        )
                    }
                }
            }
            val season = seasons.firstOrNull { it.number == selected }
            val episodes = season?.episodes ?: emptyList()
            item {
                SeasonHeader(
                    season?.number ?: selected,
                    episodes.size,
                    season?.posterPath ?: season?.representativeThumb,
                    season?.representativeVideo,
                    watched = season != null && store.marks.isWatched(season),
                    plot = season?.plot,
                    onToggleWatched = {
                        season?.let {
                            store.setWatched(it.episodes.map { e -> e.videoPath }, !store.marks.isWatched(it))
                        }
                    },
                )
            }
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
private fun MovieActions(movie: Movie, store: StateStore, onPlay: (Movie) -> Unit, onTrailer: (Movie) -> Unit) {
    val watched = store.marks.isWatched(movie)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = { onPlay(movie) },
            modifier = Modifier.height(64.dp),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (movie.allParts.any { store.resumeFor(it) != null }) "Resume" else "Play", fontSize = 22.sp)
        }
        if (movie.trailerPath != null) {
            Spacer(Modifier.width(12.dp))
            FilledTonalButton(onClick = { onTrailer(movie) }, modifier = Modifier.height(64.dp)) {
                Text("Trailer", fontSize = 18.sp)
            }
        }
        Spacer(Modifier.width(16.dp))
        WatchedToggle(watched, onToggle = { store.setWatched(movie.allParts, !watched) })
    }
    val facts = listOfNotNull(
        movie.runtimeMinutes?.let { "$it minutes" },
        movie.parts.size.takeIf { it > 1 }?.let { "$it parts, played one after the other" },
    )
    if (facts.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(facts.joinToString("  ·  "), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (movie.versions.size > 1) {
        Spacer(Modifier.height(16.dp))
        Text("${movie.versions.size} files", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        movie.versions.forEach { version -> VersionRow(version, onClick = { onPlay(version) }) }
    }
}

/** One file of a film found several times: what its picture and sound are, its languages and size. */
@Composable
private fun VersionRow(version: Movie, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .focusClickable(shape = shape, onClick = onClick)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            java.io.File(version.videoPath).name,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        QualityChips(rememberQuality(version.videoPath, version.quality))
        LanguageFlags(version.audioLanguages, version.subtitleLanguages)
        version.sizeBytes?.let {
            Text(fileSize(it), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A size as people read it: `4.7 GB`, `700 MB`. */
internal fun fileSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%d MB".format(bytes shr 20)
    else -> "%d KB".format(bytes shr 10)
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
    val watched = store.isWatched(episode.videoPath, episode.watched)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(168.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (episode.thumbPath != null && File(episode.thumbPath).exists()) {
                PosterImage(episode.thumbPath, contentScale = ContentScale.Crop)
            } else {
                // No scraped `-thumb.jpg`: pull a still out of the video itself.
                VideoFrameImage(episode.videoPath)
            }
            // What the file itself holds, probed once and cached; the NFO only when the file cannot be read.
            val context = androidx.compose.ui.platform.LocalContext.current
            val probed by androidx.compose.runtime.produceState<com.hawkynt.mediadroid.nfo.StreamDetails?>(
                null,
                episode.videoPath,
            ) { value = com.hawkynt.mediadroid.data.QualityStore.of(context).detailsFor(episode.videoPath) }
            val details = probed
            ThumbBadges(
                audio = details?.audio?.mapNotNull { it.language }?.distinct()?.ifEmpty { null }
                    ?: episode.audioLanguages,
                subtitles = episode.subtitleLanguages,
                quality = details?.let { com.hawkynt.mediadroid.library.Quality.of(it) } ?: episode.quality,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Episode ${episode.number}",
                fontSize = 17.sp,
                fontWeight = if (watched) FontWeight.Normal else FontWeight.SemiBold,
                maxLines = 1,
            )
            episode.title?.let {
                Text(
                    it,
                    fontSize = 15.sp,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            episode.firstAired?.let {
                Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            episode.plot?.let {
                Text(
                    it,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        WatchedToggle(watched, onToggle = { store.setWatched(listOf(episode.videoPath), !watched) }, size = 48.dp)
    }
}

/** The watched tick: filled when watched, a plain check to tick it off; tapping toggles. */
@Composable
private fun WatchedToggle(watched: Boolean, onToggle: () -> Unit, size: Dp = 56.dp) {
    IconButton(
        onClick = onToggle,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Icon(
            if (watched) Icons.Filled.CheckCircle else Icons.Filled.Check,
            contentDescription = if (watched) "Watched" else "Mark watched",
        )
    }
}

/** The selected season: its own cover when the folder has one, and how many episodes it holds. */
@Composable
private fun SeasonHeader(
    number: Int,
    episodeCount: Int,
    posterPath: String?,
    videoPath: String?,
    watched: Boolean,
    onToggleWatched: () -> Unit,
    plot: String? = null,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (posterPath != null || videoPath != null) {
                Box(
                    modifier = Modifier
                        .width(72.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    PosterImage(posterPath, videoFallback = videoPath, contentScale = ContentScale.Crop)
                }
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(seasonName(number), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (episodeCount == 1) "1 episode" else "$episodeCount episodes",
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (episodeCount > 0) WatchedToggle(watched, onToggle = onToggleWatched)
        }
        // What the season is about, from its season.nfo.
        plot?.let { Text(it, fontSize = 16.sp, modifier = Modifier.padding(top = 10.dp)) }
    }
}

/** Season 0 holds the specials, the Kodi convention. */
private fun seasonName(number: Int): String = if (number == 0) "Specials" else "Season $number"

/** `2017-10-06` in the device's own date style; the text as it stands when it is no full date. */
private fun releaseDate(premiered: String): String = runCatching {
    java.time.LocalDate.parse(premiered.take(10))
        .format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))
}.getOrDefault(premiered)

/**
 * The file behind a film, folded away until asked for: where it is, its size and container, every
 * video, audio and subtitle track, and which decoder plays each here.
 */
@Composable
private fun FileSection(location: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var open by remember(location) { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Spacer(Modifier.height(16.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(shape)
            .focusClickable(shape = shape, onClick = { open = !open })
            .padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        Text("File", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(6.dp))
        Text(if (open) "▲" else "▼", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (!open) return
    val size by androidx.compose.runtime.produceState<Long?>(null, location) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.hawkynt.mediadroid.data.SourceAccess.resolve(context, location)?.length()
        }
    }
    val tracks by androidx.compose.runtime.produceState<List<com.hawkynt.mediadroid.data.TrackReport>?>(
        null,
        location,
    ) {
        value = com.hawkynt.mediadroid.data.FileReport.tracks(context, location) ?: emptyList()
    }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Text(location, fontSize = 14.sp, color = dim)
    Text(
        listOfNotNull(location.substringAfterLast('.', "").uppercase().ifEmpty { null }, size?.let(::fileSize))
            .joinToString(" · "),
        fontSize = 14.sp,
        color = dim,
    )
    val list = tracks
    if (list == null) {
        Text("Reading the file…", fontSize = 14.sp, color = dim)
        return
    }
    if (list.isEmpty()) Text("The tracks cannot be read.", fontSize = 14.sp, color = dim)
    list.forEach { track ->
        val kind = when (track.type) {
            androidx.media3.common.C.TRACK_TYPE_VIDEO -> "Video"
            androidx.media3.common.C.TRACK_TYPE_AUDIO -> "Audio"
            androidx.media3.common.C.TRACK_TYPE_TEXT -> "Subtitles"
            else -> "Other"
        }
        Text("$kind: ${describe(track.format)}", fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

