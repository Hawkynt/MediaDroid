package com.hawkynt.mediadroid.player

/** How trying a picture came out: taken, or refused with why, the picture before kept. */
data class PictureVerdict(val keepsUp: Boolean, val message: String = "")

/**
 * The check behind every choice of deinterlacing, upscaling and smooth motion, on every platform (see PictureCheck): a
 * neural network first passes its own self-test — fetched where the app does not carry it, its result kept by the
 * [Device] — then the whole picture a dry run of the stage, and is refused where the device falls behind. What differs
 * per platform — how a network is tested, how the stage is dry run, where a result is kept — is the [Device]'s.
 */
object PictureTrial {

    /** What a platform does for a check. Each call may block; none is made on a screen's thread. */
    interface Device {
        /** The frame time a dry run plays at. */
        val frameMs: Double

        /** Whether Real-ESRGAN's self-test passed already. */
        val neuralUpscalingPassed: Boolean

        /** Real-ESRGAN's self-test: the unit it ran fastest on and what a 576p picture took there; null where none. */
        fun testNeuralUpscaling(): NeuralSelfTest.Timing?

        /** Keeps Real-ESRGAN as passed on [best]'s unit. */
        fun keepNeuralUpscaling(best: NeuralSelfTest.Timing)

        /** What [upscaler]'s self-test found once it passed; null before. */
        fun enlargerPassed(upscaler: PictureAdjustment.Upscaler): NeuralInterpolation.Passed?

        /** What [network]'s self-test found once it passed; null before. */
        fun motionPassed(network: PictureAdjustment.Interpolation): NeuralInterpolation.Passed?

        /** Whether [download]'s files are on the device. */
        fun modelsReady(download: NeuralModels.Download): Boolean

        /** Fetches [download], telling [progress] how far (0..1); false where it could not. */
        fun fetch(download: NeuralModels.Download, progress: (Float) -> Unit): Boolean

        /** [upscaler]'s self-test (see NeuralSelfTest). */
        fun selfTest(upscaler: PictureAdjustment.Upscaler): NeuralSelfTest.Outcome

        /** [network]'s self-test (see NeuralSelfTest). */
        fun selfTest(network: PictureAdjustment.Interpolation): NeuralSelfTest.Outcome

        /** Keeps [upscaler] as passed. */
        fun keepEnlarger(upscaler: PictureAdjustment.Upscaler, passed: NeuralInterpolation.Passed)

        /** Keeps [network] as passed. */
        fun keepMotion(network: PictureAdjustment.Interpolation, passed: NeuralInterpolation.Passed)

        /** The stage dry run with [picture] (see PictureCheck); null where it could not be set up. */
        fun dryRun(picture: PictureAdjustment): PictureCheck.DryRun?
    }

    /** Tries [picture] on [device], telling [say] what it is doing meanwhile. Blocks. */
    fun check(picture: PictureAdjustment, device: Device, say: (String) -> Unit): PictureVerdict =
        neuralUpscaling(picture, device, say)
            ?: enlarging(picture, device, say)
            ?: motion(picture, device, say)
            ?: dryRun(picture, device, say)

    private fun neuralUpscaling(picture: PictureAdjustment, device: Device, say: (String) -> Unit): PictureVerdict? {
        if (picture.upscaler != PictureAdjustment.Upscaler.NEURAL || device.neuralUpscalingPassed) return null
        say("Testing Real-ESRGAN on this device…")
        val best = device.testNeuralUpscaling()
        val ms = best?.msByHeight?.values?.firstOrNull()
        if (best == null || ms == null) {
            return PictureVerdict(false, "This device cannot run Real-ESRGAN at all; the upscaler stays as it was.")
        }
        if (!NeuralUpscaling.fastEnough(ms)) {
            return PictureVerdict(
                false,
                "This device is too slow for Real-ESRGAN: a 576p picture takes ${duration(ms)} on its fastest unit " +
                    "(${best.unit}), and a frame allows ${NeuralUpscaling.BUDGET_MS.toInt()} ms. The upscaler stays " +
                    "as it was.",
            )
        }
        device.keepNeuralUpscaling(best)
        return null
    }

