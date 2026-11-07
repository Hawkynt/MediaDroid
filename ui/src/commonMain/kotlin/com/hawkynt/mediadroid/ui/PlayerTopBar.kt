package com.hawkynt.mediadroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawkynt.mediadroid.player.AbLoop
import com.hawkynt.mediadroid.ui.resources.Res
import com.hawkynt.mediadroid.ui.resources.ic_photo_camera
import org.jetbrains.compose.resources.painterResource

/**
 * The player's top bar, over a gradient: back, the [title] and a [note] (the sleep timer's), then what the platform
 * adds ([screens]: the phone's second screen and darkening), while [paused] one frame back and on, repeating a part,
 * the chapters, a snapshot, what is playing and the gear.
 */
@Composable
fun PlayerTopBar(
    title: String,
    note: String?,
    paused: Boolean,
    abLoop: AbLoop,
    hasChapters: Boolean,
    onBack: () -> Unit,
    onStepFrame: (forward: Boolean) -> Unit,
    onRepeat: () -> Unit,
    onChapters: () -> Unit,
    onSnapshot: () -> Unit,
    onInfo: () -> Unit,
    onGear: () -> Unit,
    modifier: Modifier = Modifier,
    screens: @Composable RowScope.() -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                color = Color.White,
                fontSize = 18.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            note?.let { Text(it, color = Color.White, fontSize = 15.sp, maxLines = 1) }
            screens()
            if (paused) {
                IconButton(onClick = { onStepFrame(false) }) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "One frame back",
                        tint = Color.White,
                    )
                }
                IconButton(onClick = { onStepFrame(true) }) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "One frame forward",
                        tint = Color.White,
                    )
                }
            }
            TextButton(onClick = onRepeat) {
                Text(
                    when {
                        abLoop.repeating -> "Stop repeating"
                        abLoop.a != null -> "Mark the end"
                        else -> "Repeat a part"
                    },
                    color = if (abLoop.a != null) MaterialTheme.colorScheme.primary else Color.White,
                    fontSize = 15.sp,
                    maxLines = 1,
                )
            }
            if (hasChapters) {
                IconButton(onClick = onChapters) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Chapters", tint = Color.White)
                }
            }
            IconButton(onClick = onSnapshot) {
                Icon(
                    painterResource(Res.drawable.ic_photo_camera),
                    contentDescription = "Take a picture",
                    tint = Color.White,
                )
            }
            IconButton(onClick = onInfo) {
                Icon(Icons.Filled.Info, contentDescription = "What is playing", tint = Color.White)
            }
            IconButton(onClick = onGear) {
                Icon(Icons.Filled.Settings, contentDescription = "Playback and picture", tint = Color.White)
            }
        }
    }
}
