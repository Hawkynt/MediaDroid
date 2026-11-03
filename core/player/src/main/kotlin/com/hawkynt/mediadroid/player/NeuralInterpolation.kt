package com.hawkynt.mediadroid.player

/**
 * Neural frame interpolation (RIFE 4.25 lite, Practical-RIFE, MIT): the network makes the picture halfway between two
 * frames, at a fixed size the frames are brought to ([WIDTH] × [HEIGHT], 576p padded to a multiple of 128 as RIFE
 * pads). On screen the picture then runs from the earlier frame through that in-between to the later one, each half
 * blended (see [mix]), so it doubles the frame rate exactly and suits any refresh between.
 */
object NeuralInterpolation {
    /** The size the network takes and gives frames at. */
    const val WIDTH = 768
    const val HEIGHT = 640

    /** What one in-between may take: half a frame at 25 frames a second, so 25 can become 50. */
    const val BUDGET_MS = 20.0

    /** Whether a device that makes a 576p in-between in [msPerInBetween] keeps up with playback. */
    fun fastEnough(msPerInBetween: Double): Boolean = msPerInBetween <= BUDGET_MS

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
