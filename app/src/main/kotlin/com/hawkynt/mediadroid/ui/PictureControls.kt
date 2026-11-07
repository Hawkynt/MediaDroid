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
import androidx.compose.material3.Switch
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
import com.hawkynt.mediadroid.player.LogoMask
import com.hawkynt.mediadroid.player.MpvShaders
import com.hawkynt.mediadroid.player.NeuralEnlarging
import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.player.PictureCheck
import com.hawkynt.mediadroid.player.PictureControl

/**
 * The four picture sliders, neutral in the middle, black bars cropped or not, then the filters: sharpness, clarity and
 * debanding from off upwards, and noise from removed to added grain. [onChange] fires while dragging so the picture
 * follows live; [onDone] when a slider is let go or a step is picked, the moment to save.
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
    /** Offer hiding a station logo; a choice for one show, remembered for its folder, not a household default. */
    logo: Boolean = false,
    /**
     * Tries a choice of deinterlacing, upscaling or smooth motion before it is taken (see pictureCheck); a choice it
     * refuses is not taken, and why shows below its row. Takes everything by default.
     */
    check: PictureTry = { _, _ -> PictureVerdict(true) },
    /**
     * The colourisers offered for a black-and-white video (see rememberColorizers); with none but Off, no row shows. A
     * choice for one video or folder, not a household default.
     */
    colorizers: List<PictureAdjustment.Colorize> = listOf(PictureAdjustment.Colorize.OFF),
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
        // Found as the video plays and cut away before every filter (see AutoCrop), so Fill fills with picture.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("Crop black bars", fontSize = 16.sp, modifier = Modifier.weight(1f))
            Switch(
                checked = picture.cropBlackBars,
                onCheckedChange = {
                    onChange(picture.copy(cropBlackBars = it))
                    onDone()
                },
            )
        }
        Text(
            "Black bars above, below or beside the picture are found as it plays and cut away.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            // The blocks and ripples a starved MPEG encoder leaves, see Deblock and Cdef.
            Triple("Deblocking", picture.deblock) { v: Float -> picture.copy(deblock = v) },
            Triple("Deringing", picture.dering) { v: Float -> picture.copy(dering = v) },
            // Grain averaged with the frames before and after where nothing moves, see TemporalDenoise.
            Triple("Noise over time", picture.temporalDenoise) { v: Float -> picture.copy(temporalDenoise = v) },
            // A composite capture's crawling dots and rainbows, cancelled where nothing moves, see DotCrawl.
            Triple("Dot crawl", picture.dotCrawl) { v: Float -> picture.copy(dotCrawl = v) },
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
        // Only while dot crawl is combed out: the capture's colour system, where its rate does not tell it (PAL-M;
        // DotCrawl.Standard), kept for this video.
        if (picture.dotCrawl > 0f) {
            Text("Colour system", fontSize = 16.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PictureAdjustment.ColourSystem.entries.forEach { system ->
                    FilterChip(
                        selected = picture.colourSystem == system,
                        onClick = {
                            onChange(picture.copy(colourSystem = system))
                            onDone()
                        },
                        label = { Text(system.label, fontSize = 15.sp) },
                    )
                }
            }
            Text(
                picture.colourSystem.description,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                        val candidate = picture.copy(upscaler = upscaler, upscalerModel = "", upscalerTta = false)
                        upscaling.pick(picture, candidate, upscaler.label, check, take)
                    },
                    label = { Text(upscaler.label, fontSize = 15.sp) },
                )
            }
        }
        // A network's models — noise levels, strengths, scales — and its eight-way run, each tried like the rest.
        val choice = NeuralEnlarging.choice(picture)?.takeIf { picture.upscaler.isEnlarger }
        if (choice != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val models = NeuralEnlarging.models(picture.upscaler)
                if (models.size > 1) {
                    models.forEach { model ->
                        FilterChip(
                            selected = choice.model == model,
                            enabled = upscaling.trying == null,
                            onClick = {
                                val candidate = choice.copy(model = model)
                                upscaling.pick(picture, candidate.applied(picture), candidate.label, check, take)
                            },
                            label = { Text(model.label, fontSize = 15.sp) },
                        )
                    }
                }
                FilterChip(
                    selected = choice.tta,
                    enabled = upscaling.trying == null,
                    onClick = {
                        val candidate = choice.copy(tta = !choice.tta)
                        upscaling.pick(picture, candidate.applied(picture), candidate.label, check, take)
                    },
                    label = { Text(NeuralEnlarging.TTA_LABEL, fontSize = 15.sp) },
                )
            }
        }
        upscaling.Below(
            if (choice?.tta == true) "${picture.upscaler.description} ${NeuralEnlarging.TTA_DESCRIPTION}"
            else picture.upscaler.description,
        )
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
        // How the colour of a 4:2:0 picture is enlarged to its brightness's size (see ChromaUpscaling).
        val chroma = remember { Trial() }
        Text("Colour edges", fontSize = 16.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PictureAdjustment.Chroma.entries.forEach { filter ->
                FilterChip(
                    selected = picture.chroma == filter,
                    enabled = chroma.trying == null,
                    onClick = { chroma.pick(picture, picture.copy(chroma = filter), filter.label, check, take) },
                    label = { Text(filter.label, fontSize = 15.sp) },
                )
            }
        }
        chroma.Below(picture.chroma.description)
        // The mpv user shaders in the folder picked in Settings (MpvShaders), each tried before it is taken.
        val shaders = remember { Trial() }
        Text("mpv shaders", fontSize = 16.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (listOf<String?>(null) + MpvShaders.choices).forEach { name ->
                val label = name?.let(MpvShaders::label) ?: "Off"
                FilterChip(
                    selected = picture.shader == name,
                    enabled = shaders.trying == null,
                    onClick = { shaders.pick(picture, picture.copy(shader = name), label, check, take) },
                    label = { Text(label, fontSize = 15.sp) },
                )
            }
        }
        shaders.Below(
            picture.shader?.let { MpvShaders.library[it]?.description } ?: if (MpvShaders.choices.isEmpty()) {
                "Pick a folder of mpv .glsl shaders in Settings to choose them here."
            } else {
                "Off: no mpv shader runs."
            },
        )
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
        // Colour for black-and-white pictures, tried before it is taken: fetched, tested and timed on this device.
        if (colorizers.size > 1) {
            val colouring = remember { Trial() }
            Text("Colour", fontSize = 16.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                colorizers.forEach { colorize ->
                    FilterChip(
                        selected = picture.colorize == colorize,
                        enabled = colouring.trying == null,
                        onClick = {
                            colouring.pick(picture, picture.copy(colorize = colorize), colorize.label, check, take)
                        },
                        label = { Text(colorize.label, fontSize = 15.sp) },
                    )
                }
            }
            colouring.Below(picture.colorize.description)
        }
        if (logo) LogoRow(picture) { onChange(it); onDone() }
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
            "Filters run on the graphics chip; on HDR videos where the screen shows HDR itself, they are skipped.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A station logo hidden: in which corner (or none), how large, and how — for the whole show, see LogoMask. [take] gets
 * every change, chosen or let go.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LogoRow(picture: PictureAdjustment, take: (PictureAdjustment) -> Unit) {
    val logo = picture.logo
    Text("Station logo", fontSize = 16.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = logo == null,
            onClick = { take(picture.copy(logo = null)) },
            label = { Text("Off", fontSize = 15.sp) },
        )
        LogoMask.Corner.entries.forEach { corner ->
            FilterChip(
                selected = logo?.corner == corner,
                onClick = {
                    val chosen = logo?.copy(corner = corner) ?: LogoMask(corner, 0.15f, LogoMask.Mode.BLUR)
                    take(picture.copy(logo = chosen))
                },
                label = { Text(corner.label, fontSize = 15.sp) },
            )
        }
    }
    if (logo == null) {
        Text(
            "Pick the corner a station's logo sits in: it is hidden in every video of this show.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    var size by remember(logo) { mutableStateOf(logo.size) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("Logo size", fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text("%.0f %%".format(size * 100), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Slider(
        value = size,
        onValueChange = { size = it },
        valueRange = LogoMask.MIN_SIZE..LogoMask.MAX_SIZE,
        onValueChangeFinished = { take(picture.copy(logo = logo.copy(size = size))) },
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LogoMask.Mode.entries.forEach { mode ->
            FilterChip(
                selected = logo.mode == mode,
                onClick = { take(picture.copy(logo = logo.copy(mode = mode))) },
                label = { Text(mode.label, fontSize = 15.sp) },
            )
        }
    }
    Text(logo.mode.description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
