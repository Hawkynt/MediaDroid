package com.hawkynt.mediadroid.desktop

import java.util.Locale

/** Where the window was left: [x], [y], [width], [height] in dp, and whether it was [maximized]. */
data class WindowPlace(val x: Float, val y: Float, val width: Float, val height: Float, val maximized: Boolean) {

    /** As kept in the store: `x,y,width,height`, then `,max` when maximized. */
    override fun toString(): String =
        "%.0f,%.0f,%.0f,%.0f".format(Locale.ROOT, x, y, width, height) + if (maximized) ",max" else ""

    companion object {
        /** Windows smaller than this are not brought back: a broken entry must not leave a sliver of a window. */
        const val MIN_WIDTH = 320f
        const val MIN_HEIGHT = 240f

        /** The place kept as [text], or null when there is none or it does not read. */
        fun parse(text: String?): WindowPlace? {
            val parts = text?.split(',') ?: return null
            val n = parts.take(4).map { it.trim().toFloatOrNull() ?: return null }
            if (n.size < 4 || n[2] < MIN_WIDTH || n[3] < MIN_HEIGHT) return null
            return WindowPlace(n[0], n[1], n[2], n[3], parts.drop(4).any { it.trim() == "max" })
        }
    }
}
