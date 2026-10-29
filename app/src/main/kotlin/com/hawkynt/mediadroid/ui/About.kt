package com.hawkynt.mediadroid.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawkynt.mediadroid.library.Episode
import com.hawkynt.mediadroid.library.MediaItem
import com.hawkynt.mediadroid.library.Movie
import com.hawkynt.mediadroid.library.Show

/**
 * What a film, show, episode or file is about, as a long press shows it: the title, a line of facts
 * (year, length, genres), the description and the people and studios behind it, and where it comes
 * from — the NFO or the file's own tags.
 */
data class About(
    val title: String,
    val line: String? = null,
    val plot: String? = null,
    val facts: List<String> = emptyList(),
    val source: String? = null,
)

fun aboutOf(item: MediaItem): About = when (item) {
    is Movie -> About(
        title = item.title,
        line = listOfNotNull(
            item.year?.toString(),
            item.runtimeMinutes?.let { "$it min" },
            item.genres.takeIf { it.isNotEmpty() }?.joinToString(", "),
        ).joinToString(" · ").ifEmpty { null },
        plot = item.plot,
        facts = listOfNotNull(
            item.directors.takeIf { it.isNotEmpty() }?.let { "Directed by ${it.joinToString(", ")}" },
            item.cast.takeIf { it.isNotEmpty() }?.let { "With ${it.take(5).joinToString(", ")}" },
            item.studios.takeIf { it.isNotEmpty() }?.joinToString(", "),
            item.rating?.let { "Rated %.1f / 10".format(it) },
        ),
        source = if (item.plot != null) "From the NFO" else null,
    )
    is Show -> About(
        title = item.title,
        line = listOfNotNull(
            item.year?.toString(),
            "${item.seasons.size} season" + if (item.seasons.size == 1) "" else "s",
            item.genres.takeIf { it.isNotEmpty() }?.joinToString(", "),
        ).joinToString(" · "),
        plot = item.plot,
        facts = listOfNotNull(
            item.studios.takeIf { it.isNotEmpty() }?.joinToString(", "),
            item.rating?.let { "Rated %.1f / 10".format(it) },
        ),
        source = if (item.plot != null) "From the NFO" else null,
    )
}

fun aboutOf(episode: Episode, season: Int, show: String): About = About(
    title = episode.title ?: "Episode ${episode.number}",
    line = "$show · S%02d · E%02d".format(season, episode.number),
    plot = episode.plot,
    source = if (episode.plot != null) "From the NFO" else null,
)

/** The About sheet. */
@Composable
fun AboutDialog(about: About, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(about.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                about.line?.let {
                    Text(it, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    about.plot ?: "Nothing is written about it yet.",
                    fontSize = 16.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
                about.facts.forEach { Text(it, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp)) }
                about.source?.let {
                    Text(
                        it,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
    )
}
