package com.hawkynt.mediadroid.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawkynt.mediadroid.player.Histogram

/**
 * The picture's [histogram] over it, beside the picture controls: luma in white, the colour differences Cb in light
 * blue and Cr in pink, on a log scale, each above a dotted yellow line that spans the 256 levels — as DScaler's
 * Histogram filter draws it. Levels outside the video range are dimmer, the ends red where pixels pile up clipped.
 */
@Composable
fun HistogramOverlay(histogram: Histogram?, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(8.dp),
    ) {
        Text("Histogram", color = Color.White, fontSize = 13.sp)
        val shown = histogram ?: Histogram(IntArray(256), IntArray(256), IntArray(256), 0)
        Graph(shown.luma, Color.White, Histogram.LUMA_RANGE)
        Graph(shown.cb, Color(0xFF9AD7FF), Histogram.CHROMA_RANGE)
        Graph(shown.cr, Color(0xFFFFA8D8), Histogram.CHROMA_RANGE)
        // Black bars and night scenes are rightly at full black: the share is told, not judged.
        Text(
            if (shown.samples == 0) {
                "Waiting for a picture…"
            } else {
                "At full black %.1f %% · at full white %.1f %%"
                    .format(shown.clippedBlack * 100, shown.clippedWhite * 100)
            },
            color = Color.White,
            fontSize = 12.sp,
        )
    }
}

/** One channel's [counts], log scale, dimmed outside [range], the ends marked where pixels pile up. */
@Composable
private fun Graph(counts: IntArray, colour: Color, range: IntRange) {
    Canvas(Modifier.width(256.dp).height(48.dp).padding(top = 4.dp)) {
        val rows = (size.height - 3f).toInt().coerceAtLeast(1)
        val heights = Histogram.heights(counts, rows)
        val column = size.width / counts.size
        for (level in counts.indices) {
            if (heights[level] == 0) continue
            val clipped = (level == 0 || level == counts.size - 1) && counts[level] > 0
            val tint = when {
                clipped -> Color(0xFFFF5252)
                level in range -> colour
                else -> colour.copy(alpha = 0.45f)
            }
            val x = level * column + column / 2
            drawLine(tint, Offset(x, rows.toFloat()), Offset(x, (rows - heights[level]).toFloat()), column)
        }
        drawLine(
            Color(0xFFFFEB3B),
            Offset(0f, size.height - 1f),
            Offset(size.width, size.height - 1f),
            strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 2f)),
        )
    }
}