    private fun enlarging(picture: PictureAdjustment, device: Device, say: (String) -> Unit): PictureVerdict? {
        val upscaler = picture.upscaler
        if (!upscaler.isEnlarger || device.enlargerPassed(upscaler) != null) return null
        fetched(NeuralModels.download(upscaler), upscaler.label, device, say)?.let {
            return PictureVerdict(false, "$it The upscaler stays as it was.")
        }
        say("Testing ${upscaler.label} on this device…")
        val timings = when (val outcome = device.selfTest(upscaler)) {
            NeuralSelfTest.Outcome.Ended -> return PictureVerdict(
                false,
                "${upscaler.label} needed more than this device can give — memory or graphics — and the test was " +
                    "ended. The upscaler stays as it was.",
            )
            is NeuralSelfTest.Outcome.Ran -> outcome.timings
        }
        val best = timings.firstOrNull()
        val ms = best?.msByHeight?.values?.firstOrNull()
        if (best == null || ms == null) {
            return PictureVerdict(
                false,
                "This device cannot run ${upscaler.label} right. The upscaler stays as it was.",
            )
        }
        if (!NeuralEnlarging.fastEnough(ms)) {
            return PictureVerdict(
                false,
                "This device is too slow for ${upscaler.label}: a 576p picture takes ${duration(ms)} on its fastest " +
                    "unit (${best.unit}), and a frame allows ${NeuralEnlarging.BUDGET_MS.toInt()} ms. The upscaler " +
                    "stays as it was.",
            )
        }
        device.keepEnlarger(upscaler, NeuralInterpolation.Passed(best.unit, best.msByHeight))
        return null
    }

    private fun motion(picture: PictureAdjustment, device: Device, say: (String) -> Unit): PictureVerdict? {
        val network = picture.interpolation
        if (!network.isNeural || device.motionPassed(network) != null) return null
        fetched(NeuralModels.download(network), network.label, device, say)?.let {
            return PictureVerdict(false, "$it Smooth motion stays as it was.")
        }
        say("Testing ${network.label} on this device…")
        val timings = when (val outcome = device.selfTest(network)) {
            NeuralSelfTest.Outcome.Ended -> return PictureVerdict(
                false,
                "${network.label} needed more than this device can give — memory or graphics — and the test was " +
                    "ended. Smooth motion stays as it was.",
            )
            is NeuralSelfTest.Outcome.Ran -> outcome.timings
        }
        val best = timings.firstOrNull()
        val smallest = best?.msByHeight?.values?.firstOrNull()
        if (best == null || smallest == null) {
            return PictureVerdict(
                false,
                "This device cannot run ${network.label} at all. Smooth motion stays as it was.",
            )
        }
        if (!NeuralInterpolation.fastEnough(smallest)) {
            return PictureVerdict(
                false,
                "This device is too slow for ${network.label}: a 480p in-between takes ${duration(smallest)} on its " +
                    "fastest unit (${best.unit}), and playback allows ${NeuralInterpolation.BUDGET_MS.toInt()} ms. " +
                    "Smooth motion stays as it was.",
            )
        }
        device.keepMotion(network, NeuralInterpolation.Passed(best.unit, best.msByHeight))
        return null
    }

    // Fetched once, its digests checked then; after that the files' sizes are enough. Why it failed, or null.
    private fun fetched(download: NeuralModels.Download?, label: String, device: Device, say: (String) -> Unit):
        String? {
        if (download == null || device.modelsReady(download)) return null
        val ok = device.fetch(download) { say("Fetching $label… ${(it * 100).toInt()} %") }
        return if (ok) null else "$label could not be fetched. Is the device online?"
    }

    private fun dryRun(picture: PictureAdjustment, device: Device, say: (String) -> Unit): PictureVerdict {
        say("Trying it on this device…")
        val run = device.dryRun(picture)
            ?: return PictureVerdict(false, "The picture could not be tried on this device; it stays as it was.")
        val frameMs = device.frameMs
        if (PictureCheck.keepsUp(run, frameMs)) return PictureVerdict(true)
        val budget = (PictureCheck.SHARE_OF_FRAME * frameMs).toInt()
        return PictureVerdict(
            false,
            if (run.late > 0) {
                "This device falls behind with it: ${run.late} of ${run.frames} frames came too late. It stays as it " +
                    "was."
            } else {
                "This device falls behind with it: a 576p frame takes ${run.workMs.toInt()} ms of the " +
                    "${frameMs.toInt()} ms it lasts, and smooth playback leaves $budget ms. It stays as it was."
            },
        )
    }

    /** [ms] as a person reads it; a run the self-test cut short (NeuralInterpolation.cutShort) as more than that. */
    fun duration(ms: Double): String = when {
        NeuralInterpolation.cutShort(ms) -> "more than ${(NeuralInterpolation.MAX_WAIT_MS / 1000).toInt()} seconds"
        else -> "${ms.toInt()} ms"
    }
}
