package com.hawkynt.mediadroid.ui

import android.content.Context
import com.hawkynt.mediadroid.data.ModelStore
import com.hawkynt.mediadroid.data.StateStore
import com.hawkynt.mediadroid.player.NeuralEnlarging
import com.hawkynt.mediadroid.player.NeuralInterpolation
import com.hawkynt.mediadroid.player.NeuralModels
import com.hawkynt.mediadroid.player.NeuralTestService
import com.hawkynt.mediadroid.player.NeuralUpscaler
import com.hawkynt.mediadroid.player.NeuralUpscaling
import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.player.PictureCheck
import com.hawkynt.mediadroid.player.PictureChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How trying a choice came out: taken, or refused with why, the choice before kept. */
data class PictureVerdict(val keepsUp: Boolean, val message: String = "")

/** Tries a picture before it is taken, telling what it is doing meanwhile (see [pictureCheck]). */
typealias PictureTry = suspend (picture: PictureAdjustment, say: (String) -> Unit) -> PictureVerdict

/**
 * The check behind every choice of deinterlacing, upscaling and smooth motion (see PictureCheck): a neural network
 * first passes its own self-test — fetched where the app does not carry it, its result kept in [store] — then the
 * whole picture a dry run of the stage, and is refused where the device falls behind.
 */
fun pictureCheck(context: Context, store: StateStore): PictureTry = { picture, say ->
    withContext(Dispatchers.Default) {
        neuralUpscaling(context, store, picture, say)
            ?: neuralEnlarging(context, store, picture, say)
            ?: neuralMotion(context, store, picture, say)
            ?: dryRun(context, store, picture, say)
    }
}

private suspend fun neuralUpscaling(
    context: Context,
    store: StateStore,
    picture: PictureAdjustment,
    say: (String) -> Unit,
): PictureVerdict? {
    val settings = store.data.settings
    if (picture.upscaler != PictureAdjustment.Upscaler.NEURAL || settings.neuralUpscaling) return null
    say("Testing Real-ESRGAN on this device…")
    val best = NeuralUpscaler.selfTest(context.applicationContext).firstOrNull()
    if (best == null || best.second > NeuralUpscaling.BUDGET_MS) {
        return PictureVerdict(
            false,
            if (best == null) {
                "This device cannot run Real-ESRGAN at all; the upscaler stays as it was."
            } else {
                "This device is too slow for Real-ESRGAN: a 576p picture takes ${duration(best.second)} on its " +
                    "fastest unit (${best.first}), and a frame allows ${NeuralUpscaling.BUDGET_MS.toInt()} ms. " +
                    "The upscaler stays as it was."
            },
        )
    }
    withContext(Dispatchers.Main) {
        settings.neuralUpscaling = true
        settings.neuralUnit = best.first.name
        settings.neuralMs = best.second.toFloat()
        store.save()
    }
    return null
}

private suspend fun neuralEnlarging(
    context: Context,
    store: StateStore,
    picture: PictureAdjustment,
    say: (String) -> Unit,
): PictureVerdict? {
    val upscaler = picture.upscaler
    val settings = store.data.settings
    if (!upscaler.isEnlarger || settings.neuralEnlargerPassed(upscaler) != null) return null
    say("Testing ${upscaler.label} on this device…")
    val timings = when (val outcome = NeuralTestService.selfTest(context, upscaler)) {
        NeuralTestService.Outcome.Ended -> return PictureVerdict(
            false,
            "${upscaler.label} needed more than this device can give — memory or graphics — and the test was " +
                "ended. The upscaler stays as it was.",
        )
        is NeuralTestService.Outcome.Ran -> outcome.timings
    }
    val best = timings.firstOrNull()
    val ms = best?.msByHeight?.values?.first()
    if (best == null || ms == null || !NeuralEnlarging.fastEnough(ms)) {
        return PictureVerdict(
            false,
            if (best == null || ms == null) {
                "This device cannot run ${upscaler.label} right. The upscaler stays as it was."
            } else {
                "This device is too slow for ${upscaler.label}: a 576p picture takes ${duration(ms)} on its fastest " +
                    "unit (${best.unit}), and a frame allows ${NeuralEnlarging.BUDGET_MS.toInt()} ms. The upscaler " +
                    "stays as it was."
            },
        )
    }
    val passed = NeuralInterpolation.Passed(best.unit.name, best.msByHeight)
    withContext(Dispatchers.Main) {
        settings.neuralEnlargers = settings.neuralEnlargers + (upscaler.name to passed.encode())
        store.save()
    }
    return null
}

