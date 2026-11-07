package com.hawkynt.mediadroid.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.decoder.VideoDecoderOutputBuffer
import com.hawkynt.mediadroid.stage.StageCheck
import java.util.concurrent.ConcurrentHashMap

/**
 * The dry run behind PictureCheck: the real picture stage with a picture's filters, fed 576p test frames at their own
 * rate through its input — as a device decoder feeds it — and drawing into an image of the screen's size that nobody
 * shows; once warmed up, it measures what the stage's statistics measure in playback. What a picture's costly part
 * (PictureCheck.key) once showed stays known for as long as the app runs.
 */
object PictureChecker {
    /** The frames fed before measuring: the stage's history fills, its programs build. */
    private const val WARM_FRAMES = 12

    /** The frames measured: with the warm-up, under two seconds all told. */
    private const val MEASURED_FRAMES = 30

    private val known = ConcurrentHashMap<String, PictureCheck.DryRun>()

    /** The frame time a check plays at: 576p's 25 frames a second. */
    val frameMs: Double get() = 1000.0 / PictureCheck.FRAMES_PER_SECOND

    /**
     * What the stage does with [picture] on this device — the neural parts on the units their self-tests chose
     * ([neuralUnit], [neuralMotion], [neuralEnlargers], [colorizer]); null where the stage could not be set up. Takes under two
     * seconds the first time for a picture; blocks — not on the main thread.
     */
    fun dryRun(
        context: Context,
        picture: PictureAdjustment,
        neuralUnit: NeuralUpscaler.Unit?,
        neuralMotion: Map<PictureAdjustment.Interpolation, NeuralInterpolation.Passed>,
        neuralEnlargers: Map<NeuralEnlarging.Choice, NeuralInterpolation.Passed> = emptyMap(),
        colorizer: NeuralInterpolation.Passed? = null,
    ): PictureCheck.DryRun? {
        val key = PictureCheck.key(picture)
        known[key]?.let { return it }
        val metrics = context.resources.displayMetrics
        val screenWidth = maxOf(metrics.widthPixels, metrics.heightPixels)
        val screenHeight = minOf(metrics.widthPixels, metrics.heightPixels)
        val readerThread = HandlerThread("picture-check").apply { start() }
        val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(readerThread.looper))
        val stage = VideoFilterStage()
        val statistics = PlaybackStatistics(capacity = MEASURED_FRAMES)
        val frames = testFrames()
        // A chroma upscaler works on the FFmpeg decoder's planes alone: those it is checked on.
        val planes = if (picture.chroma != PictureAdjustment.Chroma.BILINEAR) frames.map(::yuvFrame) else null
        return try {
            stage.appContext = context.applicationContext
            stage.waitForGpu = true
            stage.neuralUnit = neuralUnit
            stage.neuralMotion = neuralMotion
            stage.neuralEnlargers = neuralEnlargers
            stage.colorizer = colorizer
            stage.filters = picture
            stage.setVideoSize(PictureCheck.WIDTH, PictureCheck.HEIGHT)
            if (!stage.setOutput(reader.surface)) return null
            stage.statistics = statistics
            val intervalNs = 1_000_000_000L / PictureCheck.FRAMES_PER_SECOND
            val start = System.nanoTime()
            var lateBefore = 0
            for (k in 0 until WARM_FRAMES + MEASURED_FRAMES) {
                if (k == WARM_FRAMES) lateBefore = statistics.snapshot().late
                if (planes != null) {
                    stage.setOutputBuffer(yuvBuffer(planes[k % planes.size], k * intervalNs / 1000L))
                } else {
                    val canvas = stage.inputSurface.lockHardwareCanvas()
                    canvas.drawBitmap(frames[k % frames.size], 0f, 0f, null)
                    stage.inputSurface.unlockCanvasAndPost(canvas)
                }
                val next = start + (k + 1) * intervalNs
                val wait = (next - System.nanoTime()) / 1_000_000L
                if (wait > 0) Thread.sleep(wait)
            }
            // The last frame's work ends with the frame after; one more interval for it to be counted.
            Thread.sleep(intervalNs / 1_000_000L)
            val snapshot = statistics.snapshot()
            PictureCheck.DryRun(
                frames = snapshot.graphicsMs.size,
                late = snapshot.late - lateBefore,
                workMs = snapshot.graphicsMs.average().takeIf { !it.isNaN() } ?: 0.0,
            ).also {
                known[key] = it
                android.util.Log.i("PictureChecker", "$key: ${it.frames} frames, ${it.late} late, ${it.workMs} ms each")
            }
        } catch (e: RuntimeException) {
            null
        } finally {
            stage.statistics = null
            stage.setOutput(null)
            stage.release()
            reader.close()
            readerThread.quitSafely()
            frames.forEach { it.recycle() }
        }
    }

    /**
     * Test frames at 576p: smooth random blotches about 12 pixels across, moving 4 pixels across and 2 down a
     * frame, so motion has something to find and the frames differ as video does.
     */
    private fun testFrames(): List<Bitmap> {
        val w = PictureCheck.WIDTH
        val h = PictureCheck.HEIGHT
        return List(StageCheck.PICTURES) { k ->
            val rgba = StageCheck.picture(k)
            val pixels = IntArray(w * h) { i ->
                fun c(n: Int) = rgba[i * 4 + n].toInt() and 0xFF
                (0xFF shl 24) or (c(0) shl 16) or (c(1) shl 8) or c(2)
            }
            Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        }
    }

    /** [frame] as limited-range BT.601 yuv420p, the planes one after the other, as the FFmpeg decoder gives them. */
    private fun yuvFrame(frame: Bitmap): ByteArray {
        val w = frame.width
        val h = frame.height
        val pixels = IntArray(w * h).also { frame.getPixels(it, 0, w, 0, 0, w, h) }
        val rgba = ByteArray(w * h * 4)
        pixels.forEachIndexed { i, p ->
            rgba[i * 4] = (p shr 16).toByte()
            rgba[i * 4 + 1] = (p shr 8).toByte()
            rgba[i * 4 + 2] = p.toByte()
            rgba[i * 4 + 3] = -1
        }
        return StageCheck.planes(rgba)
    }

    /** [planes] (see [yuvFrame]) in a decoder's output buffer, as Media3's FFmpeg decoder fills one. */
    private fun yuvBuffer(planes: ByteArray, timeUs: Long): VideoDecoderOutputBuffer {
        val w = PictureCheck.WIDTH
        val h = PictureCheck.HEIGHT
        val buffer = VideoDecoderOutputBuffer { }
        buffer.init(timeUs, androidx.media3.common.C.VIDEO_OUTPUT_MODE_YUV, null)
        check(buffer.initForYuvFrame(w, h, w, (w + 1) / 2, VideoDecoderOutputBuffer.COLORSPACE_BT601))
        requireNotNull(buffer.data).position(0)
        buffer.data!!.put(planes)
        return buffer
    }

}
