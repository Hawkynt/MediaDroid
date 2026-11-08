package com.hawkynt.mediadroyd.player

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Neural frame interpolation (RIFE and the networks after it): a network makes the picture halfway between two
 * frames at any size. On screen the picture then runs from the earlier frame through that in-between to the later
 * one, each half blended (see [mix]), so it doubles the frame rate exactly and suits any refresh between.
 *
 * The time an in-between takes grows with its pixels, so the self-test times [TEST_SIZES] and the player makes the
 * in-betweens of the material at its own size where the device keeps up, and otherwise at the most pixels it keeps
 * up with, the material's shape kept (see [workingSize]). RIFE and IFRNet hand back their flow instead of a picture ([handsFlow]):
 * the flow is worked out at that size and the frames are warped by it at their own, so a smaller size costs only
 * motion detail, never sharpness — its self-test starts lower ([testSizes]).
 */
object NeuralInterpolation {
    /**
     * What one in-between may take: three quarters of a frame at 25 frames a second, so 25 can become 50 — the network
     * runs on a thread of its own with a frame's time for each (the picture a frame later), the rest left for the
     * decoder and the read-back.
     */
    const val BUDGET_MS = 30.0

    /** A picture's size in pixels. */
    data class Size(val width: Int, val height: Int) {
        /** Its pixels. */
        val area: Long get() = width.toLong() * height
    }

    /** What the self-test times, smallest first: 16:9 pictures at 480p, 576p, 720p and 1080p. */
    val TEST_SIZES = listOf(Size(854, 480), Size(1024, 576), Size(1280, 720), Size(1920, 1080))

    /**
     * The smaller sizes a network handing back its flow is also timed at: from 120p (NeuralPace.MIN_LINES, where a flow
     * still beats a blend) through 180p, 240p and 360p — so a device that keeps up only with a small flow is offered
     * the network, at that size.
     */
    private val FLOW_SIZES = listOf(Size(214, 120), Size(320, 180), Size(426, 240), Size(640, 360))

    /**
     * Whether [network] hands back its flow and blend mask rather than a picture: RIFE and IFRNet, whose last step —
     * the two frames warped by the flows and blended by the mask (IFRNet adding its residual) — the stage takes over
     * at the frames' own size.
     */
    fun handsFlow(network: PictureAdjustment.Interpolation): Boolean =
        network == PictureAdjustment.Interpolation.RIFE || network == PictureAdjustment.Interpolation.IFRNET

    /** What [network]'s self-test times, smallest first: from 120p for one that [handsFlow], else [TEST_SIZES]. */
    fun testSizes(network: PictureAdjustment.Interpolation): List<Size> =
        if (handsFlow(network)) FLOW_SIZES + TEST_SIZES else TEST_SIZES

    /**
     * How much a flow worked out at [working] grows to serve [own]: across and down, each by its own ratio (the
     * working size keeps the shape only to the nearest even pixel).
     */
    fun flowScale(own: Size, working: Size): Pair<Float, Float> =
        own.width.toFloat() / working.width to own.height.toFloat() / working.height

    /**
     * The longest a self-test takes, from the tap to the answer, its network's loading included: a hundred times the
     * budget, so a slower device is told so at once. A test cut short counts as taking this long.
     */
    const val MAX_WAIT_MS = 2000.0

    /**
     * What an in-between at the smallest test size ([smallest]) would take, from [ms] for one of [pixels]: by the
     * pixels, a lower bound where the network's own overhead counts for more on the small one.
     */
    fun estimate(ms: Double, pixels: Int, smallest: Size = TEST_SIZES.first()): Double = ms * smallest.area / pixels

    /**
     * Whether [ms] for the self-test's small check picture is already past the budget: the work only grows with the
     * pixels, so no test size can keep up — told without giving the device anything larger.
     */
    fun tooSlowAlready(ms: Double): Boolean = !fastEnough(ms)

