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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.player.PictureCheck
import com.hawkynt.mediadroid.player.PictureControl

/**
 * The four picture sliders, neutral in the middle, then the filters: sharpness, clarity and debanding from off
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
    /**
     * Tries a choice of deinterlacing, upscaling or smooth motion before it is taken (see pictureCheck); a choice it
     * refuses is not taken, and why shows below its row. Takes everything by default.
     */
    check: PictureTry = { _, _ -> PictureVerdict(true) },
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
            // Smooths the steps an 8-bit gradient shows (a sky, a fade to black), see Deband.
            Triple("Debanding", picture.deband) { v: Float -> picture.copy(deband = v) },
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
        val take = { taken: PictureAdjustment ->
            onChange(taken)
            onDone()
        }
        val deinterlace = remember { Trial() }
        Text("Deinterlace", fontSize = 16.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PictureAdjustment.Deinterlace.entries.forEach { mode ->
                FilterChip(
                    selected = picture.deinterlace == mode,
                    enabled = deinterlace.trying == null,
                    onClick = { deinterlace.pick(picture, picture.copy(deinterlace = mode), mode.label, check, take) },
                    label = { Text(mode.label, fontSize = 15.sp) },
                )
            }
            // YADIF and BWDIF can show both fields of a frame, each as a picture of its own.
            if (picture.deinterlace.isTemporal) {
                FilterChip(
                    selected = picture.fieldRate,
                    enabled = deinterlace.trying == null,
                    onClick = {
                        val fields = picture.copy(fieldRate = !picture.fieldRate)
                        deinterlace.pick(picture, fields, "Every field", check, take)
                    },
                    label = { Text("Every field", fontSize = 15.sp) },
                )
            }
        }
        deinterlace.Below(
            if (picture.isFieldRate) "Both fields of every frame shown, each as a picture: motion as the camera saw it."
            else picture.deinterlace.description,
        )
        val upscaling = remember { Trial() }
        Text("Upscaling", fontSize = 16.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PictureAdjustment.Upscaler.entries.forEach { upscaler ->
                FilterChip(
                    selected = picture.upscaler == upscaler,
                    enabled = upscaling.trying == null,
                    onClick = {
                        upscaling.pick(picture, picture.copy(upscaler = upscaler), upscaler.label, check, take)
                    },
                    label = { Text(upscaler.label, fontSize = 15.sp) },
                )
            }
        }
        upscaling.Below(picture.upscaler.description)
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
        val motion = remember { Trial() }
        Text("Smooth motion", fontSize = 16.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PictureAdjustment.Interpolation.entries.forEach { strength ->
                FilterChip(
                    selected = picture.interpolation == strength,
                    enabled = motion.trying == null,
                    onClick = {
                        motion.pick(picture, picture.copy(interpolation = strength), strength.label, check, take)
                    },
                    label = { Text(strength.label, fontSize = 15.sp) },
                )
            }
        }
        motion.Below(picture.interpolation.description)
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

/**
 * One row's choice being tried: which ([trying], what the check says meanwhile), or why the last one was refused.
 */
private class Trial {
    var trying by mutableStateOf<String?>(null)
    var status by mutableStateOf("")
    var refused by mutableStateOf<String?>(null)
    private var job: kotlinx.coroutines.Job? = null

    /**
     * Picks [candidate] (labelled [label]) over [current]: taken at once where it only takes work away
     * (PictureCheck.needed), else tried with [check] first and taken ([take]) only where the device keeps up.
     */
    fun pick(
        current: PictureAdjustment,
        candidate: PictureAdjustment,
        label: String,
        check: PictureTry,
        take: (PictureAdjustment) -> Unit,
    ) {
        if (trying != null || PictureCheck.key(current) == PictureCheck.key(candidate)) return
        refused = null
        if (!PictureCheck.needed(current, candidate)) {
            take(candidate)
            return
        }
        trying = label
        status = "Trying $label on this device…"
        job = kotlinx.coroutines.MainScope().launch {
            val verdict = check(candidate) { said -> launch { status = "$label: $said" } }
            trying = null
            if (verdict.keepsUp) take(candidate) else refused = "$label: ${verdict.message}"
        }
    }

    /** Below the row: what is being tried, why the last choice was refused, or what the chosen one does. */
    @Composable
    fun Below(description: String) {
        val text = when {
            trying != null -> status
            refused != null -> refused!!
            else -> description
        }
        Text(
            text,
            fontSize = 13.sp,
            color = if (trying == null && refused != null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(bottom = 6.dp),
        )
    }
}
