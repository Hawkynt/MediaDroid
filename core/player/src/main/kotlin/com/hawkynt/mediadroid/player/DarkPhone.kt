package com.hawkynt.mediadroid.player

/**
 * The phone's own screen while a film plays on a second screen. Android shows its lock screen over
 * every external display while the phone is locked, and switching the phone's screen off switches the
 * TV off with it — so instead of locking, the phone goes dark: black at the lowest brightness, kept on.
 * Once darkened, the player is guarded: whoever leaves it — Home, Back, the end of playback — leaves a
 * locked phone behind, even after a touch has lit the phone up again.
 */
class DarkPhone {
    /** The phone's screen is dark right now. */
    var dark = false
        private set

    private var armed = false

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
    fun secondScreenGone() {
        dark = false
    }

    /** The player is being left; true when the phone has to be locked now. */
    fun leave(): Boolean = armed.also {
        armed = false
        dark = false
    }
}
