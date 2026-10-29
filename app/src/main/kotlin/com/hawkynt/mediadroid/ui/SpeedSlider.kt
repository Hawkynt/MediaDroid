package com.hawkynt.mediadroid.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawkynt.mediadroid.player.PlaybackMath

/**
 * Playback speed from 0.2× to 5× on one slider, logarithmic so 1× sits in the middle, in steps of
 * 0.05× (see [PlaybackMath.speedAt]); the value beside it, and a button back to 1×.
 */
@Composable
fun SpeedSlider(speed: Float, onSpeed: (Float) -> Unit, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Text(speedLabel(speed), fontSize = 18.sp, modifier = Modifier.widthIn(min = 64.dp))
        Slider(
            value = PlaybackMath.speedPosition(speed),
            onValueChange = { onSpeed(PlaybackMath.speedAt(it)) },
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onSpeed(1f) }, enabled = speed != 1f) { Text("1×", fontSize = 16.sp) }
    }
}

/** `1×`, `1.75×`, `0.2×`. */
fun speedLabel(speed: Float): String =
    String.format(java.util.Locale.ROOT, "%.2f", speed).trimEnd('0').trimEnd('.') + "×"
