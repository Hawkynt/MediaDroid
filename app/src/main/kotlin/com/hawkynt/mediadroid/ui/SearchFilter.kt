package com.hawkynt.mediadroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawkynt.mediadroid.library.Library
import com.hawkynt.mediadroid.library.LibraryQuery
import com.hawkynt.mediadroid.library.MediaItem

@Composable
fun SearchScreen(library: Library, store: com.hawkynt.mediadroid.data.StateStore, onOpen: (MediaItem) -> Unit) {
    var query by remember { mutableStateOf("") }
    val results = LibraryQuery.search(library.items, query)

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search titles") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 130.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(results, key = { it.path }) { item ->
                PosterCard(item, onClick = { onOpen(item) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FiltersScreen(library: Library, filter: FilterState, onFilterChange: (FilterState) -> Unit) {
    val facets = LibraryQuery.genreFacets(library.items)
    val decades = LibraryQuery.groupByYear(library.items).keys.filter { it >= 0 }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Genres", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            facets.forEach { (genre, count) ->
                FilterChip(
                    selected = genre in filter.genres,
                    onClick = {
                        val next = if (genre in filter.genres) filter.genres - genre else filter.genres + genre
                        onFilterChange(filter.copy(genres = next))
                    },
                    label = { Text("$genre ($count)", fontSize = 16.sp) },
                )
            }
        }

        Text("Years", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 24.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = filter.decade == null,
                onClick = { onFilterChange(filter.copy(decade = null)) },
                label = { Text("All", fontSize = 16.sp) },
            )
            decades.forEach { decade ->
                FilterChip(
                    selected = filter.decade == decade,
                    onClick = { onFilterChange(filter.copy(decade = decade)) },
                    label = { Text("${decade}s", fontSize = 16.sp) },
                )
            }
        }

        Text("Sort", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 24.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Sort.entries.forEach { sort ->
                FilterChip(
                    selected = filter.sort == sort,
                    onClick = { onFilterChange(filter.copy(sort = sort)) },
                    label = {
                        Text(
                            when (sort) {
                                Sort.TITLE -> "Title A–Z"
                                Sort.YEAR_NEW -> "Newest first"
                                Sort.YEAR_OLD -> "Oldest first"
                            },
                            fontSize = 16.sp,
                        )
                    },
                )
            }
        }

        Text(
            "Filters apply on the Browse screen.",
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 24.dp),
        )
    }
}