private suspend fun neuralMotion(
    context: Context,
    store: StateStore,
    picture: PictureAdjustment,
    say: (String) -> Unit,
): PictureVerdict? {
    val network = picture.interpolation
    val settings = store.data.settings
    if (!network.isNeural || settings.neuralMotionPassed(network) != null) return null
    // Fetched once, its digests checked then; after that the files' sizes are enough.
    NeuralModels.download(network)?.takeUnless { ModelStore.ready(context, it) }?.let { download ->
        if (!ModelStore.fetch(context, download) { say("Fetching ${network.label}… ${(it * 100).toInt()} %") }) {
            return PictureVerdict(
                false,
                "${network.label} could not be fetched. Is the device online? Smooth motion stays as it was.",
            )
        }
    }
    say("Testing ${network.label} on this device…")
    val timings = when (val outcome = NeuralTestService.selfTest(context, network)) {
        NeuralTestService.Outcome.Ended -> return PictureVerdict(
            false,
            "${network.label} needed more than this device can give — memory or graphics — and the test was " +
                "ended. Smooth motion stays as it was.",
        )
        is NeuralTestService.Outcome.Ran -> outcome.timings
    }
    val best = timings.firstOrNull()
    val smallest = best?.msByHeight?.values?.first()
    if (best == null || smallest == null || !NeuralInterpolation.fastEnough(smallest)) {
        return PictureVerdict(
            false,
            if (best == null || smallest == null) {
                "This device cannot run ${network.label} at all. Smooth motion stays as it was."
            } else {
                "This device is too slow for ${network.label}: a 480p in-between takes ${duration(smallest)} on its " +
                    "fastest unit (${best.unit}), and playback allows ${NeuralInterpolation.BUDGET_MS.toInt()} ms. " +
                    "Smooth motion stays as it was."
            },
        )
    }
    val passed = NeuralInterpolation.Passed(best.unit.name, best.msByHeight)
    withContext(Dispatchers.Main) {
        settings.neuralMotion = settings.neuralMotion + (network.name to passed.encode())
        store.save()
    }
    return null
}

private fun dryRun(context: Context, store: StateStore, picture: PictureAdjustment, say: (String) -> Unit):
    PictureVerdict {
    say("Trying it on this device…")
    val settings = store.data.settings
    val unit = if (settings.neuralUpscaling) {
        runCatching { NeuralUpscaler.Unit.valueOf(settings.neuralUnit) }.getOrNull()
    } else {
        null
    }
    val motion = settings.neuralMotionOffered.associateWith { settings.neuralMotionPassed(it)!! }
    val enlargers = settings.neuralEnlargersOffered.associateWith { settings.neuralEnlargerPassed(it)!! }
    val run = PictureChecker.dryRun(context, picture, unit, motion, enlargers)
        ?: return PictureVerdict(false, "The picture could not be tried on this device; it stays as it was.")
    val frameMs = PictureChecker.frameMs
    if (PictureCheck.keepsUp(run, frameMs)) return PictureVerdict(true)
    val budget = (PictureCheck.SHARE_OF_FRAME * frameMs).toInt()
    return PictureVerdict(
        false,
        if (run.late > 0) {
            "This device falls behind with it: ${run.late} of ${run.frames} frames came too late. It stays as it was."
        } else {
            "This device falls behind with it: a 576p frame takes ${run.workMs.toInt()} ms of the " +
                "${frameMs.toInt()} ms it lasts, and smooth playback leaves $budget ms. It stays as it was."
        },
    )
}

/** [ms] as a person reads it; a run the self-test cut short (see NeuralInterpolation.cutShort) as more than that. */
internal fun duration(ms: Double): String = when {
    NeuralInterpolation.cutShort(ms) -> "more than ${(NeuralInterpolation.MAX_WAIT_MS / 1000).toInt()} seconds"
    else -> "${ms.toInt()} ms"
}
