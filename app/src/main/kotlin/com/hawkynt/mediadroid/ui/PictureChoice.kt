package com.hawkynt.mediadroid.ui

import android.content.Context
import com.hawkynt.mediadroid.data.ModelStore
import com.hawkynt.mediadroid.state.StateStore
import com.hawkynt.mediadroid.player.Colorizing
import com.hawkynt.mediadroid.player.ModelFolder
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

    override fun colorizerPassed() = settings.colorizerPassed

    override fun selfTestColorizer() = NeuralTestService.selfTestColorizer(context)

    override fun keepColorizer(passed: NeuralInterpolation.Passed) = save {
        settings.neuralColorizer = passed.encode()
    }

    override fun refuseColorizer() = save {
        settings.neuralColorizerRefusedFor = Colorizing.refusalKey(appVersion(context))
    }

    override fun dryRun(picture: PictureAdjustment): PictureCheck.DryRun? {
        val unit = if (settings.neuralUpscaling) {
            runCatching { NeuralUpscaler.Unit.valueOf(settings.neuralUnit) }.getOrNull()
        } else {
            null
        }
        val motion = settings.neuralMotionOffered.associateWith { settings.neuralMotionPassed(it)!! }
        val enlargers = settings.neuralEnlargersOffered.associateWith { settings.neuralEnlargerPassed(it)!! }
        return PictureChecker.dryRun(context, picture, unit, motion, enlargers, settings.colorizerPassed)
    }
}

/**
 * The colourisers a video's picture offers (Colorizing.offered): DDColor where its self-test passed here, its files are
 * on the device or they can be fetched — so its chip appears by itself once the files are published — but never on a
 * device its test found too slow or wrong for it, until the app or the model changes.
 */
@androidx.compose.runtime.Composable
fun rememberColorizers(context: Context, store: StateStore): List<PictureAdjustment.Colorize> {
    val settings = store.data.settings
    val refused = Colorizing.refused(settings.neuralColorizerRefusedFor, appVersion(context))
    val passed = settings.colorizerPassed != null
    return androidx.compose.runtime.produceState(Colorizing.offered(passed && !refused), passed, refused) {
        value = if (refused) {
            Colorizing.offered(false)
        } else {
            withContext(Dispatchers.IO) {
                Colorizing.offered(
                    passed || ModelStore.ready(context, NeuralModels.DDCOLOR) ||
                        ModelFolder.reachable(NeuralModels.DDCOLOR),
                )
            }
        }
    }.value
}

/** This app's version code (the build's date, YYYYMMDD). */
private fun appVersion(context: Context): Long =
    androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(
        context.packageManager.getPackageInfo(context.packageName, 0),
    )
