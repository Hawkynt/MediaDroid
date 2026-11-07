package com.hawkynt.mediadroid.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawkynt.mediadroid.player.PlaybackStatistics
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

// The overlay's colours: a dark panel over the picture, text in two inks, the two series from the reference
// palette's dark steps (validated against the panel: CVD ΔE 26.8, both at least 3:1), and the reserved
// "critical" status colour for late frames, always with its label.
private val Panel = Color(0xE01A1A19)
private val Ink = Color(0xFFF2F1EE)
private val MutedInk = Color(0xFFB4B2AC)
private val Grid = Color(0x33F2F1EE)
private val Series1 = Color(0xFF3987E5)
private val Series2 = Color(0xFFD95926)
private val Critical = Color(0xFFD03B3B)

/**
 * The statistics overlay (Info › Advanced › Statistics): how playback has been going over the last few
 * seconds — frame times from the decoder against the times pictures stay on screen, jitter and judder,
 * the graphics thread's work per frame against the time between frames, the processor's work per frame
 * against its budget, and Motion's search scale. Redrawn four times a
 * second from [statistics]; it lets every touch through to the player below.
 */
@Composable
fun StatisticsOverlay(statistics: PlaybackStatistics, modifier: Modifier = Modifier) {
    var snapshot by remember(statistics) { mutableStateOf(statistics.snapshot()) }
    LaunchedEffect(statistics) {
        while (true) {
            delay(REFRESH_MS)
            snapshot = statistics.snapshot()
        }
    }
    val s = snapshot
    Column(
        modifier
            .width(300.dp)
            .background(Panel, RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Statistics", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "decoder %.1f fps · screen %.1f /s".format(s.framesPerSecond, s.shownPerSecond),
            color = Ink,
            fontSize = 12.sp,
        )
        Text(
            "jitter %.1f ms · judder %.0f %% · dropped %d".format(s.jitterMs, s.judderPercent, s.dropped),
            color = Ink,
            fontSize = 12.sp,
        )
        if (s.late > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(Critical, RoundedCornerShape(2.dp)))
                Text("  late %d".format(s.late), color = Ink, fontSize = 12.sp)
            }
        }

        Text("Frame time and time on screen, ms", color = MutedInk, fontSize = 11.sp)
        Legend(listOf("decoder" to Series1, "screen" to Series2))
        LineChart(listOf(s.frameTimesMs to Series1, s.shownTimesMs to Series2), guide = null)

        if (s.graphicsMs.isNotEmpty()) {
            val between = if (s.frameTimesMs.isNotEmpty()) s.frameTimesMs.average().toFloat() else null
            Text("Graphics thread per frame, ms (dashed: the time between frames)", color = MutedInk, fontSize = 11.sp)
            LineChart(listOf(s.graphicsMs to Series1), guide = between)
        }
        if (s.workMs.isNotEmpty()) {
            val budget = if (s.frameTimesMs.isNotEmpty()) s.frameTimesMs.average().toFloat() / 2f else null
            Text("Processor per frame, ms (dashed: the search's budget)", color = MutedInk, fontSize = 11.sp)
            LineChart(listOf(s.workMs to Series1), guide = budget)
        }
        if (s.scales.isNotEmpty()) {
            Text("Motion's search, picture size: now 1/%d".format(s.scales.last()), color = MutedInk, fontSize = 11.sp)
            ScaleChart(s.scales)
        }
    }
}

@Composable
private fun Legend(entries: List<Pair<String, Color>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        entries.forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 10.dp, height = 3.dp).background(color, RoundedCornerShape(2.dp)))
                Text("  $label", color = Ink, fontSize = 11.sp)
            }
        }
    }
}

/** Lines over the last samples, newest on the right, from 0 to a round top written at the top left. */
@Composable
private fun LineChart(series: List<Pair<FloatArray, Color>>, guide: Float?) {
    val highest = max(series.maxOf { (values, _) -> values.maxOrNull() ?: 0f }, guide ?: 0f)
    val top = niceTop(highest)
    Box(Modifier.fillMaxWidth().height(56.dp)) {
        Canvas(Modifier.fillMaxWidth().height(56.dp)) {
            val stroke = 2.dp.toPx()
            drawLine(Grid, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            drawLine(Grid, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
            guide?.let {
                val y = size.height * (1f - it / top)
                drawLine(
                    MutedInk, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                )
            }
            series.forEach { (values, color) ->
                if (values.size < 2) return@forEach
                val step = size.width / (SAMPLES - 1)
                val first = SAMPLES - values.size
                val path = Path()
                values.forEachIndexed { i, v ->
                    val x = (first + i) * step
                    val y = size.height * (1f - v.coerceIn(0f, top) / top)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        Text("%.0f".format(top), color = MutedInk, fontSize = 10.sp, modifier = Modifier.padding(start = 2.dp))
    }
}

/** Motion's search scale as steps, from the whole picture (bottom) to a 32nd (top). */
@Composable
private fun ScaleChart(scales: IntArray) {
    Box(Modifier.fillMaxWidth().height(36.dp)) {
        Canvas(Modifier.fillMaxWidth().height(36.dp)) {
            drawLine(Grid, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            if (scales.isEmpty()) return@Canvas
            val step = size.width / (SAMPLES - 1)
            val first = SAMPLES - scales.size
            val path = Path()
            var previousY = 0f
            scales.forEachIndexed { i, scale ->
                val level = (ln(scale.toDouble()) / ln(2.0)).toFloat()
                val y = size.height * (1f - level / LEVELS)
                val x = (first + i) * step
                if (i == 0) {
                    path.moveTo(x, y)
                } else {
                    // A step: along at the old scale, then up or down to the new one.
                    path.lineTo(x, previousY)
                    path.lineTo(x, y)
                }
                previousY = y
            }
            drawPath(path, Series1, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        Text("1/32", color = MutedInk, fontSize = 10.sp, modifier = Modifier.padding(start = 2.dp))
    }
}

/** A round top for an axis holding [value] with a little room: 10, 20, 50, 100 … at least 10. */
private fun niceTop(value: Float): Float {
    val v = max(value * 1.1f, 10f)
    val magnitude = 10.0.pow(floor(log10(v.toDouble()))).toFloat()
    return listOf(1f, 2f, 5f, 10f).first { it * magnitude >= v } * magnitude
}

private const val REFRESH_MS = 250L

/** How many samples the charts span: PlaybackStatistics' default capacity. */
private const val SAMPLES = 240

/** The scale steps above the whole picture: 2, 4, 8, 16, 32. */
private const val LEVELS = 5f
