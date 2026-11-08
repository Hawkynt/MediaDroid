package com.hawkynt.mediadroyd.player

/**
 * How one output screen is set up, kept for that screen alone — the phone's own, a TV, a projector: the shape it is
 * seen at ([ScreenShape], an anamorphic lens), and for a projector the picture [mirror]ed (rear projection), turned
 * upside down ([flip], ceiling mount) and its keystone corrected ([keystoneVertical], [keystoneHorizontal], see
 * [Keystone]); for an OLED screen, burn-in care ([burnInCare], see [BurnInDrift]) — off for every other screen, and
 * no screen says which it is, so it is the household's to switch on.
 */
data class ScreenSetup(
    val shape: ScreenShape = ScreenShape.AUTO,
    val mirror: Boolean = false,
    val flip: Boolean = false,
    val keystoneVertical: Float = 0f,
    val keystoneHorizontal: Float = 0f,
    val burnInCare: Boolean = false,
) {
    /** The picture is drawn as a trapezoid: the whole screen is the stage's to draw on, bars and all. */
    val keystoned: Boolean get() = keystoneVertical != 0f || keystoneHorizontal != 0f

    companion object {
        val Default = ScreenSetup()

        /**
         * The setup kept as [shape] (a [ScreenShape] name; one this version does not know is [ScreenShape.AUTO]) and
         * the rest, the keystones held within [Keystone.LIMIT].
         */
        fun of(
            shape: String,
            mirror: Boolean = false,
            flip: Boolean = false,
            keystoneVertical: Float = 0f,
            keystoneHorizontal: Float = 0f,
            burnInCare: Boolean = false,
        ): ScreenSetup = ScreenSetup(
            shape = ScreenShape.entries.firstOrNull { it.name == shape } ?: ScreenShape.AUTO,
            mirror = mirror,
            flip = flip,
            keystoneVertical = keystoneVertical.coerceIn(-Keystone.LIMIT, Keystone.LIMIT),
            keystoneHorizontal = keystoneHorizontal.coerceIn(-Keystone.LIMIT, Keystone.LIMIT),
            burnInCare = burnInCare,
        )
    }
}
