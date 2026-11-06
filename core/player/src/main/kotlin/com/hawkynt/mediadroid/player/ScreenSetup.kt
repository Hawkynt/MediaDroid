package com.hawkynt.mediadroid.player

/**
 * How one output screen is set up, kept for that screen alone — the phone's own, a TV, a projector: the shape it is
 * seen at ([ScreenShape], an anamorphic lens).
 */
data class ScreenSetup(
    val shape: ScreenShape = ScreenShape.AUTO,
) {
    companion object {
        val Default = ScreenSetup()

        /** The setup kept as [shape] (a [ScreenShape] name); a name this version does not know is the default. */
        fun of(shape: String): ScreenSetup = ScreenSetup(
            shape = ScreenShape.entries.firstOrNull { it.name == shape } ?: ScreenShape.AUTO,
        )
    }
}
