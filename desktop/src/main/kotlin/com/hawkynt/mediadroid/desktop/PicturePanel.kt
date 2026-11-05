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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawkynt.mediadroid.engine.Track
import com.hawkynt.mediadroid.library.Languages
import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.player.PictureCheck
import com.hawkynt.mediadroid.player.PictureControl
import com.hawkynt.mediadroid.player.PictureVerdict
import com.hawkynt.mediadroid.player.PlaybackMath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The choices the desktop stage runs (GlStage, Film in the engine); the neural ones follow. */
val DesktopDeinterlacers = PictureAdjustment.Deinterlace.entries.toList()
val DesktopInterpolations = PictureAdjustment.Interpolation.entries.filter { !it.isNeural }
val DesktopUpscalers = listOf(
    PictureAdjustment.Upscaler.STANDARD,
    PictureAdjustment.Upscaler.LANCZOS,
    PictureAdjustment.Upscaler.FSR,
    PictureAdjustment.Upscaler.EWA,
    PictureAdjustment.Upscaler.NIS,
    PictureAdjustment.Upscaler.GSR,
    PictureAdjustment.Upscaler.ANIME4K,
    PictureAdjustment.Upscaler.ACNET,
    PictureAdjustment.Upscaler.XBRZ,
)

/** How a track picker names [track]: its own label, else its language, else its place among its kind ([number]). */
fun trackName(track: Track, number: Int): String =
    track.label?.takeIf { it.isNotBlank() } ?: track.language?.let(Languages::label) ?: "Track $number"

/**
 * The player's panel: the sound and subtitle [tracks] to pick from ([onPick] with null: subtitles off), then the
 * Android gear's picture controls, from the same domain (PictureControl, enums). A choice of deinterlacing, upscaling
 * or smooth motion is tried with [check] before it is taken, as on Android; one it refuses is not taken, and why shows
 * below its row.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PicturePanel(
    picture: PictureAdjustment,
    onChange: (PictureAdjustment) -> Unit,
    modifier: Modifier = Modifier,
    tracks: List<Track> = emptyList(),
    onPick: (Track.Kind, String?) -> Unit = { _, _ -> },
    speed: Float = 1f,
    onSpeed: (Float) -> Unit = {},
    check: PictureTry = { _, _ -> PictureVerdict(true) },
) {
    Column(
        modifier.width(380.dp).fillMaxHeight().background(Color(0xF01A1A2E)).verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Speed", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier.weight(1f))
            Text("%.2f×".format(speed), color = Color.Gray)
            if (speed != 1f) TextButton(onClick = { onSpeed(1f) }) { Text("1×") }
        }
        Slider(
            value = PlaybackMath.speedPosition(speed),
            onValueChange = { onSpeed(PlaybackMath.speedAt(it)) },
        )
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
        // Found as the video plays and cut away before every filter (AutoCrop), as on the phone.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Crop black bars", color = Color.White, modifier = Modifier.weight(1f))
            Switch(checked = picture.cropBlackBars, onCheckedChange = { onChange(picture.copy(cropBlackBars = it)) })
        }
        listOf(
            Triple("Sharpness", picture.sharpness) { v: Float -> picture.copy(sharpness = v) },
            Triple("Clarity", picture.clarity) { v: Float -> picture.copy(clarity = v) },
            Triple("Debanding", picture.deband) { v: Float -> picture.copy(deband = v) },
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
        val scope = rememberCoroutineScope()
        val deinterlace = remember { Trial() }
        Text("Deinterlace", color = Color.White, modifier = Modifier.padding(top = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DesktopDeinterlacers.forEach { mode ->
                FilterChip(
                    selected = picture.deinterlace == mode,
                    enabled = deinterlace.trying == null,
                    onClick = {
                        deinterlace.pick(scope, picture, picture.copy(deinterlace = mode), mode.label, check, onChange)
                    },
                    label = { Text(mode.label) },
                )
            }
            // YADIF and BWDIF can show both fields of a frame, each as a picture of its own.
            if (picture.deinterlace.isTemporal) {
                FilterChip(
                    selected = picture.fieldRate,
                    enabled = deinterlace.trying == null,
                    onClick = {
                        val fields = picture.copy(fieldRate = !picture.fieldRate)
                        deinterlace.pick(scope, picture, fields, "Every field", check, onChange)
                    },
                    label = { Text("Every field") },
                )
            }
        }
        deinterlace.Below(
            if (picture.isFieldRate) "Both fields of every frame shown, each as a picture: motion as the camera saw it."
            else picture.deinterlace.description,
        )
        val upscaling = remember { Trial() }
        Text("Upscaling", color = Color.White, modifier = Modifier.padding(top = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DesktopUpscalers.forEach { upscaler ->
                FilterChip(
                    selected = picture.upscaler == upscaler,
                    enabled = upscaling.trying == null,
                    onClick = {
                        val candidate = picture.copy(upscaler = upscaler)
                        upscaling.pick(scope, picture, candidate, upscaler.label, check, onChange)
                    },
                    label = { Text(upscaler.label) },
                )
            }
        }
        upscaling.Below(picture.upscaler.description)
        // How much of the upscaler shows: 0 % plain scaling, 100 % the upscaler alone.
        if (picture.upscaler != PictureAdjustment.Upscaler.STANDARD) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Upscaler strength", color = Color.White, modifier = Modifier.weight(1f))
                Text(
                    if (picture.upscalerStrength <= 0f) "off" else "%.0f %%".format(picture.upscalerStrength * 100),
                    color = Color.Gray,
                )
            }
            Slider(
                value = picture.upscalerStrength,
                onValueChange = { onChange(picture.copy(upscalerStrength = (it * 20).toInt() / 20f)) },
                valueRange = 0f..1f,
            )
        }
        val motion = remember { Trial() }
        Text("Smooth motion", color = Color.White, modifier = Modifier.padding(top = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DesktopInterpolations.forEach { strength ->
                FilterChip(
                    selected = picture.interpolation == strength,
                    enabled = motion.trying == null,
                    onClick = {
                        val candidate = picture.copy(interpolation = strength)
                        motion.pick(scope, picture, candidate, strength.label, check, onChange)
                    },
                    label = { Text(strength.label) },
                )
            }
        }
        motion.Below(picture.interpolation.description)
        Text("3D as 2D: one eye", color = Color.White, modifier = Modifier.padding(top = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PictureAdjustment.Stereo.entries.forEach { eye ->
                FilterChip(
                    selected = picture.stereo == eye,
                    onClick = { onChange(picture.copy(stereo = eye)) },
                    label = { Text(eye.label) },
                )
            }
        }
    }
}

/**
 * One row's choice being tried: which ([trying], what the check says meanwhile), or why the last one was refused —
 * the Android player's rows, the same way.
 */
private class Trial {
    var trying by mutableStateOf<String?>(null)
    var status by mutableStateOf("")
    var refused by mutableStateOf<String?>(null)

    /**
     * Picks [candidate] (labelled [label]) over [current]: taken at once where it only takes work away
     * (PictureCheck.needed), else tried with [check] first and taken ([take]) only where this computer keeps up.
     */
    fun pick(
        scope: CoroutineScope,
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
        scope.launch {
            val verdict = check(candidate) { said -> scope.launch { status = "$label: $said" } }
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
            color = if (trying == null && refused != null) MaterialTheme.colorScheme.error else Color.Gray,
            fontSize = 13.sp,
        )
    }
}
