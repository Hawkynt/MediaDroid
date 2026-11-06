package com.hawkynt.mediadroid.player

/**
 * The shape a whole video frame is shown at where the file's own flag is wrong: a 4:3 DVD flagged wide, an anamorphic
 * rip whose flag got lost. [AUTO] keeps what the file says (its pixel aspect); the others set the frame's shape
 * outright, whatever its pixel size — black bars cropped afterwards keep that shape's pixels.
 */
enum class AspectOverride(val label: String, val ratio: Float?) {
    AUTO("Auto", null),
    FOUR_THREE("4:3", 4f / 3f),
    SIXTEEN_NINE("16:9", 16f / 9f),
    FLAT("1.85:1", 1.85f),
    SCOPE("2.35:1", 2.35f),
}

/**
 * The shape a screen is seen at, where it is not the shape of its pixels: a projector behind an anamorphic lens, which
 * widens what it throws, or a panel whose pixels are not square. [AUTO] is the panel as its pixels count. A 16:9
 * projector behind a 1.33× lens throws a 2.37:1 picture; behind a 1.25× lens a 2.22:1 one; a 4:3 projector behind a
 * 1.33× lens a 16:9 one.
 */
enum class ScreenShape(val label: String, val ratio: Float?) {
    AUTO("As its pixels", null),
    FOUR_THREE("4:3", 4f / 3f),
    SIXTEEN_TEN("16:10", 16f / 10f),
    SIXTEEN_NINE("16:9", 16f / 9f),
    LENS_125("2.22:1 (1.25× lens)", 16f / 9f * 1.25f),
    LENS_133("2.37:1 (1.33× lens)", 16f / 9f * 4f / 3f),
}

/**
 * The geometry of a picture's shape: what it is meant to look like, and what that takes on a screen that widens it.
 */
object PictureShape {

    /**
     * The width of one of a [width] × [height] frame's pixels over its height: the file's own [pixelAspect], unless
     * [override] sets the whole frame's shape.
     */
    fun pixelAspect(width: Int, height: Int, pixelAspect: Float, override: AspectOverride): Float {
        val ratio = override.ratio
        if (ratio == null || width <= 0 || height <= 0) return pixelAspect
        return ratio * height / width
    }

    /**
     * The shape a [width] × [height] frame is meant to be seen at — its width over its height — of which [shareX] of
     * the width and [shareY] of the height show (black bars cropped).
     */
    fun aspect(
        width: Int,
        height: Int,
        pixelAspect: Float,
        override: AspectOverride,
        shareX: Float = 1f,
        shareY: Float = 1f,
    ): Float {
        if (width <= 0 || height <= 0) return 1f
        return width * pixelAspect(width, height, pixelAspect, override) * shareX / (height * shareY)
    }

    /**
     * How much wider than its pixels a [panelAspect] panel is seen as [screen]: 1 for a screen as its pixels, 4/3
     * for a 16:9 projector behind a 1.33× lens.
     */
    fun widening(panelAspect: Float, screen: ScreenShape): Float {
        val ratio = screen.ratio
        if (ratio == null || panelAspect <= 0f) return 1f
        return ratio / panelAspect
    }

    /**
     * The shape to draw a picture meant to be seen at [aspect] in, on a [panelAspect] panel seen as [screen]: narrower
     * by what the screen widens, so it is seen as meant.
     */
    fun drawn(aspect: Float, panelAspect: Float, screen: ScreenShape): Float = aspect / widening(panelAspect, screen)
}
