package com.hawkynt.mediadroyd.player

import kotlin.math.cbrt
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * How a picture fills the screen: [FIT] whole, bars where the shapes differ; [FILL] the screen covered, the overhang
 * cut; [STRETCH] the screen covered, the picture pulled to its shape; [PANORAMIC] a narrow picture widened to the
 * screen with its middle kept in shape and only the sides stretched (see [Panorama]).
 */
enum class PictureFit(val label: String) {
    FIT("Fit"),
    FILL("Fill"),
    STRETCH("Stretch"),
    PANORAMIC("Panoramic"),
}

/** Where a picture of a given shape goes on a screen, by [PictureFit]. */
object PictureLayout {

    /** How much wider [PictureFit.PANORAMIC] may make a picture: a 4:3 one onto a 16:9 screen, and no further. */
    const val MAX_PANORAMA = 4f / 3f

    /**
     * How much wider than its shape [PictureFit.PANORAMIC] shows a picture of [aspect] on a screen of [screenAspect]:
     * as much as fills the screen's width, at most [MAX_PANORAMA]; 1 for a picture as wide as the screen or wider.
     */
    fun panorama(aspect: Float, screenAspect: Float): Float {
        if (aspect <= 0f || screenAspect <= 0f) return 1f
        return (screenAspect / aspect).coerceIn(1f, MAX_PANORAMA)
    }

    /**
     * The size, in pixels, a picture of [aspect] is drawn at on a [width] × [height] screen by [fit] — centred; for
     * [PictureFit.FILL] larger than the screen, the overhang cut.
     */
    fun size(fit: PictureFit, aspect: Float, width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0 || aspect <= 0f) return width.coerceAtLeast(1) to height.coerceAtLeast(1)
        val screen = width.toFloat() / height
        val shown = when (fit) {
            PictureFit.STRETCH -> return width to height
            PictureFit.PANORAMIC -> aspect * panorama(aspect, screen)
            else -> aspect
        }
        val wider = shown > screen
        val byWidth = if (fit == PictureFit.FILL) !wider else wider
        return if (byWidth) {
            width to (width / shown).roundToInt().coerceAtLeast(1)
        } else {
            (height * shown).roundToInt().coerceAtLeast(1) to height
        }
    }
}

/**
 * Panoramic stretch: a picture shown [ratio] times wider than its shape, its middle kept in shape and the stretch
 * growing towards the sides. Across the picture, centred at 0 and reaching ±1 at its edges, the widening at s is
 * 1 + c·s² — none in the middle, 1 + c at the edges — so a point s lands at s + c·s³/3, and c = 3·(ratio − 1) makes
 * the edges land at ±ratio. A 4:3 picture on a 16:9 screen (ratio 4/3) keeps its middle as it is and its edges
 * stretched twice as wide. The screen's drawing needs the way back, [source]: the cubic solved by Cardano's formula.
 */
object Panorama {

    /**
     * Where, across the picture (0 at its left edge, 1 at its right), the point [x] across the shown picture comes
     * from, shown [ratio] times wider than its shape.
     */
    fun source(x: Double, ratio: Double): Double {
        val c = 3.0 * (ratio - 1.0)
        val target = ratio * (2.0 * x - 1.0)
        if (c < 1e-9) return x
        // s³ + p·s − q = 0 with p = 3/c and q = 3·target/c; one real root, the discriminant being positive.
        val p = 3.0 / c
        val q = 3.0 * target / c
        val root = sqrt(q * q / 4.0 + p * p * p / 27.0)
        val s = cbrt(q / 2.0 + root) + cbrt(q / 2.0 - root)
        return (s + 1.0) / 2.0
    }

    /** How much wider than its shape the picture is shown at [x] across the picture (0..1), shown [ratio] wider. */
    fun stretchAt(x: Double, ratio: Double): Double {
        val s = 2.0 * x - 1.0
        return 1.0 + 3.0 * (ratio - 1.0) * s * s
    }
}
