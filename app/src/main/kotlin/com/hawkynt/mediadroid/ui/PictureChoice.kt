package com.hawkynt.mediadroid.ui

import android.content.Context
import com.hawkynt.mediadroid.data.ModelStore
import com.hawkynt.mediadroid.data.StateStore
import com.hawkynt.mediadroid.player.NeuralEnlarging
import com.hawkynt.mediadroid.player.NeuralInterpolation
import com.hawkynt.mediadroid.player.NeuralModels
import com.hawkynt.mediadroid.player.NeuralSelfTest
import com.hawkynt.mediadroid.player.NeuralTestService
import com.hawkynt.mediadroid.player.NeuralUpscaler
import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.player.PictureCheck
import com.hawkynt.mediadroid.player.PictureChecker
import com.hawkynt.mediadroid.player.PictureTrial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** How trying a choice came out: taken, or refused with why, the choice before kept (see PictureTrial). */
typealias PictureVerdict = com.hawkynt.mediadroid.player.PictureVerdict

/** Tries a picture before it is taken, telling what it is doing meanwhile (see [pictureCheck]). */
typealias PictureTry = suspend (picture: PictureAdjustment, say: (String) -> Unit) -> PictureVerdict

/**
 * The check behind every choice of deinterlacing, upscaling and smooth motion — PictureTrial, the same on every
 * platform — on this phone: the self-tests in their own process (NeuralTestService), the dry run on the real stage
 * (PictureChecker), what passed kept in [store].
 */
fun pictureCheck(context: Context, store: StateStore): PictureTry = { picture, say ->
    withContext(Dispatchers.Default) {
        PictureTrial.check(picture, AndroidTrial(context.applicationContext, store), say)
    }
}

/** What a check does on Android; the settings change on the main thread, as every other change to [store] does. */
private class AndroidTrial(private val context: Context, private val store: StateStore) : PictureTrial.Device {
    private val settings get() = store.data.settings

    private fun save(change: () -> Unit) = runBlocking(Dispatchers.Main) {
        change()
        store.save()
    }

    override val frameMs: Double get() = PictureChecker.frameMs

    override val neuralUpscalingPassed: Boolean get() = settings.neuralUpscaling

    override fun testNeuralUpscaling(): NeuralSelfTest.Timing? =
        NeuralUpscaler.selfTest(context).firstOrNull()?.let { (unit, ms) ->
            NeuralSelfTest.Timing(unit.name, mapOf(PictureCheck.HEIGHT to ms))
        }

    override fun keepNeuralUpscaling(best: NeuralSelfTest.Timing) = save {
        settings.neuralUpscaling = true
        settings.neuralUnit = best.unit
        settings.neuralMs = best.msByHeight.values.first().toFloat()
    }

    override fun enlargerPassed(choice: NeuralEnlarging.Choice) = settings.neuralEnlargerPassed(choice)

    override fun motionPassed(network: PictureAdjustment.Interpolation) = settings.neuralMotionPassed(network)

    override fun modelsReady(download: NeuralModels.Download) = ModelStore.ready(context, download)

    override fun fetch(download: NeuralModels.Download, progress: (Float) -> Unit) =
        ModelStore.fetch(context, download, progress)

    override fun selfTest(choice: NeuralEnlarging.Choice) = NeuralTestService.selfTest(context, choice)

    override fun selfTest(network: PictureAdjustment.Interpolation) = NeuralTestService.selfTest(context, network)

    override fun keepEnlarger(choice: NeuralEnlarging.Choice, passed: NeuralInterpolation.Passed) = save {
        settings.neuralEnlargers = settings.neuralEnlargers + (choice.key to passed.encode())
    }

    override fun keepMotion(network: PictureAdjustment.Interpolation, passed: NeuralInterpolation.Passed) = save {
        settings.neuralMotion = settings.neuralMotion + (network.name to passed.encode())
    }

    override fun dryRun(picture: PictureAdjustment): PictureCheck.DryRun? {
        val unit = if (settings.neuralUpscaling) {
            runCatching { NeuralUpscaler.Unit.valueOf(settings.neuralUnit) }.getOrNull()
        } else {
            null
        }
        val motion = settings.neuralMotionOffered.associateWith { settings.neuralMotionPassed(it)!! }
        val enlargers = settings.neuralEnlargersOffered.associateWith { settings.neuralEnlargerPassed(it)!! }
        return PictureChecker.dryRun(context, picture, unit, motion, enlargers)
    }
}
