package com.hawkynt.mediadroid.ui

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.sp
import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.player.PictureControl

/**
 * The four picture sliders, neutral in the middle. [onChange] fires while dragging so the picture
 * follows live; [onDone] when a slider is let go, the moment to save.
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
    }
}

/**
 * Applies [picture] to whatever this layer draws — the video's TextureView. Android 13 and newer
 * run a small shader with a true gamma curve; older devices get the colour-matrix approximation.
 */
fun Modifier.pictureCorrection(picture: PictureAdjustment): Modifier =
    if (picture.isNeutral) {
        this
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        graphicsLayer { renderEffect = PictureShader.effect(picture) }
    } else {
        graphicsLayer { colorFilter = ColorFilter.colorMatrix(ColorMatrix(picture.colorMatrix())) }
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

    fun effect(picture: PictureAdjustment): androidx.compose.ui.graphics.RenderEffect {
        val u = picture.shaderUniforms()
        val shader = RuntimeShader(SOURCE).apply {
            setFloatUniform("brightness", u.brightness)
            setFloatUniform("contrast", u.contrast)
            setFloatUniform("saturation", u.saturation)
            setFloatUniform("inverseGamma", u.inverseGamma)
        }
        return RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }
}
