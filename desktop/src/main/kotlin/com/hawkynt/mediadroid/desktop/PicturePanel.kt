package com.hawkynt.mediadroid.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawkynt.mediadroid.engine.Track
import com.hawkynt.mediadroid.library.Languages
import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.player.PictureControl

/** What the desktop stage runs so far (GlStage); the rest of the Android choices follow. */
val DesktopDeinterlacers = PictureAdjustment.Deinterlace.entries.filter { it != PictureAdjustment.Deinterlace.FILM }
val DesktopUpscalers = listOf(
    PictureAdjustment.Upscaler.STANDARD,
    PictureAdjustment.Upscaler.LANCZOS,
    PictureAdjustment.Upscaler.FSR,
    PictureAdjustment.Upscaler.EWA,
    PictureAdjustment.Upscaler.NIS,
    PictureAdjustment.Upscaler.GSR,
)

/** How a track picker names [track]: its own label, else its language, else its place among its kind ([number]). */
fun trackName(track: Track, number: Int): String =
    track.label?.takeIf { it.isNotBlank() } ?: track.language?.let(Languages::label) ?: "Track $number"

/**
 * The player's panel: the sound and subtitle [tracks] to pick from ([onPick] with null: subtitles off), then the
 * Android gear's picture controls, from the same domain (PictureControl, enums).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PicturePanel(
    picture: PictureAdjustment,
    onChange: (PictureAdjustment) -> Unit,
    modifier: Modifier = Modifier,
    tracks: List<Track> = emptyList(),
    onPick: (Track.Kind, String?) -> Unit = { _, _ -> },
) {
    Column(
        modifier.width(380.dp).fillMaxHeight().background(Color(0xF01A1A2E)).verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val sound = tracks.filter { it.kind == Track.Kind.AUDIO }
        val subtitles = tracks.filter { it.kind == Track.Kind.TEXT }
        if (sound.size > 1) {
            Text("Sound", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                sound.forEachIndexed { i, track ->
                    FilterChip(
                        selected = track.selected,
                        onClick = { onPick(Track.Kind.AUDIO, track.id) },
                        label = { Text(trackName(track, i + 1)) },
                    )
                }
            }
        }
        if (subtitles.isNotEmpty()) {
            Text("Subtitles", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = subtitles.none { it.selected },
                    onClick = { onPick(Track.Kind.TEXT, null) },
                    label = { Text("Off") },
                )
                subtitles.forEachIndexed { i, track ->
                    FilterChip(
                        selected = track.selected,
                        onClick = { onPick(Track.Kind.TEXT, track.id) },
                        label = { Text(trackName(track, i + 1)) },
                    )
                }
            }
        }
        Text("Picture", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
        PictureControl.entries.forEach { control ->
            val value = control.get(picture)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(control.label, color = Color.White, modifier = Modifier.weight(1f))
                Text(
                    if (control == PictureControl.BRIGHTNESS) "%+.2f".format(value) else "%.2f×".format(value),
                    color = Color.Gray,
                )
            }
            Slider(
                value = control.positionOf(value),
                onValueChange = { onChange(control.set(picture, control.valueAt(it))) },
                valueRange = -1f..1f,
            )
        }
        listOf(
            Triple("Sharpness", picture.sharpness) { v: Float -> picture.copy(sharpness = v) },
            Triple("Clarity", picture.clarity) { v: Float -> picture.copy(clarity = v) },
        ).forEach { (label, value, set) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(label, color = Color.White, modifier = Modifier.weight(1f))
                Text(if (value == 0f) "off" else "%.0f %%".format(value * 100), color = Color.Gray)
            }
            Slider(value = value, onValueChange = { onChange(set(it)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Noise", color = Color.White, modifier = Modifier.weight(1f))
            Text(
                when {
                    picture.noise < 0f -> "removed %.0f %%".format(-picture.noise * 100)
                    picture.noise > 0f -> "grain %.0f %%".format(picture.noise * 100)
                    else -> "as it is"
                },
                color = Color.Gray,
            )
        }
        Slider(
            value = picture.noise,
            onValueChange = { onChange(picture.copy(noise = if (kotlin.math.abs(it) < 0.03f) 0f else it)) },
            valueRange = -1f..1f,
        )
        Text("Deinterlace", color = Color.White, modifier = Modifier.padding(top = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DesktopDeinterlacers.forEach { mode ->
                FilterChip(
                    selected = picture.deinterlace == mode,
                    onClick = { onChange(picture.copy(deinterlace = mode)) },
                    label = { Text(mode.label) },
                )
            }
        }
        Text(picture.deinterlace.description, color = Color.Gray, fontSize = 13.sp)
        Text("Upscaling", color = Color.White, modifier = Modifier.padding(top = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DesktopUpscalers.forEach { upscaler ->
                FilterChip(
                    selected = picture.upscaler == upscaler,
                    onClick = { onChange(picture.copy(upscaler = upscaler)) },
                    label = { Text(upscaler.label) },
                )
            }
        }
        Text(picture.upscaler.description, color = Color.Gray, fontSize = 13.sp)
    }
}
