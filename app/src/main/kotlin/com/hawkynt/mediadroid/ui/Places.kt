package com.hawkynt.mediadroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawkynt.mediadroid.data.StateStore
import com.hawkynt.mediadroid.library.BrowseFilter
import com.hawkynt.mediadroid.library.Library
import com.hawkynt.mediadroid.library.LibraryQuery
import com.hawkynt.mediadroid.library.LibrarySection
import com.hawkynt.mediadroid.library.Place
import com.hawkynt.mediadroid.ui.theme.focusClickable

/** The two kinds of place the sidebar leads to. */
enum class PlaceKind(val label: String) { GENRES("Genres"), YEARS("Years") }

/**
 * Genres or decades as places: each with its count and a strip of posters, for one section at a time.
 * Tapping one opens that section's wall narrowed to it, through the same filter the chips set.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacesScreen(
    library: Library,
    store: StateStore,
    kind: PlaceKind,
    section: LibrarySection,
    onSectionChange: (LibrarySection) -> Unit,
    onOpen: (LibrarySection, BrowseFilter) -> Unit,
) {
    val sections = LibrarySection.entries.filter { library.itemsIn(it).isNotEmpty() }
    val shown = section.takeIf { it in sections } ?: sections.firstOrNull() ?: section
    val items = LibraryQuery.filterByAge(library.itemsIn(shown), store.data.settings.maxAgeYears)
    val places: List<Pair<String, Place<*>>> = when (kind) {
        PlaceKind.GENRES -> LibraryQuery.genrePlaces(items).map { it.key to it }
        PlaceKind.YEARS -> LibraryQuery.decadePlaces(items).map { "${it.key}s" to it }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
        ) {
            Text(kind.label, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (sections.size > 1) {
                SingleChoiceSegmentedButtonRow {
                    sections.forEachIndexed { i, entry ->
                        SegmentedButton(
                            selected = entry == shown,
                            onClick = { onSectionChange(entry) },
                            shape = SegmentedButtonDefaults.itemShape(i, sections.size),
                            icon = {},
                            label = { Text(sectionLabel(entry), fontSize = 14.sp, maxLines = 1) },
                        )
                    }
                }
            }
        }
        if (places.isEmpty()) {
            Text(
                "Nothing to group here yet.",
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 300.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(places, key = { it.first }) { (label, place) ->
                PlaceCard(label, place) {
                    val filter = when (val key = place.key) {
                        is Int -> BrowseFilter(decade = key)
                        else -> BrowseFilter(genres = setOf(key.toString()))
                    }
                    onOpen(shown, filter)
                }
            }
        }
    }
}

private fun sectionLabel(section: LibrarySection) = when (section) {
    LibrarySection.MOVIES -> "Movies"
    LibrarySection.SHOWS -> "TV Shows"
}

@Composable
private fun PlaceCard(label: String, place: Place<*>, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .focusClickable(shape = shape, onClick = onClick)
            .padding(10.dp),
    ) {
        // The collage: up to four posters side by side; empty slots keep the strip's shape.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(4) { i ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    place.preview.getOrNull(i)?.let {
                        PosterImage(it.posterPath, fallbackLetter = it.title.take(1), contentScale = ContentScale.Crop)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text("${place.count}", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
