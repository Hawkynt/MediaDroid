package com.hawkynt.mediadroyd.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawkynt.mediadroyd.engine.Track
import com.hawkynt.mediadroyd.engine.desktop.DesktopNeural
import com.hawkynt.mediadroyd.library.Languages
import com.hawkynt.mediadroyd.player.Keystone
import com.hawkynt.mediadroyd.player.PictureAdjustment
import com.hawkynt.mediadroyd.player.PlaybackMath
import com.hawkynt.mediadroyd.player.ScreenSetup
import com.hawkynt.mediadroyd.player.ScreenShape
import com.hawkynt.mediadroyd.ui.PanelTitle
import kotlin.math.roundToInt

// The desktop's own rows in the player's gear (GearPanel's slots): the sound of this computer's output, the decoder,
// this monitor's setup and its test patterns, the histogram. Everything else in the gear is the phone's, from the same
// domain.

/** Which upscalers offer their networks' models on the desktop: every one on ncnn there (DesktopNeural.onNcnn). */
val DesktopNeuralModels: (PictureAdjustment.Upscaler) -> Boolean = DesktopNeural::onNcnn

/** How a track picker names [track]: its own label, else its language, else null for the picker's own count. */
fun trackName(track: Track): String? =
    track.label?.takeIf { it.isNotBlank() } ?: track.language?.let(Languages::label)

/** Loudness, even loudness, night mode and the audio delay of this computer's output, worded as on Android. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColumnScope.DesktopSoundRows(
    sound: SoundSettings,
    onSound: (SoundSettings) -> Unit,
    nightMode: Boolean,
    onNightMode: (Boolean) -> Unit,
) {
    PanelTitle("Sound")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("Loudness", fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text("%+.0f dB".format(sound.loudnessDb), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Slider(
        value = sound.loudnessDb,
        onValueChange = { onSound(sound.copy(loudnessDb = kotlin.math.round(it))) },
        valueRange = -12f..12f,
    )
    SwitchRow(
        "Even loudness",
        "Every film and song plays about as loud as the last: by its ReplayGain tags where it has them, else " +
            "measured as it plays (EBU R128).",
        sound.evenLoudness,
    ) { onSound(sound.copy(evenLoudness = it)) }
    SwitchRow("Night mode", "Quiet dialogue comes up, loud scenes come down.", nightMode, onNightMode)
    PanelTitle("Audio delay")
    fun nudge(steps: Int) = onSound(sound.copy(audioDelayMs = PlaybackMath.nudgeAudioDelay(sound.audioDelayMs, steps)))
    // Wraps rather than squeezes: a button pressed narrower than its word shows it a letter a line.
    FlowRow(itemVerticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { nudge(-1) }) { Text("Sooner", fontSize = 16.sp) }
        Text(if (sound.audioDelayMs == 0) "in sync" else "%+d ms".format(sound.audioDelayMs), fontSize = 18.sp)
        OutlinedButton(onClick = { nudge(1) }) { Text("Later", fontSize = 16.sp) }
        if (sound.audioDelayMs != 0) {
            TextButton(onClick = { nudge(-sound.audioDelayMs / PlaybackMath.AUDIO_DELAY_STEP_MS) }) { Text("Reset") }
        }
    }
    Hint(
        PlaybackMath.syncText("sound", sound.audioDelayMs).replaceFirstChar { it.uppercase() } +
            ". Later when you hear a word before the lips move, sooner when after. Kept for this computer's output.",
    )
}

/** The histogram over the picture, to set brightness and contrast without clipping (Histogram). */
@Composable
fun ColumnScope.DesktopHistogramRow(shown: Boolean, onShown: (Boolean) -> Unit) {
    SwitchRow(
        "Histogram over the picture",
        "How often each brightness and colour occurs on screen: the darkest peak just right of the left end, " +
            "the brightest just left of the right end.",
        shown,
        Modifier.padding(top = 12.dp),
        onShown,
    )
}