    /** Whether a time of [ms] stands for a run the self-test cut short. */
    fun cutShort(ms: Double): Boolean = ms >= MAX_WAIT_MS

    /** How far an in-between may be from the true middle, as a mean difference per value (0..255). */
    const val MAX_ERROR = 6.0

    /**
     * Whether a unit computes the network right: its in-between of two test frames is within [MAX_ERROR] of the
     * frame truly halfway ([networkError]) and nearer to it than the two frames blended ([blendError]) — a unit whose
     * driver computes it wrong is not used.
     */
    fun rightEnough(networkError: Double, blendError: Double): Boolean =
        networkError <= MAX_ERROR && networkError < blendError

    /** Whether one in-between in [msPerInBetween] keeps up with playback. */
    fun fastEnough(msPerInBetween: Double): Boolean = msPerInBetween <= BUDGET_MS

    /**
     * The size to make the in-betweens of [width] × [height] material at, from what the self-test measured
     * ([msByHeight]: the test sizes by height, what one in-between took): its own size where the estimate keeps up —
     * from the tested size nearest in pixels, per pixel — else the material brought to the pixels of the largest
     * tested size that kept up, its shape kept and both sides even; null where none did.
     */
    fun workingSize(width: Int, height: Int, msByHeight: Map<Int, Double>): Size? {
        val tested = (FLOW_SIZES + TEST_SIZES).mapNotNull { size -> msByHeight[size.height]?.let { size to it } }
        if (tested.isEmpty()) return null
        val own = Size(width, height)
        val (nearest, nearestMs) = tested.minBy { abs(it.first.area - own.area) }
        if (fastEnough(nearestMs * own.area / nearest.area)) return own
        val (fits, _) = tested.lastOrNull { (size, ms) -> size.area < own.area && fastEnough(ms) } ?: return null
        val scale = sqrt(fits.area.toDouble() / own.area)
        return Size(even(width * scale), even(height * scale))
    }

    private fun even(value: Double): Int = ((value / 2).roundToInt() * 2).coerceAtLeast(2)

    /** [msByHeight] as text to keep: `480=5.5;720=11.25`. */
    fun encode(msByHeight: Map<Int, Double>): String =
        msByHeight.entries.joinToString(";") { (height, ms) -> "$height=$ms" }

    /** What [encode] wrote, read back; parts that do not read are left out. */
    fun decode(text: String): Map<Int, Double> = text.split(';').mapNotNull { part ->
        val height = part.substringBefore('=', "").toIntOrNull()
        val ms = part.substringAfter('=', "").toDoubleOrNull()
        if (height != null && ms != null) height to ms else null
    }.toMap()

    /** A passed self-test: the [unit] (GPU or CPU) the network ran fastest on and what it took there per size. */
    data class Passed(val unit: String, val msByHeight: Map<Int, Double>) {
        /** As text to keep: `GPU|480=5.5;720=11.25`. */
        fun encode(): String = "$unit|${NeuralInterpolation.encode(msByHeight)}"

        companion object {
            /** What [encode] wrote, read back; null where it does not read or holds no times. */
            fun decode(text: String): Passed? {
                val unit = text.substringBefore('|', "")
                val times = NeuralInterpolation.decode(text.substringAfter('|', ""))
                return if (unit.isEmpty() || times.isEmpty()) null else Passed(unit, times)
            }
        }
    }

    /** What to mix: the earlier frame and the in-between ([towardsMiddle]), or the in-between and the later frame. */
    data class Mix(val towardsMiddle: Boolean, val weight: Float)

    /** At [phase] (0 the earlier frame, 1 the later): before the half towards the in-between, after it onwards. */
    fun mix(phase: Float): Mix {
        val p = phase.coerceIn(0f, 1f)
        return if (p < 0.5f) {
            Mix(towardsMiddle = true, weight = p * 2f)
        } else {
            Mix(towardsMiddle = false, weight = p * 2f - 1f)
        }
    }
}
