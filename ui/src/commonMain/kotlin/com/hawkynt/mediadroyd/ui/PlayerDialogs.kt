package com.hawkynt.mediadroyd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** The file's chapters with where each begins, the playing one marked; picking one plays from its start. */
@Composable
fun ChapterDialog(
    chapters: List<com.hawkynt.mediadroyd.library.Chapter>,
    current: Int?,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
) {
    val ordered = chapters.indices.sortedBy { chapters[it].startMs }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", fontSize = 18.sp) } },
        title = { Text("Chapters") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ordered.forEach { i ->
                    val playing = i == current
                    TextButton(onClick = { onPick(chapters[i].startMs) }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            com.hawkynt.mediadroyd.player.ChapterSteps.name(chapters, i),
                            fontSize = 20.sp,
                            fontWeight = if (playing) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        Text(clock(chapters[i].startMs), fontSize = 18.sp)
                    }
                }
            }
        },
    )
}

/** Lists the audio or subtitle tracks of the current item; "Automatic" clears the pick. */
@Composable
fun TrackDialog(
    tracks: List<com.hawkynt.mediadroyd.engine.Track>,
    kind: com.hawkynt.mediadroyd.engine.Track.Kind,
    labelFor: (com.hawkynt.mediadroyd.engine.Track) -> String? = { null },
    /** What the first choice, no track of its own, is called. */
    noneLabel: String = "Automatic",
    /** The track shown marked as the one playing; none by default. */
    marked: (com.hawkynt.mediadroyd.engine.Track) -> Boolean = { false },
    onDismiss: () -> Unit,
    onPick: (com.hawkynt.mediadroyd.engine.Track?) -> Unit,
) {
    val choices = com.hawkynt.mediadroyd.engine.Tracks.of(tracks, kind)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", fontSize = 18.sp) } },
        text = {
            Column {
                TextButton(onClick = { onPick(null) }) { Text(noneLabel, fontSize = 20.sp) }
                choices.forEachIndexed { i, track ->
                    val label = labelFor(track) ?: track.label ?: track.language ?: "Track ${i + 1}"
                    TextButton(onClick = { onPick(track) }) {
                        Text(
                            buildString {
                                if (marked(track)) append("✓ ")
                                append(label)
                                track.language?.let { append("  ($it)") }
                            },
                            fontSize = 20.sp,
                        )
                    }
                }
                if (choices.isEmpty()) Text("This file has only one such track.", fontSize = 18.sp)
            }
        },
    )
}

/**
 * A short confirmation over the picture — "+15 s", "Volume 40 %", a chapter's name — in the accent colour where
 * [accent] (a level past the device's maximum).
 */
@Composable
fun FeedbackNote(text: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    Text(
        text,
        color = if (accent) com.hawkynt.mediadroyd.ui.theme.MediaDroydColors.Amber else Color.White,
        fontSize = 22.sp,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp, vertical = 10.dp),
    )
}

/**
 * The level a volume or brightness swipe set ([level], 0 to 2): a bar filling from the bottom, 100 % at the mark in its
 * middle — the device's range white below it, the boost above it in the accent colour — so the stretch still empty
 * above the mark shows there is more to swipe for.
 */
@Composable
fun LevelBar(level: Float, modifier: Modifier = Modifier) {
    val fill = com.hawkynt.mediadroyd.player.PlaybackMath.levelFill(level)
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(LEVEL_BAR_WIDTH / 2)
    Box(
        modifier = modifier
            .size(LEVEL_BAR_WIDTH, LEVEL_BAR_HEIGHT)
            .background(Color.Black.copy(alpha = 0.6f), shape)
            .padding(3.dp),
    ) {
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
            // Top to bottom: the boost still empty, the boost filled, the device's range empty, then filled.
            val emptyBoost = 0.5f - fill.boost
            val emptyDevice = 0.5f - fill.device
            if (emptyBoost > 0f) androidx.compose.foundation.layout.Spacer(Modifier.weight(emptyBoost))
            if (fill.boost > 0f) {
                Box(
                    Modifier.fillMaxWidth().weight(fill.boost)
                        .background(com.hawkynt.mediadroyd.ui.theme.MediaDroydColors.Amber),
                )
            }
            // The device's maximum: a mark across the middle.
            Box(Modifier.fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.8f)))
            if (emptyDevice > 0f) androidx.compose.foundation.layout.Spacer(Modifier.weight(emptyDevice))
            if (fill.device > 0f) Box(Modifier.fillMaxWidth().weight(fill.device).background(Color.White))
        }
    }
}

private val LEVEL_BAR_WIDTH = 14.dp
private val LEVEL_BAR_HEIGHT = 220.dp

/** `1:23:45` or `23:45` for a position in milliseconds. */
fun clock(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = total / 60 % 60
    val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** How long the lock symbol must be held to unlock the player. */
private const val UNLOCK_HOLD_MS = 2_000L

/** How long the lock symbol stays up after a touch. */
private const val LOCK_HINT_MS = 2_500L

/**
 * The locked player: a layer over everything that swallows every touch. Any touch (or a [stamp] change
 * from a key or Back) shows the lock symbol for a moment; holding it for [UNLOCK_HOLD_MS] unlocks.
 */
@Composable
fun LockOverlay(stamp: Int, onTouch: () -> Unit, onUnlock: () -> Unit) {
    var visible by remember { mutableStateOf(true) }
    var holding by remember { mutableStateOf(false) }
    LaunchedEffect(stamp, holding) {
        visible = true
        if (!holding) {
            delay(LOCK_HINT_MS)
            visible = false
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        if (event.changes.any { it.pressed }) onTouch()
                    }
                }
            },
    ) {
        if (visible) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(96.dp)
                    .background(Color.Black.copy(alpha = if (holding) 0.85f else 0.6f), CircleShape)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown()
                            holding = true
                            val released = withTimeoutOrNull(UNLOCK_HOLD_MS) { waitForUpOrCancellation() }
                            holding = false
                            if (released == null) onUnlock()
                        }
                    },
            ) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = "Hold to unlock",
                    tint = Color.White,
                    modifier = Modifier.size(48.dp),
                )
            }
        }
    }
}
