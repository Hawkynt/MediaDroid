package com.hawkynt.mediadroid.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawkynt.mediadroid.R
import com.hawkynt.mediadroid.data.QualityStore
import com.hawkynt.mediadroid.data.SourceAccess
import com.hawkynt.mediadroid.data.StateStore
import com.hawkynt.mediadroid.data.StorageRoot
import com.hawkynt.mediadroid.library.FileEntry
import com.hawkynt.mediadroid.library.FileSort
import com.hawkynt.mediadroid.library.FolderEntry
import com.hawkynt.mediadroid.library.FolderListing
import com.hawkynt.mediadroid.library.Listing
import com.hawkynt.mediadroid.ui.theme.focusClickable
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Storage as it really is, like a file manager: the storage volumes, then folders and videos with
 * their real names. Nothing is grouped or renamed; an NFO beside a video still gives it its title and
 * the usual art its picture. Playing a video queues the folder's videos in the order shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    store: StateStore,
    roots: List<StorageRoot>,
    dir: String?,
    onOpen: (String?) -> Unit,
    onPlay: (queue: List<Playable>, startIndex: Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sort by remember { mutableStateOf(store.filesSort) }
    val listing by produceState<Listing?>(null, dir, sort) {
        // Any source: the device's storage, a share, a WebDAV or cloud folder.
        value = dir?.let {
            withContext(Dispatchers.IO) { SourceAccess.resolve(context, it)?.let { d -> FolderListing.list(d, sort) } }
        }
    }
    // Up one folder; from a volume's top back to the list of volumes.
    val up: () -> Unit = { onOpen(dir?.takeUnless { d -> roots.any { it.path == d } }?.let(::parentOf)) }
    if (dir != null) BackHandler(onBack = up)

    fun play(videos: List<FileEntry>, start: Int) {
        val queue = videos.map {
            Playable(
                title = it.title,
                subtitle = it.name.takeIf { n -> n != it.title },
                videoPath = it.path,
                posterPath = it.picturePath,
                nfoWatched = it.nfoWatched,
            )
        }
        if (queue.isNotEmpty()) onPlay(queue, start)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        ) {
            if (dir != null) {
                IconButton(onClick = up) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up") }
            } else {
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    dir?.let { d -> roots.firstOrNull { it.path == d }?.label ?: nameOf(d) } ?: "Files",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                dir?.let { place(it, roots) }?.takeIf { '›' in it }?.let {
                    Text(
                        it,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.StartEllipsis,
                    )
                }
            }
        }
        if (dir != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                    FileSort.entries.forEachIndexed { i, entry ->
                        SegmentedButton(
                            selected = sort == entry,
                            onClick = {
                                sort = entry
                                store.saveFilesSort(entry)
                            },
                            shape = SegmentedButtonDefaults.itemShape(i, FileSort.entries.size),
                            icon = {},
                            label = { Text(entry.label, fontSize = 14.sp) },
                        )
                    }
                }
                val videos = listing?.videos.orEmpty()
                if (videos.isNotEmpty()) {
                    Spacer(Modifier.width(12.dp))
                    FilledTonalIconButton(onClick = { play(videos, 0) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Play this folder")
                    }
                }
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (dir == null) {
                items(roots, key = { it.path }) { root -> RootRow(root, onClick = { onOpen(root.path) }) }
            } else {
                val current = listing
                if (current != null && current.folders.isEmpty() && current.videos.isEmpty()) {
                    item { Text("No folders or videos here.", fontSize = 16.sp, modifier = Modifier.padding(8.dp)) }
                }
                items(current?.folders.orEmpty(), key = { it.path }) { folder ->
                    FolderRow(
                        folder,
                        onClick = { onOpen(folder.path) },
                        onPlay = {
                            scope.launch {
                                val videos = withContext(Dispatchers.IO) {
                                    SourceAccess.resolve(context, folder.path)
                                        ?.let { FolderListing.list(it, sort).videos }
                                        .orEmpty()
                                }
                                play(videos, 0)
                            }
                        },
                    )
                }
                val videos = current?.videos.orEmpty()
                items(videos, key = { it.path }) { video ->
                    VideoRow(video, store, onClick = { play(videos, videos.indexOf(video)) })
                }
            }
        }
    }
}

/** Where [dir] is, said from its volume: `Internal storage › Movies › Kids`. */
private fun place(dir: String, roots: List<StorageRoot>): String {
    val root = roots.filter { dir.startsWith(it.path) }.maxByOrNull { it.path.length } ?: return dir
    val rest = dir.removePrefix(root.path).trim('/').split('/').filter { it.isNotEmpty() }
    return (listOf(root.label) + rest).joinToString(" › ")
}

@Composable
private fun RootRow(root: StorageRoot, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = rowModifier(onClick).padding(14.dp),
    ) {
        Icon(painterResource(R.drawable.ic_folder_open), contentDescription = null, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(root.label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(root.path, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FolderRow(folder: FolderEntry, onClick: () -> Unit, onPlay: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = rowModifier(onClick).padding(8.dp)) {
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 60.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            if (folder.coverPath != null) {
                PosterImage(folder.coverPath, contentScale = ContentScale.Crop)
            } else {
                Icon(painterResource(R.drawable.ic_folder_open), contentDescription = null)
            }
        }
        Spacer(Modifier.width(14.dp))
        Text(
            folder.name,
            fontSize = 17.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onPlay) { Icon(Icons.Filled.PlayArrow, contentDescription = "Play ${folder.name}") }
    }
}

@Composable
private fun VideoRow(video: FileEntry, store: StateStore, onClick: () -> Unit) {
    val context = LocalContext.current
    val duration by produceState<Long?>(null, video.path) {
        value = QualityStore.of(context).durationMsFor(video.path)
    }
    val watched = store.isWatched(video.path, video.nfoWatched)
    val progress = store.resumeFor(video.path)?.let { progressOf(it.positionMs, it.durationMs) }?.takeIf { it > 0f }
    Row(modifier = rowModifier(onClick).padding(8.dp)) {
        Box(
            modifier = Modifier
                .width(128.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            PosterImage(video.picturePath, videoFallback = video.path, contentScale = ContentScale.Crop)
            if (watched) WatchedTick(Modifier.align(Alignment.TopEnd).padding(4.dp), size = 20.dp)
            progress?.let { ResumeBar(it, Modifier.align(Alignment.BottomCenter)) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                video.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // The real file name stays in sight when an NFO gave the video a title.
            if (video.title != video.name.substringBeforeLast('.')) {
                Text(
                    video.name,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
            val facts = listOfNotNull(
                duration?.let { clock(it) },
                video.sizeBytes.takeIf { it >= 1024 }?.let { fileSize(it) },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                video.minAge?.let {
                    AgeBadge(it, size = 18.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(facts.joinToString("  ·  "), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            QualityChips(rememberQuality(video.path, video.quality))
        }
    }
}

@Composable
private fun rowModifier(onClick: () -> Unit): Modifier {
    val shape = RoundedCornerShape(10.dp)
    return Modifier
        .fillMaxWidth()
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .focusClickable(shape = shape, onClick = onClick)
}

/** `1:42:05` or `42:05`. */
private fun clock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** The folder above a location: a path's parent, or a URI without its last part. */
private fun parentOf(location: String): String? = if (location.startsWith("/")) {
    File(location).parent
} else {
    location.trimEnd('/').substringBeforeLast('/', "").ifEmpty { null }
}

/** The last part of a location, decoded for a URI. */
private fun nameOf(location: String): String =
    if (location.startsWith("/")) File(location).name else Uri.decode(location.trimEnd('/').substringAfterLast('/'))

