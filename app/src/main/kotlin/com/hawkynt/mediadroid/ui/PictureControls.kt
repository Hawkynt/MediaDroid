package com.hawkynt.mediadroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.player.PictureControl

/**
 * The four picture sliders, neutral in the middle, then the filters: sharpness and clarity from off
 * upwards, and noise from removed to added grain. [onChange] fires while dragging so the picture follows live; [onDone]
 * when a slider is let go or a step is picked, the moment to save.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PictureSliders(
    picture: PictureAdjustment,
    onChange: (PictureAdjustment) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    /** Offer one eye of a 3D video; a choice for one video, not a household default. */
    stereo: Boolean = false,
    /** Offer Real-ESRGAN: the household's self-test found the device fast enough (see NeuralUpscaling). */
    neural: Boolean = false,
    /** The neural networks to offer under smooth motion: their self-tests passed (see NeuralInterpolation). */
    neuralMotion: Set<com.hawkynt.mediadroid.player.PictureAdjustment.Interpolation> = emptySet(),
) {
    Column(modifier = modifier) {
        PictureControl.entries.forEach { control ->
            val value = control.get(picture)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(control.label, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text(
                    if (control == PictureControl.BRIGHTNESS) "%+.2f".format(value) else "%.2f×".format(value),
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = control.positionOf(value),
                onValueChange = { onChange(control.set(picture, control.valueAt(it))) },
                valueRange = -1f..1f,
                onValueChangeFinished = onDone,
            )
        }
        Text(
            "Filters",
            fontSize = 16.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp),
        )
        listOf(
            Triple("Sharpness", picture.sharpness) { v: Float -> picture.copy(sharpness = v) },
            Triple("Clarity", picture.clarity) { v: Float -> picture.copy(clarity = v) },
        ).forEach { (label, value, set) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(label, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text(
                    if (value == 0f) "off" else "%.0f %%".format(value * 100),
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(value = value, onValueChange = { onChange(set(it)) }, onValueChangeFinished = onDone)
        }
        // One slider for noise: left removes it, right adds film grain, the middle leaves it alone.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Noise", fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(
                when {
                    picture.noise < 0f -> "removed %.0f %%".format(-picture.noise * 100)
                    picture.noise > 0f -> "grain %.0f %%".format(picture.noise * 100)
                    else -> "as it is"
                },
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = picture.noise,
            onValueChange = { onChange(picture.copy(noise = if (kotlin.math.abs(it) < 0.03f) 0f else it)) },
            valueRange = -1f..1f,
            onValueChangeFinished = onDone,
        )
        Text("Deinterlace", fontSize = 16.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PictureAdjustment.Deinterlace.entries.forEach { mode ->
                FilterChip(
                    selected = picture.deinterlace == mode,
                    onClick = {
                        onChange(picture.copy(deinterlace = mode))
                        onDone()
                    },
                    label = { Text(mode.label, fontSize = 15.sp) },
                )
            }
            // YADIF and BWDIF can show both fields of a frame, each as a picture of its own.
            if (picture.deinterlace.isTemporal) {
                FilterChip(
                    selected = picture.fieldRate,
                    onClick = {
                        onChange(picture.copy(fieldRate = !picture.fieldRate))
                        onDone()
                    },
                    label = { Text("Every field", fontSize = 15.sp) },
                )
            }
        }
        Text("Upscaling", fontSize = 16.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PictureAdjustment.Upscaler.entries.filter {
                it != PictureAdjustment.Upscaler.NEURAL || neural || picture.upscaler == it
            }.forEach { upscaler ->
                FilterChip(
                    selected = picture.upscaler == upscaler,
                    onClick = {
                        onChange(picture.copy(upscaler = upscaler))
                        onDone()
                    },
                    label = { Text(upscaler.label, fontSize = 15.sp) },
                )
            }
        }
        // How much of the upscaler shows: 0 % plain scaling, 100 % the upscaler alone.
        if (picture.upscaler != PictureAdjustment.Upscaler.STANDARD) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Upscaler strength", fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text(
                    if (picture.upscalerStrength <= 0f) "off" else "%.0f %%".format(picture.upscalerStrength * 100),
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = picture.upscalerStrength,
                onValueChange = { onChange(picture.copy(upscalerStrength = (it * 20).toInt() / 20f)) },
                valueRange = 0f..1f,
                onValueChangeFinished = onDone,
            )
        }
        Text("Smooth motion", fontSize = 16.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PictureAdjustment.Interpolation.entries.filter {
                !it.isNeural || it in neuralMotion || picture.interpolation == it
            }.forEach { strength ->
                FilterChip(
                    selected = picture.interpolation == strength,
                    onClick = {
                        onChange(picture.copy(interpolation = strength))
                        onDone()
                    },
                    label = { Text(strength.label, fontSize = 15.sp) },
                )
            }
        }
        if (stereo) {
            Text("3D as 2D: one eye", fontSize = 16.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PictureAdjustment.Stereo.entries.forEach { eye ->
                    FilterChip(
                        selected = picture.stereo == eye,
                        onClick = {
                            onChange(picture.copy(stereo = eye))
                            onDone()
                        },
                        label = { Text(eye.label, fontSize = 15.sp) },
                    )
                }
            }
        }
        Text(
            "Filters run on the graphics chip; HDR videos skip them.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
