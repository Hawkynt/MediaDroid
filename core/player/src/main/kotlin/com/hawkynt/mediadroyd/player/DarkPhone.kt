package com.hawkynt.mediadroyd.player

/**
 * The phone's own screen while a film plays on a second screen. Android shows its lock screen over
 * every external display while the phone is locked, and switching the phone's screen off switches the
 * TV off with it — so instead of locking, the phone goes dark: black and empty at the lowest brightness, kept on,
 * nothing left on it to burn in — lifted just enough to read while a gesture's note shows on it, the other screen
 * showing only the film. It does so by itself whenever the controls hide while the film is on the other
 * screen, and a touch lights it up with the controls. Darkened by its button ([darken]), the player is also
 * guarded: whoever leaves it — Home, Back, the end of playback — leaves a locked phone behind, even after a touch
 * has lit the phone up again.
 */
class DarkPhone {
    /** The phone's screen is dark right now. */
    var dark = false
        private set

    private var armed = false

    private var elsewhere = false

    /** Whether the film is on the second screen ([on]) or back on the phone, which then lights up. */
    fun secondScreen(on: Boolean) {
        elsewhere = on
        if (!on) dark = false
    }

    /** The controls hid: with the film on the second screen the phone goes dark, the lock left as it is. */
    fun controlsHidden() {
        if (elsewhere) dark = true
    }

    /** Darkens the phone and arms the lock for when the player is left. */
    fun darken() {
        dark = true
        armed = true
    }

    /** A touch on the dark phone lights it up again; the lock stays armed. */
    fun touch() {
        dark = false
    }

    /** The second screen went away: the film is back on the phone, which has to light up. */
    fun secondScreenGone() = secondScreen(false)

    /** The player is being left; true when the phone has to be locked now. */
    fun leave(): Boolean = armed.also {
        armed = false
        dark = false
    }

    /**
     * The phone window's brightness: 0 while dark, [NOTE_BRIGHTNESS] while dark with a gesture's note on it
     * ([noteShown]: "Volume 40 %"), null while lit — the system's or the viewer's own then.
     */
    fun brightness(noteShown: Boolean): Float? = when {
        !dark -> null
        noteShown -> NOTE_BRIGHTNESS
        else -> 0f
    }

    companion object {
        /** The dark phone's brightness while a note shows: readable in a dark room, not lighting it. */
        const val NOTE_BRIGHTNESS = 0.15f
    }
}
