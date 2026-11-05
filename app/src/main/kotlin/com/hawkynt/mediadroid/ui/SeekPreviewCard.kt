package com.hawkynt.mediadroid.ui

import android.graphics.Bitmap
import android.view.View
import androidx.annotation.OptIn
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import com.hawkynt.mediadroid.data.SeekPreviews
import com.hawkynt.mediadroid.player.SeekPreview

/**
 * The finger on Media3's seek bar: the moment it points at and where the bar's line runs, in the player
 * view's pixels, so a picture can float above that spot.
 */
internal data class Scrub(val positionMs: Long, val lineLeft: Float, val lineWidth: Float, val lineY: Float)

/** Reports every move of a finger (or a remote's keys) along the seek bar, and null when it lets go. */
@OptIn(UnstableApi::class)
internal fun PlayerView.onScrub(report: (Scrub?) -> Unit) {
    val bar = findViewById<View>(androidx.media3.ui.R.id.exo_progress) ?: return
    val timeBar = bar as? TimeBar ?: return
    val playerView = this
    fun at(position: Long): Scrub {
        val barAt = IntArray(2).also(bar::getLocationInWindow)
        val viewAt = IntArray(2).also(playerView::getLocationInWindow)
        // Media3 draws the line inside the bar's padding, inset by half the dragged scrubber.
        val inset = bar.paddingLeft + SCRUBBER_HALF_DP * bar.resources.displayMetrics.density
        val left = barAt[0] - viewAt[0] + inset
        val width = bar.width - bar.paddingLeft - bar.paddingRight - 2 * SCRUBBER_HALF_DP *
            bar.resources.displayMetrics.density
        return Scrub(position, left, width.coerceAtLeast(1f), barAt[1] - viewAt[1] + bar.height / 2f)
    }
    timeBar.addListener(
        object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) = report(at(position))

            override fun onScrubMove(timeBar: TimeBar, position: Long) = report(at(position))

            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) = report(null)
        },
    )
}

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

private const val SCRUBBER_HALF_DP = 12
private const val PICTURE_WIDTH_DP = 168
