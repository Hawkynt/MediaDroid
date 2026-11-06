package com.hawkynt.mediadroid.player

/**
 * Whether the device keeps up with a picture — butter-smooth video, always: a choice of deinterlacing, upscaling or
 * smooth motion is tried before it is taken, as a dry run of the real picture stage on 576p frames (see the app's
 * PictureChecker), and refused where the stage falls behind the screen.
 */
object PictureCheck {
    /** The 576p material a check plays: PAL's 720 × 576 at 25 frames a second. */
    const val WIDTH = 720
    const val HEIGHT = 576
    const val FRAMES_PER_SECOND = 25

    /**
     * The share of a frame's time the stage may be busy with it — every picture it draws for that frame, one per
     * screen refresh while blending: the rest is room to spare, for the decoder and for a busier moment.
     */
    const val SHARE_OF_FRAME = 0.8

    /**
     * The frames in which one may come late: Motion's search for a pair, while it settles on its scale — that pair
     * is shown blended, not stuttering. More is a search that cannot keep up.
     */
    const val FRAMES_PER_LATE = 10

    /** What a dry run saw once warmed up: the frames that came, how many came too late, the work per frame. */
    data class DryRun(val frames: Int, val late: Int, val workMs: Double)

    /**
     * Whether [run] kept up with a frame every [frameMs]: hardly anything late (see [FRAMES_PER_LATE]), the work well
     * within the frame's time.
     */
    fun keepsUp(run: DryRun, frameMs: Double): Boolean =
        run.frames > 0 && run.late * FRAMES_PER_LATE <= run.frames && run.workMs <= SHARE_OF_FRAME * frameMs

    /** The costly part of [picture], what a check is about: its colours and light filters left out. */
    fun key(picture: PictureAdjustment): String = listOf(
        picture.deinterlace.name,
        if (picture.fieldRate) "fields" else "frames",
        // A network's model and whether it runs eight ways are part of what it costs.
        NeuralEnlarging.choice(picture)?.key ?: picture.upscaler.name,
        picture.interpolation.name,
        picture.chroma.name,
    ).joinToString("/")

    /**
     * Whether going from [from] to [to] needs a check: anything that adds work does; a row set back to its plainest
     * choice (no deinterlacing, standard scaling, no smooth motion, bilinear chroma) only takes work away.
     */
    fun needed(from: PictureAdjustment, to: PictureAdjustment): Boolean {
        if (key(from) == key(to)) return false
        val lighter = (to.deinterlace == PictureAdjustment.Deinterlace.OFF && from.deinterlace != to.deinterlace) ||
            (to.upscaler == PictureAdjustment.Upscaler.STANDARD && from.upscaler != to.upscaler) ||
            (to.interpolation == PictureAdjustment.Interpolation.OFF && from.interpolation != to.interpolation) ||
            (!to.fieldRate && from.fieldRate) ||
            (to.chroma == PictureAdjustment.Chroma.BILINEAR && from.chroma != to.chroma)
        return !lighter
    }
}
