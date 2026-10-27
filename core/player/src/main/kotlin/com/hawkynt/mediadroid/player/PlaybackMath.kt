package com.hawkynt.mediadroid.player

/** The arithmetic behind the player's buttons and gestures, kept pure so it is tested without a device. */
object PlaybackMath {

    /** How far a double tap jumps. */
    const val SEEK_STEP_MS = 15_000L

    /** How far into an item Previous restarts it instead of going back — Media3's own default. */
    const val RESTART_THRESHOLD_MS = 3_000L

    const val MAX_ZOOM = 4f

    /** How much may be left unplayed for an item to count as watched. */
    const val WATCHED_TAIL_MS = 120_000L

    /** Where a double tap lands: [SEEK_STEP_MS] back or ahead, never before the start or past a known end. */
    fun seekBy(positionMs: Long, durationMs: Long, forward: Boolean): Long {
        val target = positionMs + if (forward) SEEK_STEP_MS else -SEEK_STEP_MS
        val upper = if (durationMs > 0) durationMs else Long.MAX_VALUE
        return target.coerceIn(0L, upper)
    }

    sealed interface Previous {
        data object Restart : Previous
        data class GoTo(val index: Int) : Previous
    }

    /** What Previous does at [positionMs] into queue item [index]. */
    fun previous(positionMs: Long, index: Int): Previous =
        if (positionMs > RESTART_THRESHOLD_MS || index == 0) Previous.Restart else Previous.GoTo(index - 1)

    /** How far past the device's own maximum a swipe may go: twice it, as VLC's 200 %. */
    const val MAX_BOOST = 2f

    /**
     * A brightness or volume level after a vertical swipe, from 0 up to [MAX_BOOST]: up to 1 is the
     * device's own range, beyond it the app boosts (see [boostDb], [gammaBoost]).
     */
    fun boostedLevel(start: Float, dragUpFraction: Float): Float = (start + dragUpFraction).coerceIn(0f, MAX_BOOST)

    /** Loudness gain for a volume level past the device maximum: the level as an amplitude factor, in dB. */
    fun boostDb(level: Float): Float = if (level <= 1f) 0f else 20f * kotlin.math.log10(level)

    /** Gamma factor for a brightness level past the device maximum: lifts the shadows, never the clipping point. */
    fun gammaBoost(level: Float): Float = if (level <= 1f) 1f else level

    /** A brightness or volume level after a vertical swipe; a swipe over the whole height spans 0..1. */
    fun level(start: Float, dragUpFraction: Float): Float = (start + dragUpFraction).coerceIn(0f, 1f)

    /** Pinch zoom: from the whole picture (1×) up to [MAX_ZOOM]. */
    fun zoom(current: Float, factor: Float): Float = (current * factor).coerceIn(1f, MAX_ZOOM)

    /** Pan offset along one axis, held so the zoomed picture always covers the view. */
    fun pan(offset: Float, zoom: Float, viewSize: Float): Float {
        val limit = (zoom - 1f) * viewSize / 2f
        return offset.coerceIn(-limit, limit)
    }

    /**
     * Whether an item stopped (or skipped forward) at [positionMs] counts as watched: the last
     * [WATCHED_TAIL_MS] or the credits from [creditsStartMs] on are all that is left. The tail is at
     * most a tenth of the runtime, so a short clip is not finished by starting it.
     */
    fun isWatched(positionMs: Long, durationMs: Long, creditsStartMs: Long?): Boolean {
        if (durationMs <= 0 || positionMs <= 0) return false
        if (creditsStartMs != null && creditsStartMs > 0 && positionMs >= creditsStartMs) return true
        return durationMs - positionMs <= minOf(WATCHED_TAIL_MS, durationMs / 10)
    }
}