/** Hardware or software decoding first, worded as Android's Settings word it, with the chip's decoder in use. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColumnScope.DesktopDecodingRows(software: Boolean, onSoftware: (Boolean) -> Unit, hardwareDecoder: String?) {
    PanelTitle("Video decoding")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = !software,
            onClick = { onSoftware(false) },
            label = { Text("Hardware (recommended)", fontSize = 15.sp) },
        )
        FilterChip(selected = software, onClick = { onSoftware(true) }, label = { Text("Software", fontSize = 15.sp) })
    }
    Text(
        if (software) {
            "Software decoding works the processor hard and can stutter on large videos. It is meant for videos " +
                "that otherwise play only with errors. From the next video on."
        } else {
            "The graphics chip's own decoder plays the picture (" + (hardwareDecoder ?: "this video: software") +
                "); software decoding steps in for what it cannot play."
        },
        color = if (software) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp,
    )
}

/**
 * The screen the window is on, kept for that screen alone (Settings → Screens): the shape it is seen at, a
 * projector's mirror, flip and keystone, burn-in care — live on the picture behind the panel — and the test patterns
 * to set them by.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColumnScope.DesktopScreenRows(
    screenName: String,
    setup: ScreenSetup,
    onSetup: (ScreenSetup) -> Unit,
    pattern: com.hawkynt.mediadroyd.player.TestPattern?,
    onPattern: (com.hawkynt.mediadroyd.player.TestPattern?) -> Unit,
) {
    PanelTitle(screenName)
    Hint("Only for projectors and special screens; an ordinary monitor needs nothing here.")
    Text("Shape as seen", fontSize = 16.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ScreenShape.entries.forEach { shape ->
            FilterChip(
                selected = setup.shape == shape,
                onClick = { onSetup(setup.copy(shape = shape)) },
                label = { Text(shape.label, fontSize = 15.sp) },
            )
        }
    }
    Hint(
        "For a projector behind an anamorphic lens, or a screen whose pixels are not square: the shape the " +
            "picture is seen at. Pictures are drawn narrower to match, so circles stay round.",
    )
    SwitchRow("Rear projection", "The picture mirrored, for a screen lit from behind.", setup.mirror) {
        onSetup(setup.copy(mirror = it))
    }
    SwitchRow("Ceiling mount", "The picture upside down, for a projector hung from the ceiling.", setup.flip) {
        onSetup(setup.copy(flip = it))
    }
    listOf(
        Triple("Keystone, top and bottom", setup.keystoneVertical) { v: Float ->
            setup.copy(keystoneVertical = v)
        } to ("top narrower" to "bottom narrower"),
        Triple("Keystone, left and right", setup.keystoneHorizontal) { v: Float ->
            setup.copy(keystoneHorizontal = v)
        } to ("right shorter" to "left shorter"),
    ).forEach { (row, sides) ->
        val (label, value, set) = row
        val percent = kotlin.math.abs(value * 100).roundToInt()
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(label, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(
                when {
                    percent == 0 -> "off"
                    value > 0f -> "${sides.first} $percent %"
                    else -> "${sides.second} $percent %"
                },
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = { onSetup(set(if (kotlin.math.abs(it) < 0.01f) 0f else (it * 200).roundToInt() / 200f)) },
            valueRange = -Keystone.LIMIT..Keystone.LIMIT,
        )
    }
    Hint("A projector aimed up throws the picture wider at the top: narrow the top until it is square again.")
    SwitchRow(
        "OLED screen: burn-in care",
        "The picture, its black bars and the subtitles drift a few pixels every half minute, too slowly to see. " +
            "Only for OLED screens.",
        setup.burnInCare,
    ) { onSetup(setup.copy(burnInCare = it)) }
    // Through the stage, with the picture above and this screen's setup; the video paused meanwhile.
    PanelTitle("Test patterns")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = pattern == null, onClick = { onPattern(null) }, label = { Text("Off", fontSize = 15.sp) })
        com.hawkynt.mediadroyd.player.TestPattern.entries.forEach { p ->
            FilterChip(selected = pattern == p, onClick = { onPattern(p) }, label = { Text(p.label, fontSize = 15.sp) })
        }
    }
    Hint(
        pattern?.hint ?: "Black and white levels, grey steps, colour bars, a grid and gamma, to set the picture " +
            "above — and a projector's keystone — by eye.",
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SwitchRow(
    label: String,
    detail: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 16.sp)
            Text(detail, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SwitchRow(label: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) =
    SwitchRow(label, detail, checked, Modifier, onChange)
