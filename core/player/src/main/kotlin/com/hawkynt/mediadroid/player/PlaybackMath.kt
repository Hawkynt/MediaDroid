package com.hawkynt.mediadroid.player

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

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

    /** How far past the device's own maximum a swipe may go: eight times it (800 %). */
    const val MAX_BOOST = 8f

    /**
     * A brightness or volume swipe position from 0 to 2: up to 1 is the device's own range, linear; the
     * second stretch boosts past it on a logarithmic scale up to [MAX_BOOST] (see [boostFactor]).
     */
    fun boostedLevel(start: Float, dragUpFraction: Float): Float = (start + dragUpFraction).coerceIn(0f, 2f)

    /** How many times the device maximum [level] stands for: 1 up to 1, then doubling every third of the stretch. */
    fun boostFactor(level: Float): Float = if (level <= 1f) 1f else MAX_BOOST.pow(level - 1f)

    /** The level as the percentage shown while swiping: 0..100 on the device, up to 800 beyond it. */
    fun percent(level: Float): Int = (if (level <= 1f) level * 100f else boostFactor(level) * 100f).roundToInt()

    /** Loudness gain for a volume level past the device maximum: the boost factor in dB (up to +18 dB). */
    fun boostDb(level: Float): Float = 20f * log10(boostFactor(level))

    /** Gamma factor for a brightness level past the device maximum: the square root of the boost, at most ≈2.8. */
    fun gammaBoost(level: Float): Float = sqrt(boostFactor(level))

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

    /**
     * How far in from the left/right and top/bottom edges overlays must stay while the screen is being
     * mirrored to a 16:9 TV, which shows only the centred 16:9 part of a longer phone screen.
     */
    fun mirrorSafeInset(width: Float, height: Float): Pair<Float, Float> {
        if (width <= 0f || height <= 0f) return 0f to 0f
        val tv = 16f / 9f
        return when {
            width / height > tv -> (width - height * tv) / 2f to 0f
            height / width > tv -> 0f to (height - width * tv) / 2f
            else -> 0f to 0f
        }
    }
}
