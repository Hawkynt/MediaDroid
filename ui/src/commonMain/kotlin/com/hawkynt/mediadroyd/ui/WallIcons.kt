package com.hawkynt.mediadroyd.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.hawkynt.mediadroyd.library.WallMode

/**
 * The wall's three layouts as icons for its switch (WallModeSwitch), drawn here on Material's 24-unit grid: Material's
 * core icons, which the UI carries, hold no grid, and the extended set is a large library for three pictures. A grid of
 * four posters, a list of rows with a small picture each, and rows with a picture and two lines of text.
 */
internal object WallIcons {
    val Posters: ImageVector = icon("Posters") {
        rect(4f, 4f, 7f, 7f)
        rect(13f, 4f, 7f, 7f)
        rect(4f, 13f, 7f, 7f)
        rect(13f, 13f, 7f, 7f)
    }

    val List: ImageVector = icon("List") {
        for (y in listOf(4f, 10f, 16f)) {
            rect(3f, y, 4f, 4f)
            rect(9f, y + 1f, 12f, 2f)
        }
    }

    val Details: ImageVector = icon("Details") {
        for (y in listOf(3f, 13f)) {
            rect(3f, y, 6f, 8f)
            rect(11f, y + 1f, 10f, 2f)
            rect(11f, y + 5f, 7f, 2f)
        }
    }

    /** The icon for [mode]. */
    fun of(mode: WallMode): ImageVector = when (mode) {
        WallMode.POSTERS -> Posters
        WallMode.LIST -> List
        WallMode.DETAILS -> Details
    }

    private fun icon(name: String, shapes: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .path(fill = SolidColor(Color.Black), pathBuilder = shapes)
            .build()

    // A filled rectangle at ([x], [y]) of [width] × [height] units.
    private fun PathBuilder.rect(x: Float, y: Float, width: Float, height: Float) {
        moveTo(x, y)
        horizontalLineToRelative(width)
        verticalLineToRelative(height)
        horizontalLineToRelative(-width)
        close()
    }
}
