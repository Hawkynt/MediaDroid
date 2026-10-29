package com.hawkynt.mediadroid.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

/** Night-blue cinema dark with a warm amber accent — the launcher icon's colours. */
object MediaDroidColors {
    val Night = Color(0xFF0E1020)
    val Surface = Color(0xFF15182C)
    val SurfaceHigh = Color(0xFF1D2140)
    val SurfaceHighest = Color(0xFF262B52)
    val Amber = Color(0xFFFFB547)
    val OnAmber = Color(0xFF2B1B00)
    val Coral = Color(0xFFFF7A6B)
    val Text = Color(0xFFECEDF5)
    val TextDim = Color(0xFFA7ABC6)
    val Outline = Color(0xFF3A3E66)
}

private val Scheme = darkColorScheme(
    primary = MediaDroidColors.Amber,
    onPrimary = MediaDroidColors.OnAmber,
    primaryContainer = Color(0xFF5A3E00),
    onPrimaryContainer = Color(0xFFFFDDB0),
    secondary = MediaDroidColors.Coral,
    onSecondary = Color(0xFF3B0A04),
    secondaryContainer = MediaDroidColors.SurfaceHighest,
    onSecondaryContainer = MediaDroidColors.Text,
    background = MediaDroidColors.Night,
    onBackground = MediaDroidColors.Text,
    surface = MediaDroidColors.Surface,
    onSurface = MediaDroidColors.Text,
    surfaceVariant = MediaDroidColors.SurfaceHigh,
    onSurfaceVariant = MediaDroidColors.TextDim,
    surfaceContainerLowest = MediaDroidColors.Night,
    surfaceContainerLow = MediaDroidColors.Surface,
    surfaceContainer = MediaDroidColors.Surface,
    surfaceContainerHigh = MediaDroidColors.SurfaceHigh,
    surfaceContainerHighest = MediaDroidColors.SurfaceHighest,
    outline = MediaDroidColors.Outline,
    outlineVariant = MediaDroidColors.Outline,
)

/**
 * Line heights relative to the font size: text set with a larger `fontSize` keeps a matching line
 * height, so a title that wraps stacks its lines instead of drawing them over each other.
 */
private fun TextStyle.relativeLines() = copy(lineHeight = 1.25.em)

private val Type = Typography().run {
    copy(
        displayLarge = displayLarge.relativeLines(),
        displayMedium = displayMedium.relativeLines(),
        displaySmall = displaySmall.relativeLines(),
        headlineLarge = headlineLarge.relativeLines().copy(fontWeight = FontWeight.Bold),
        headlineMedium = headlineMedium.relativeLines().copy(fontWeight = FontWeight.Bold),
        headlineSmall = headlineSmall.relativeLines(),
        titleLarge = titleLarge.relativeLines().copy(fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.relativeLines(),
        titleSmall = titleSmall.relativeLines(),
        bodyLarge = bodyLarge.relativeLines(),
        bodyMedium = bodyMedium.relativeLines(),
        bodySmall = bodySmall.relativeLines(),
        labelLarge = labelLarge.relativeLines(),
        labelMedium = labelMedium.relativeLines(),
        labelSmall = labelSmall.relativeLines(),
    )
}

/**
 * The app's one theme; there is no light variant — a media wall reads best in the dark. The root
 * [Surface] sets the content colour every un-tinted text and icon falls back to; without it they
 * would be drawn black on the night background.
 */
@Composable
fun MediaDroidTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Type) {
        Surface(color = Scheme.background, contentColor = Scheme.onBackground, content = content)
    }
}

/**
 * Clickable that also reads well from a sofa with a TV remote: the focused element grows a little
 * and gets an amber ring, the way Kodi and the streaming apps mark the D-pad cursor. A long press — or
 * holding a remote's OK — calls [onLongClick] where there is one.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.focusClickable(
    shape: Shape = RoundedCornerShape(10.dp),
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, label = "focus-scale")
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .border(3.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
        .combinedClickable(
            interactionSource = source,
            indication = ripple(),
            onLongClick = onLongClick,
            onClick = onClick,
        )
}
