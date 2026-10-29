package com.hawkynt.mediadroid.ui

import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
 * upwards, and denoise in steps. [onChange] fires while dragging so the picture follows live; [onDone]
 * when a slider is let go or a step is picked, the moment to save.
 */
@Composable
fun PictureSliders(
    picture: PictureAdjustment,
    onChange: (PictureAdjustment) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
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
        Text("Denoise", fontSize = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Off", "Low", "Medium", "High").forEachIndexed { level, label ->
                FilterChip(
                    selected = picture.denoise == level,
                    onClick = {
                        onChange(picture.copy(denoise = level))
                        onDone()
                    },
                    label = { Text(label, fontSize = 15.sp) },
                )
            }
        }
        Text("Deinterlace", fontSize = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
        }
        Text("Upscaling", fontSize = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PictureAdjustment.Upscaler.entries.forEach { upscaler ->
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
        Text("Smooth motion (frame blending)", fontSize = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PictureAdjustment.Interpolation.entries.forEach { strength ->
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
        Text(
            "Filters run on the graphics chip; HDR videos skip them.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Applies [picture] to this view — the video frame inside the player, so the controls drawn above
 * it keep their colours. Android 13 and newer run a small shader with a true gamma curve; older
 * devices get the colour-matrix approximation through a hardware layer.
 */
fun View.applyPicture(picture: PictureAdjustment) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        setRenderEffect(if (picture.isColorNeutral) null else PictureShader.effect(picture))
    } else if (picture.isColorNeutral) {
        setLayerType(View.LAYER_TYPE_NONE, null)
    } else {
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(picture.colorMatrix()) }
        setLayerType(View.LAYER_TYPE_HARDWARE, paint)
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object PictureShader {

    // Order and luma weights as in PictureAdjustment: saturation around grey, contrast around mid
    // grey plus brightness, then the gamma curve. Colours arrive premultiplied.
    private const val SOURCE = """
        uniform shader content;
        uniform float brightness;
        uniform float contrast;
        uniform float saturation;
        uniform float inverseGamma;

        half4 main(float2 position) {
            half4 color = content.eval(position);
            float alpha = color.a;
            if (alpha <= 0.0) return color;
            float3 rgb = color.rgb / alpha;
            float luma = dot(rgb, float3(0.213, 0.715, 0.072));
            rgb = mix(float3(luma), rgb, saturation);
            rgb = (rgb - 0.5) * contrast + 0.5 + brightness;
            rgb = pow(clamp(rgb, 0.0, 1.0), float3(inverseGamma));
            return half4(half3(rgb * alpha), half(alpha));
        }
    """

    fun effect(picture: PictureAdjustment): RenderEffect {
        val u = picture.shaderUniforms()
        val shader = RuntimeShader(SOURCE).apply {
            setFloatUniform("brightness", u.brightness)
            setFloatUniform("contrast", u.contrast)
            setFloatUniform("saturation", u.saturation)
            setFloatUniform("inverseGamma", u.inverseGamma)
        }
        return RenderEffect.createRuntimeShaderEffect(shader, "content")
    }
}
