package com.hawkynt.mediadroid.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawkynt.mediadroid.R
import com.hawkynt.mediadroid.library.LibrarySection
import com.hawkynt.mediadroid.ui.theme.focusClickable

private val CollapsedWidth = 80.dp
private val ExpandedWidth = 232.dp

/**
 * The left-hand menu the streaming apps share: Movies and TV Shows at the top, the tools below.
 * Expanded it shows labels; collapsed it is an icon rail. The toggle sits where the eye expects
 * it, at the very top.
 */
@Composable
fun Sidebar(
    expanded: Boolean,
    current: Screen,
    scanning: Boolean,
    onToggle: () -> Unit,
    onNavigate: (Screen) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    resume: ResumeSpot? = null,
) {
    val width by animateDpAsState(if (expanded) ExpandedWidth else CollapsedWidth, label = "sidebar-width")
    Column(modifier = modifier.width(width).background(MaterialTheme.colorScheme.surface)) {
    Column(
        modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp, horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(56.dp)) {
            SidebarButton(
                painter = painterResource(if (expanded) R.drawable.ic_menu_open else R.drawable.ic_menu),
                label = null,
                description = if (expanded) "Collapse menu" else "Expand menu",
                selected = false,
                onClick = onToggle,
            )
            AnimatedVisibility(expanded, enter = fadeIn(), exit = fadeOut()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(8.dp))
                    Image(painterResource(R.drawable.logo_mark), contentDescription = null, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("MediaDroid", fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        SidebarItem(
            painter = painterResource(R.drawable.ic_movie),
            label = "Movies",
            expanded = expanded,
            selected = (current as? Screen.Browse)?.section == LibrarySection.MOVIES,
            onClick = { onNavigate(Screen.Browse(LibrarySection.MOVIES)) },
        )
        SidebarItem(
            painter = painterResource(R.drawable.ic_tv),
            label = "TV Shows",
            expanded = expanded,
            selected = (current as? Screen.Browse)?.section == LibrarySection.SHOWS,
            onClick = { onNavigate(Screen.Browse(LibrarySection.SHOWS)) },
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline)

        SidebarItem(
            painter = rememberVectorPainter(Icons.Filled.Search),
            label = "Search",
            expanded = expanded,
            selected = current == Screen.Search,
            onClick = { onNavigate(Screen.Search) },
        )
        SidebarItem(
            painter = painterResource(R.drawable.ic_filter_list),
            label = "Filters",
            expanded = expanded,
            selected = current == Screen.Filters,
            onClick = { onNavigate(Screen.Filters) },
        )
        SidebarItem(
            painter = rememberVectorPainter(Icons.Filled.Lock),
            label = "Settings",
            expanded = expanded,
            selected = current == Screen.Settings,
            onClick = { onNavigate(Screen.Settings) },
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline)

        if (scanning) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.height(56.dp).padding(horizontal = 16.dp),
            ) {
                CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(24.dp))
                if (expanded) {
                    Spacer(Modifier.width(16.dp))
                    Text("Reading folders…", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            SidebarItem(
                painter = rememberVectorPainter(Icons.Filled.Refresh),
                label = "Refresh",
                expanded = expanded,
                selected = false,
                onClick = onRefresh,
            )
        }
    }
    // The last thing played, one tap from resuming it, on every screen.
    resume?.let { ResumeCard(it, expanded) }
    }
}

@Composable
private fun SidebarItem(
    painter: Painter,
    label: String,
    expanded: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    SidebarButton(painter, if (expanded) label else null, label, selected, onClick, Modifier.fillMaxWidth())
}

@Composable
private fun SidebarButton(
    painter: Painter,
    label: String?,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    val tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .height(56.dp)
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .focusClickable(shape = shape, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
            Icon(painter, contentDescription = if (label == null) description else null, tint = tint, modifier = Modifier.size(24.dp))
            if (label != null) {
                Spacer(Modifier.width(16.dp))
                Text(
                    label,
                    fontSize = 18.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

/** The video played last, as the sidebar offers it for resuming. */
data class ResumeSpot(
    val title: String,
    val subtitle: String?,
    val picturePath: String?,
    val videoPath: String,
    val progress: Float?,
    val onResume: () -> Unit,
)

@Composable
private fun ResumeCard(spot: ResumeSpot, expanded: Boolean) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .focusClickable(shape = shape, onClick = spot.onResume),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().aspectRatio(if (expanded) 16f / 9f else 1f),
            contentAlignment = Alignment.Center,
        ) {
            PosterImage(spot.picturePath, videoFallback = spot.videoPath, contentScale = ContentScale.Crop)
            Box(
                modifier = Modifier.size(40.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Resume ${spot.title}", tint = Color.White)
            }
        }
        spot.progress?.let {
            LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().height(3.dp))
        }
        if (expanded) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    spot.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                spot.subtitle?.let {
                    Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}
