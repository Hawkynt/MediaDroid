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

    /** The wall-clock time (epoch ms) the item ends if it plays on at [speed]; null while its length is unknown. */
    fun endsAtMs(nowMs: Long, positionMs: Long, durationMs: Long, speed: Float): Long? {
        if (durationMs <= 0 || speed <= 0f) return null
        return nowMs + ((durationMs - positionMs).coerceAtLeast(0L) / speed).toLong()
    }

    /** How often a held skip button steps. */
    const val HOLD_REPEAT_MS = 250L

    /** How far a held skip button steps after [heldMs]: 10 s, 30 s after 3 s, 60 s after 6, 5 min after 9. */
    fun holdStepMs(heldMs: Long): Long = when {
        heldMs >= 9_000 -> 300_000L
        heldMs >= 6_000 -> 60_000L
        heldMs >= 3_000 -> 30_000L
        else -> 10_000L
    }

    /** Repeat in the player: off, this video, or the whole queue (the show or the folder). */
    enum class Repeat(val label: String) { OFF("Off"), ONE("This video"), ALL("All of it") }

    /** The queue index to play when item [index] of [size] ends, or null when playback is done. */
    fun afterEnd(index: Int, size: Int, autoAdvance: Boolean, repeat: Repeat): Int? = when {
        repeat == Repeat.ONE -> index
        index + 1 < size && (autoAdvance || repeat == Repeat.ALL) -> index + 1
        repeat == Repeat.ALL && size > 0 -> 0
        else -> null
    }

    /** The sleep timer's choices in minutes; "at the end of this video" is a choice of its own. */
    val SLEEP_MINUTES = listOf(15, 30, 45, 60, 90)

    /** How long before the timer runs out the sound starts fading. */
    const val SLEEP_FADE_MS = 10_000L

    /** The volume factor while a sleep timer runs out: 1 until its last [SLEEP_FADE_MS], then down to 0. */
    fun sleepFade(remainingMs: Long): Float =
        (remainingMs.toFloat() / SLEEP_FADE_MS).coerceIn(0f, 1f)

    /** The whole minutes a sleep timer shows, rounding up so it never claims 0 while still running. */
    fun sleepMinutesLeft(remainingMs: Long): Int =
        if (remainingMs <= 0) 0 else ((remainingMs + 59_999) / 60_000).toInt()

    /**
     * The picture-in-picture window's aspect ratio for a video of [width] × [height] with [pixelRatio]:
     * the picture's own shape, held within the 1:2.39 … 2.39:1 Android accepts. Null while unknown.
     */
    fun pipAspect(width: Int, height: Int, pixelRatio: Float): Pair<Int, Int>? {
        if (width <= 0 || height <= 0) return null
        val shownWidth = (width * pixelRatio).toInt()
        val ratio = shownWidth.toFloat() / height
        return when {
            ratio > 2.39f -> 239 to 100
            ratio < 1f / 2.39f -> 100 to 239
            else -> shownWidth to height
        }
    }

    const val AUDIO_DELAY_STEP_MS = 50
    const val AUDIO_DELAY_LIMIT_MS = 2500

    /** An audio delay (ms; positive: sound later) moved by [steps] of [AUDIO_DELAY_STEP_MS], held in range. */
    fun nudgeAudioDelay(currentMs: Int, steps: Int): Int =
        (currentMs + steps * AUDIO_DELAY_STEP_MS).coerceIn(-AUDIO_DELAY_LIMIT_MS, AUDIO_DELAY_LIMIT_MS)

    /**
     * The display refresh rate to play a [frameRate] film at: the lowest of [available] that is a whole
     * multiple of it (within 0.1 %), or null when none is — then switching would not remove the judder.
     */
    fun matchingRefreshRate(frameRate: Float, available: List<Float>): Float? {
        if (frameRate <= 0f) return null
        return available.sorted().firstOrNull { rate ->
            val multiple = kotlin.math.round(rate / frameRate)
            multiple >= 1 && kotlin.math.abs(rate - multiple * frameRate) <= rate * 0.001f
        }
    }
}
