package com.hawkynt.mediadroid.desktop

import com.hawkynt.mediadroid.engine.desktop.DesktopNeural
import com.hawkynt.mediadroid.engine.desktop.GlStage
import com.hawkynt.mediadroid.engine.desktop.NeuralTestProcess
import com.hawkynt.mediadroid.engine.desktop.StageDryRun
import com.hawkynt.mediadroid.player.ModelFolder
import com.hawkynt.mediadroid.player.NeuralInterpolation
import com.hawkynt.mediadroid.player.NeuralModels
import com.hawkynt.mediadroid.player.NeuralSelfTest
import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.player.PictureCheck
import com.hawkynt.mediadroid.player.PictureTrial
import com.hawkynt.mediadroid.player.PictureVerdict
import java.awt.DisplayMode
import java.awt.GraphicsEnvironment

/** Tries a picture before it is taken, telling what it is doing meanwhile (PictureTrial). */
typealias PictureTry = suspend (picture: PictureAdjustment, say: (String) -> Unit) -> PictureVerdict

/**
 * What a picture check (PictureTrial, the same as on Android) does on this computer: a network's self-test in
 * processes of its own (NeuralTestProcess) with its files in [neural]'s models folder — fetched there when picked,
 * where the app does not carry them — its pass kept in [store]; the dry run on a stage of its own (StageDryRun) with
 * the networks passed so far, drawing at the size the largest screen would show a 576p picture, at that screen's
 * refresh rate. Real-ESRGAN is not offered on the desktop yet.
 */
class DesktopTrial(private val store: DesktopStore, private val neural: DesktopNeural) : PictureTrial.Device {
    override val frameMs: Double get() = StageDryRun.frameMs

    override val neuralUpscalingPassed: Boolean get() = false

    override fun testNeuralUpscaling(): NeuralSelfTest.Timing? = null

    override fun keepNeuralUpscaling(best: NeuralSelfTest.Timing) = Unit

    override fun enlargerPassed(upscaler: PictureAdjustment.Upscaler) = store.enlargerPassed(upscaler)

    override fun motionPassed(network: PictureAdjustment.Interpolation) = store.motionPassed(network)

    override fun modelsReady(download: NeuralModels.Download) = neural.ready(download)

    override fun fetch(download: NeuralModels.Download, progress: (Float) -> Unit) =
        ModelFolder.fetch(neural.folder(download), download, progress)

    override fun selfTest(upscaler: PictureAdjustment.Upscaler) = NeuralTestProcess.selfTest(neural.models, upscaler)

    override fun selfTest(network: PictureAdjustment.Interpolation) = NeuralTestProcess.selfTest(neural.models, network)

    override fun keepEnlarger(upscaler: PictureAdjustment.Upscaler, passed: NeuralInterpolation.Passed) =
        store.keepEnlarger(upscaler, passed)

    override fun keepMotion(network: PictureAdjustment.Interpolation, passed: NeuralInterpolation.Passed) =
        store.keepMotion(network, passed)

    override fun dryRun(picture: PictureAdjustment): PictureCheck.DryRun? {
        val screen = largestScreen()
        val scale = minOf(screen.width.toDouble() / PictureCheck.WIDTH, screen.height.toDouble() / PictureCheck.HEIGHT)
        val width = (PictureCheck.WIDTH * scale).toInt().coerceAtLeast(PictureCheck.WIDTH)
        val height = (PictureCheck.HEIGHT * scale).toInt().coerceAtLeast(PictureCheck.HEIGHT)
        val hz = screen.refreshRate.takeIf { it != DisplayMode.REFRESH_RATE_UNKNOWN } ?: DEFAULT_REFRESH_HZ
        val passed = GlStage.Neural(neural, store.neuralMotion, store.neuralEnlargers)
        return StageDryRun.run(picture, width, height, hz.toDouble(), passed)
    }

    private fun largestScreen(): DisplayMode = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.displayMode }
            .maxBy { it.width.toLong() * it.height }
    }.getOrElse { DisplayMode(1920, 1080, 32, DEFAULT_REFRESH_HZ) }

    private companion object {
        const val DEFAULT_REFRESH_HZ = 60
    }
}
