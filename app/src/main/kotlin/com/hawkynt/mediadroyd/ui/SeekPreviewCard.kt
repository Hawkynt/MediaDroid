package com.hawkynt.mediadroyd.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawkynt.mediadroyd.data.SeekPreviews
import com.hawkynt.mediadroyd.player.SeekPreview

/** The finger on the seek bar (PlayerPanel.onScrub): the moment it points at and where the bar's line runs. */
internal typealias Scrub = com.hawkynt.mediadroyd.player.PlayerPanel.Scrub

/**
 * The picture of the moment under the finger, floating above the seek bar with its time: a picture
 * already kept for a step nearby shows at once, and the right one replaces it when it is taken. Moving
 * on cancels the request for the step left behind.
 */
@Composable
internal fun SeekPreviewCard(scrub: Scrub, durationMs: Long, session: SeekPreviews.Session?, time: String) {
    val bucket = SeekPreview.bucketOf(scrub.positionMs, durationMs)
    var picture by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(session, bucket, durationMs) {
        session ?: return@LaunchedEffect
        session.keptNear(bucket, durationMs)?.let { picture = it }
        session.pictureAt(bucket, durationMs)?.let { picture = it }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .layout { measurable, constraints ->
                val card = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                val fraction = if (durationMs > 0) (scrub.positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                val centre = scrub.lineLeft + fraction * scrub.lineWidth
                val margin = 8.dp.roundToPx()
                val x = (centre - card.width / 2f).toInt()
                    .coerceIn(margin, (constraints.maxWidth - card.width - margin).coerceAtLeast(margin))
                // Above the line, clear of the finger and the dragged scrubber.
                val y = (scrub.lineY - 28.dp.toPx() - card.height).toInt().coerceAtLeast(0)
                layout(constraints.maxWidth, constraints.maxHeight) { card.place(x, y) }
            },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
                .padding(4.dp),
        ) {
            picture?.let {
                Image(
                    it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .width(PICTURE_WIDTH_DP.dp)
                        .aspectRatio(it.width.toFloat() / it.height.coerceAtLeast(1))
                        .clip(RoundedCornerShape(6.dp)),
                )
            }
            Text(
                time,
                color = Color.White,
                fontSize = 15.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

private const val PICTURE_WIDTH_DP = 168
