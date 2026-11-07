package com.hawkynt.mediadroid.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawkynt.mediadroid.player.PlaybackChain

/** Lane colours: the picture blue, the sound orange, the subtitles green. */
private fun PlaybackChain.Lane.color(): Color = when (this) {
    PlaybackChain.Lane.PICTURE -> Color(0xFF64B5F6)
    PlaybackChain.Lane.SOUND -> Color(0xFFFFB74D)
    PlaybackChain.Lane.SUBTITLES -> Color(0xFF81C784)
}

private fun PlaybackChain.Lane.label(): String = when (this) {
    PlaybackChain.Lane.PICTURE -> "Picture"
    PlaybackChain.Lane.SOUND -> "Sound"
    PlaybackChain.Lane.SUBTITLES -> "Subtitles"
}

/**
 * How a video plays, drawn as a graph in the manner of an audio player's signal path: a numbered card
 * per file read, then a lane each for picture, sound and subtitles — its stages as cards joined by
 * arrows, ending in its output. Side by side where the width allows, one under the other otherwise.
 */
@Composable
fun ChainGraph(graph: PlaybackChain.Graph) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        graph.inputs.forEachIndexed { index, input ->
            val lanes = graph.paths.filter { it.input == index }.map { it.lane }
            InputCard(index, input, lanes)
        }
        BoxWithConstraints {
            if (maxWidth >= 560.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    graph.paths.forEach { LaneColumn(it, multipleInputs = graph.inputs.size > 1, Modifier.weight(1f)) }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    graph.paths.forEach {
                        LaneColumn(it, multipleInputs = graph.inputs.size > 1, Modifier.fillMaxWidth())
                    }
                }
            }
        }
        Text(
            graph.engine,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp).align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun InputCard(index: Int, input: PlaybackChain.Input, lanes: List<PlaybackChain.Lane>) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(26.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("${index + 1}", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(input.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("${input.readFrom} · ${input.demuxer}", fontSize = 12.sp)
            }
            // What this file feeds: a dot in each of its lanes' colours.
            lanes.forEach { lane ->
                Box(Modifier.padding(start = 4.dp).size(10.dp).background(lane.color(), CircleShape))
            }
        }
    }
}

@Composable
private fun LaneColumn(path: PlaybackChain.Path, multipleInputs: Boolean, modifier: Modifier) {
    val color = path.lane.color()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Arrow(color)
        Text(
            path.lane.label() + if (multipleInputs) " · from ${path.input + 1}" else "",
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
        path.nodes.forEach { node ->
            Arrow(color)
            NodeCard(node, color, filled = false)
        }
        Arrow(color)
        NodeCard(path.output, color, filled = true)
    }
}

@Composable
private fun NodeCard(node: PlaybackChain.Node, color: Color, filled: Boolean) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (filled) color.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, color),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(node.title.uppercase(), color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(node.detail, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** A short downward arrow in the lane's colour, joining two stages. */
@Composable
private fun Arrow(color: Color) {
    Canvas(Modifier.width(16.dp).height(16.dp)) {
        val x = size.width / 2
        drawLine(color, Offset(x, 0f), Offset(x, size.height - 5.dp.toPx()), strokeWidth = 2.dp.toPx())
        val head = Path().apply {
            moveTo(x - 5.dp.toPx(), size.height - 6.dp.toPx())
            lineTo(x + 5.dp.toPx(), size.height - 6.dp.toPx())
            lineTo(x, size.height)
            close()
        }
        drawPath(head, color)
    }
}
