package com.hawkynt.mediadroid.desktop

import com.hawkynt.mediadroid.engine.desktop.StageDryRun
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
 * What a picture check (PictureTrial, the same as on Android) does on this computer: the dry run on a stage of its own
 * (StageDryRun), drawing at the size the largest screen would show a 576p picture, at that screen's refresh rate. The
 * neural networks are not offered on the desktop yet, so their tests find nothing to run.
 */
class DesktopTrial : PictureTrial.Device {
    override val frameMs: Double get() = StageDryRun.frameMs

    override val neuralUpscalingPassed: Boolean get() = false

    override fun testNeuralUpscaling(): NeuralSelfTest.Timing? = null

    override fun keepNeuralUpscaling(best: NeuralSelfTest.Timing) = Unit

    override fun enlargerPassed(upscaler: PictureAdjustment.Upscaler): NeuralInterpolation.Passed? = null

    override fun motionPassed(network: PictureAdjustment.Interpolation): NeuralInterpolation.Passed? = null

    override fun modelsReady(download: NeuralModels.Download) = false

    override fun fetch(download: NeuralModels.Download, progress: (Float) -> Unit) = false

    override fun selfTest(upscaler: PictureAdjustment.Upscaler) = NeuralSelfTest.Outcome.Ran(emptyList())

    override fun selfTest(network: PictureAdjustment.Interpolation) = NeuralSelfTest.Outcome.Ran(emptyList())

    override fun keepEnlarger(upscaler: PictureAdjustment.Upscaler, passed: NeuralInterpolation.Passed) = Unit

    override fun keepMotion(network: PictureAdjustment.Interpolation, passed: NeuralInterpolation.Passed) = Unit

    override fun dryRun(picture: PictureAdjustment): PictureCheck.DryRun? {
        val screen = largestScreen()
        val scale = minOf(screen.width.toDouble() / PictureCheck.WIDTH, screen.height.toDouble() / PictureCheck.HEIGHT)
        val width = (PictureCheck.WIDTH * scale).toInt().coerceAtLeast(PictureCheck.WIDTH)
        val height = (PictureCheck.HEIGHT * scale).toInt().coerceAtLeast(PictureCheck.HEIGHT)
        val hz = screen.refreshRate.takeIf { it != DisplayMode.REFRESH_RATE_UNKNOWN } ?: DEFAULT_REFRESH_HZ
        return StageDryRun.run(picture, width, height, hz.toDouble())
    }

    private fun largestScreen(): DisplayMode = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.displayMode }
            .maxBy { it.width.toLong() * it.height }
    }.getOrElse { DisplayMode(1920, 1080, 32, DEFAULT_REFRESH_HZ) }

    private companion object {
        const val DEFAULT_REFRESH_HZ = 60
    }
}
