package com.hawkynt.mediadroid.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.decoder.VideoDecoderOutputBuffer
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
     * ([neuralUnit], [neuralMotion], [neuralEnlargers]); null where the stage could not be set up. Takes under two
     * seconds the first time for a picture; blocks — not on the main thread.
     */
    fun dryRun(
        context: Context,
        picture: PictureAdjustment,
        neuralUnit: NeuralUpscaler.Unit?,
        neuralMotion: Map<PictureAdjustment.Interpolation, NeuralInterpolation.Passed>,
        neuralEnlargers: Map<NeuralEnlarging.Choice, NeuralInterpolation.Passed> = emptyMap(),
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
        return List(8) { k ->
            val pixels = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val sx = x + k * 4
                val sy = y + k * 2
                val r = blotch(sx, sy, 0)
                val g = blotch(sx, sy, 1)
                val b = blotch(sx, sy, 2)
                pixels[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        }
    }

    /** [frame] as limited-range BT.601 yuv420p, the planes one after the other, as the FFmpeg decoder gives them. */
    private fun yuvFrame(frame: Bitmap): ByteArray {
        val w = frame.width
        val h = frame.height
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val pixels = IntArray(w * h).also { frame.getPixels(it, 0, w, 0, 0, w, h) }
        val out = ByteArray(w * h + 2 * cw * ch)
        fun channel(x: Int, y: Int, shift: Int) =
            (pixels[y.coerceAtMost(h - 1) * w + x.coerceAtMost(w - 1)] shr shift) and 0xFF
        for (y in 0 until h) for (x in 0 until w) {
            val luma = 0.299 * channel(x, y, 16) + 0.587 * channel(x, y, 8) + 0.114 * channel(x, y, 0)
            out[y * w + x] = (16 + luma * 219 / 255 + 0.5).toInt().toByte()
        }
        for (y in 0 until ch) for (x in 0 until cw) {
            val r = channel(2 * x, 2 * y, 16)
            val g = channel(2 * x, 2 * y, 8)
            val b = channel(2 * x, 2 * y, 0)
            val luma = 0.299 * r + 0.587 * g + 0.114 * b
            out[w * h + y * cw + x] = (128 + (b - luma) / 1.772 * 224 / 255 + 0.5).toInt().toByte()
            out[w * h + cw * ch + y * cw + x] = (128 + (r - luma) / 1.402 * 224 / 255 + 0.5).toInt().toByte()
        }
        return out
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

    private fun blotch(x: Int, y: Int, channel: Int): Int {
        val cell = 12
        val cx = Math.floorDiv(x, cell)
        val cy = Math.floorDiv(y, cell)
        val fx = (x - cx * cell).toFloat() / cell
        val fy = (y - cy * cell).toFloat() / cell
        fun level(i: Int, j: Int): Float {
            var v = i * 73856093 xor j * 19349663 xor channel * 83492791
            v = (v xor (v ushr 13)) * 0x5bd1e995
            return ((v xor (v ushr 15)) and 0xFF).toFloat()
        }
        val top = level(cx, cy) * (1 - fx) + level(cx + 1, cy) * fx
        val bottom = level(cx, cy + 1) * (1 - fx) + level(cx + 1, cy + 1) * fx
        return (top * (1 - fy) + bottom * fy).toInt()
    }
}
