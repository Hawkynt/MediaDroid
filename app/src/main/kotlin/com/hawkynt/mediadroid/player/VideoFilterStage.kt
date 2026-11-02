package com.hawkynt.mediadroid.player

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.media3.decoder.VideoDecoderOutputBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch

/**
 * The picture filters' own GL stage between the decoder and the screen, for what a colour matrix
 * cannot do: denoising, sharpening, clarity. The decoder — the device's or the bundled FFmpeg's —
 * draws into [inputSurface]; every frame is copied into a texture of the video's size, filtered, and
 * drawn into the [setOutput] surface (the player view's own TextureView), with the frame's own
 * presentation time. The player uses it only while a filter is set; otherwise the decoder draws
 * straight onto the view, as before, which keeps HDR untouched.
 */
class VideoFilterStage : androidx.media3.exoplayer.video.VideoDecoderOutputBufferRenderer {

    private val thread = HandlerThread("video-filters").apply { start() }
    private val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private lateinit var config: EGLConfig
    private var idle: EGLSurface = EGL14.EGL_NO_SURFACE
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var windowSurface: Surface? = null

    private var oesTexture = 0

    // Whether the context is OpenGL ES 3, which reads pictures back into a buffer without waiting.
    private var es3 = false

    // Motion's read-back on ES 3: two pixel buffers taking turns; each holds one frame's packed luma until the
    // next frame comes and collects it, long finished on the graphics chip by then.
    private val lumaBuffers = IntArray(2)
    private val lumaBufferSizes = IntArray(2)
    private val lumaPending = arrayOfNulls<PendingLuma>(2)
    private var lumaSlot = 0

    /** A packed-luma read-back on its way: the frame's arrival, and the layout to unpack it with. */
    private class PendingLuma(val id: Long, val columns: Int, val rows: Int, val texels: Int)
    private lateinit var surfaceTexture: SurfaceTexture
    private val texMatrix = FloatArray(16)

    // FFmpeg's pictures arrive as YUV planes (see setOutputBuffer) rather than through inputSurface: the
    // newest one waiting, the textures its planes go into, and whether the frame being drawn is one.
    private val yuvQueue = java.util.concurrent.ConcurrentLinkedDeque<Arrival>()
    private val pacer = FramePacer()
    private val pumpYuv = Runnable { waitForRefresh() }
    private val onYuvVsync = android.view.Choreographer.FrameCallback { busy { pumpYuv(it) } }
    private var yuvWaiting = false
    private var lastVsyncNs = 0L
    private var vsyncPeriodNs = DEFAULT_VSYNC_NS
    private val yuvTextures = IntArray(3)
    private var yuvProgram = 0
    private var yuvInput = false
    private val yuvColor = FloatArray(9)
    private var yuvChroma = 1f

    /** When the frame being drawn arrived, on the clock eglPresentationTimeANDROID and the history use. */
    private var frameTimestamp = 0L
    private val croppedMatrix = FloatArray(16)

    /** The frame's own transform, after the crop to one eye of a 3D frame when one is chosen. */
    private fun frameMatrix(): FloatArray {
        val stereo = filters.stereo
        if (stereo == PictureAdjustment.Stereo.OFF) return texMatrix
        android.opengl.Matrix.multiplyMM(croppedMatrix, 0, texMatrix, 0, stereo.cropMatrix(), 0)
        return croppedMatrix
    }
    private val identity = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }

    private var copyProgram = 0
    private var deinterlaceProgram = 0
    private var denoiseProgram = 0
    private var sharpenProgram = 0
    private var lanczosProgram = 0
    private var downscaleProgram = 0
    private var ewaProgram = 0
    private var ewaTable = 0
    private var nisProgram = 0
    private var nisTable = 0
    private var easuProgram = 0
    private var rcasProgram = 0
    private var blendProgram = 0
    private var temporalProgram = 0
    private var weaveProgram = 0
    private var packProgram = 0
    private val frames = Array(REDUCED + 1) { Frame() }

    // Temporal deinterlacing: the last three decoded frames (frames[8] to [10], taking turns), oldest
    // first, with when each was due; the picture shown is the middle one, one frame behind.
    private val history = intArrayOf(8, 9, 10)
    private val historyAt = LongArray(3)
    private var historyCount = 0
    // The frames' interval, averaged: decoders hand frames over on the screen's refreshes, so one gap
    // alone is uneven (25 fps on 60 Hz arrives 33 and 50 ms apart).
    private var frameIntervalNs = 0L
    private var shownAt = 0L
    private var deinterlaceLatencyMs = 0
    private var deinterlaceFrames = 0f
    private val secondField = Runnable { busy { drawSecondField() } }

    // Which field comes first, told from the pictures (see FieldOrder): the first frames after a start or
    // a seek are read back as luma — every fourth column, rows from the top — and judged in threes.
    private val fieldOrder = FieldOrder()
    private val lumas = arrayOfNulls<Deinterlacer.Plane>(3)
    private var framesToJudge = 0
    private var readBack: ByteBuffer? = null

    // Film (3:2 pulldown removed, see Telecine): each frame's fields matched on a thread of its own, woven
    // into one of two banks of five slots (frames[11] on), and once a bank's cycle is judged its four kept
    // frames shown evenly. The matcher and the decimator live on that thread; a new history (a seek) starts
    // a new generation, whose verdicts alone count.
    private val filmWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var fieldMatch = Telecine.FieldMatch()
    private var decimate = Telecine.Decimate(mixed = true, duplicatePercent = FILM_DUPLICATE_PERCENT)
    private var filmPending: java.util.concurrent.Future<FilmVerdict>? = null
    private var filmGeneration = 0

    // A new history waits to reach the worker: its first frame has no neighbours, so nothing is judged yet.
    private var filmAfresh = false
    private val filmLumas = arrayOfNulls<Deinterlacer.Plane>(3)
    private var filmBank = 0
    private var filmPosition = 0
    private var filmShown = -1
    private val filmToken = Any()

    // Frame blending: the last three filtered frames (frames[5], [6] and [KEPT_THIRD], taking turns), oldest
    // first, when each arrived, and whether the screen still has a blend to show. Blending mixes the last two;
    // Motion the two before, a frame later, which leaves the motion search a whole frame's time.
    private val keptRing = intArrayOf(5, 6, KEPT_THIRD)
    private val keptAt = LongArray(3)
    private var kept = 0
    private var blending = false
    private val vsync by lazy { android.view.Choreographer.getInstance() }

    // Motion: the kept frames' luma at the search's scale (see SearchScale), the search between each pair
    // on a thread of its own, and its vectors as a texture once in — for the pair whose later frame arrived
    // at vectorsFor.
    private val motionSearch = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val motionLumas = arrayOfNulls<Deinterlacer.Plane>(2)
    private var estimator: MotionInterpolation.Estimator? = null
    private val sceneChange = MotionInterpolation.SceneChange()
    // Two vector textures taking turns: the next pair's search lands while the pair before is still on screen.
    private val vectorsFor = LongArray(2)
    private val vectorsCut = BooleanArray(2)
    private val vectorTextures = IntArray(2)
    // Per vector texture: its grid of blocks and the scale its search ran at.
    private val gridWidth = IntArray(2)
    private val gridHeight = IntArray(2)
    private val gridScale = IntArray(2) { 2 }
    // The search's scale, chosen as it plays from how long searches take (see SearchScale).
    private var searchScale: SearchScale? = null
    private var motionProgram = 0
    private var obmcTexture = 0
    private val onVsync = android.view.Choreographer.FrameCallback { busy { blend(it) } }

    /** Where the decoder draws. */
    lateinit var inputSurface: Surface
        private set

    /** Where the stage tells how playback goes, while the statistics overlay is on; null otherwise. */
    @Volatile
    var statistics: PlaybackStatistics? = null

    // How long this thread has worked since the last decoded frame, for the statistics.
    private var busyNanos = 0L

    /** Runs [work] on this thread, counting its time towards the frame's. */
    private inline fun busy(work: () -> Unit) {
        val start = System.nanoTime()
        try {
            work()
        } finally {
            busyNanos += System.nanoTime() - start
        }
    }

    // The last blend drawn, so a refresh that changes nothing does not count as a new picture.
    private var lastBlend = -1L

    /** The filters to run; read on every frame. */
    @Volatile
    var filters: PictureAdjustment = PictureAdjustment.Neutral

    /** How a picture larger than 1080p is brought down after deinterlacing (see [reduced]). */
    @Volatile
    var downscaler: Downscaler = Downscaler.DEFAULT

    /** The size the stage takes the decoder's frame at: the 3D eye's half, pixel for pixel, or the whole frame. */
    private fun eyeSize(): PictureSize =
        PictureSize(videoWidth.takeIf { it > 0 } ?: surfaceWidth(), videoHeight.takeIf { it > 0 } ?: surfaceHeight())
            .eye(filters.stereo)

    @Volatile
    private var videoWidth = 0

    @Volatile
    private var videoHeight = 0

    private var hasFrame = false

    // The film grain the pass drawing onto the screen adds, and a new seed for every picture shown.
    private var grainNow = 0f
    private var grainToShow = 0f
    private var seed = 0f

    init {
        onThread { setUp() }
    }

    /** The decoded picture's size, for the filters' pixel steps. */
    fun setVideoSize(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
    }

    /**
     * Draws into [surface] from now on, or nowhere for null; returns once the old surface is let go,
     * with whether the new one could be taken — a surface another producer still holds cannot.
     */
    fun setOutput(surface: Surface?): Boolean {
        var taken = surface == null
        onThread {
            releaseWindow()
            if (surface != null && surface.isValid) {
                window = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
                    ?: EGL14.EGL_NO_SURFACE
                taken = window != EGL14.EGL_NO_SURFACE
                if (taken) {
                    windowSurface = surface
                    if (hasFrame) draw(redraw = true)
                }
            }
        }
        return taken
    }

    /**
     * A fresh [inputSurface] over the same picture, handed to [attach] before the old one is let go. A
     * software decoder that drew into the old one (FFmpeg's, through ANativeWindow_lock) stays connected
     * to it as a CPU producer for as long as that surface lives, and no device decoder can attach meanwhile.
     */
    fun renewInput(attach: (Surface) -> Unit) {
        val old = inputSurface
        inputSurface = Surface(surfaceTexture)
        attach(inputSurface)
        old.release()
    }

    /**
     * A picture from a decoder that hands over YUV planes — the bundled FFmpeg's. They are uploaded as
     * textures here, which keeps the decoder clear of a window's buffer queue: on some devices a buffer the
     * processor writes into a window waits on the order of 100 ms to be free again. Called on the playback
     * thread up to 30 ms early and now and then two at once, so they wait in a short queue and are drawn evenly
     * spaced (see [FramePacer]); far behind, the oldest is let go unshown.
     */
    override fun setOutputBuffer(outputBuffer: VideoDecoderOutputBuffer) {
        yuvQueue.addLast(Arrival(outputBuffer, System.nanoTime()))
        while (yuvQueue.size > MAX_YUV_QUEUED) yuvQueue.pollFirst()?.buffer?.release()
        handler.post(pumpYuv)
    }

    /** A picture handed over, with when; [counted] once the pacer has heard of it. */
    private class Arrival(val buffer: VideoDecoderOutputBuffer, val atNs: Long) {
        var counted = false
    }

    /** Asks for the next screen refresh, once, while pictures wait. */
    private fun waitForRefresh() {
        if (yuvWaiting || yuvQueue.isEmpty()) return
        yuvWaiting = true
        vsync.postFrameCallback(onYuvVsync)
    }

    /**
     * At a screen refresh ([frameTimeNanos]): the oldest waiting picture is drawn when the pacer has it due by
     * half a refresh from now, so pictures fall on whole refreshes — with Even frames each one on the same
     * number of them — and the next refresh is asked for while more wait.
     */
    private fun pumpYuv(frameTimeNanos: Long) {
        yuvWaiting = false
        val period = frameTimeNanos - lastVsyncNs
        if (period in MIN_VSYNC_NS..MAX_VSYNC_NS) vsyncPeriodNs += (period - vsyncPeriodNs) / 8
        lastVsyncNs = frameTimeNanos
        yuvQueue.forEach {
            if (!it.counted) {
                it.counted = true
                pacer.arrived(it.atNs)
            }
        }
        if (yuvQueue.isEmpty()) return
        if (pacer.dueAt(frameTimeNanos, yuvQueue.size) - frameTimeNanos <= vsyncPeriodNs / 2) {
            yuvQueue.pollFirst()?.let { arrival ->
                pacer.shown(frameTimeNanos)
                takeYuv(arrival.buffer)
            }
        }
        waitForRefresh()
    }

    private fun takeYuv(buffer: VideoDecoderOutputBuffer) {
        try {
            val planes = buffer.yuvPlanes ?: return
            val strides = buffer.yuvStrides ?: return
            if (buffer.width <= 0 || buffer.height <= 0 || strides[0] <= 0 || strides[1] <= 0) return
            EGL14.eglMakeCurrent(display, idle, idle, context)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            for (i in 0 until 3) {
                val rows = if (i == 0) buffer.height else (buffer.height + 1) / 2
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, yuvTextures[i])
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, strides[i], rows, 0,
                    GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, planes[i].position(0),
                )
            }
            // Rows run from the top in the planes, from the bottom in texture coordinates; the stride's padding
            // right of the picture is cut off.
            val lumaPart = buffer.width.toFloat() / strides[0]
            yuvChroma = ((buffer.width + 1) / 2).toFloat() / strides[1] / lumaPart
            texMatrix.fill(0f)
            texMatrix[0] = lumaPart
            texMatrix[5] = -1f
            texMatrix[10] = 1f
            texMatrix[13] = 1f
            texMatrix[15] = 1f
            System.arraycopy(
                when (buffer.colorspace) {
                    VideoDecoderOutputBuffer.COLORSPACE_BT601 -> BT601
                    VideoDecoderOutputBuffer.COLORSPACE_BT2020 -> BT2020
                    else -> BT709
                },
                0, yuvColor, 0, 9,
            )
            yuvInput = true
            draw(redraw = false)
        } finally {
            buffer.release()
        }
    }

    /** The frame just arrived into the bound framebuffer: the decoder's surface, or FFmpeg's planes. */
    private fun copyInput() {
        if (!yuvInput) {
            run(copyProgram, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture, frameMatrix()) {}
            return
        }
        run(yuvProgram, GLES20.GL_TEXTURE_2D, yuvTextures[0], frameMatrix()) { p ->
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, yuvTextures[1])
            GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uU"), 1)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, yuvTextures[2])
            GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uV"), 2)
            GLES20.glUniformMatrix3fv(GLES20.glGetUniformLocation(p, "uColor"), 1, false, yuvColor, 0)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uChroma"), yuvChroma)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        }
    }

    fun release() {
        handler.removeCallbacks(pumpYuv)
        vsync.removeFrameCallback(onYuvVsync)
        while (true) yuvQueue.pollFirst()?.buffer?.release() ?: break
        onThread {
            GLES20.glDeleteProgram(yuvProgram)
            GLES20.glDeleteTextures(3, yuvTextures, 0)
            releaseWindow()
            GLES20.glDeleteProgram(copyProgram)
            GLES20.glDeleteProgram(deinterlaceProgram)
            GLES20.glDeleteProgram(denoiseProgram)
            GLES20.glDeleteProgram(sharpenProgram)
            GLES20.glDeleteProgram(lanczosProgram)
            GLES20.glDeleteProgram(downscaleProgram)
            GLES20.glDeleteProgram(ewaProgram)
            GLES20.glDeleteTextures(1, intArrayOf(ewaTable), 0)
            GLES20.glDeleteProgram(nisProgram)
            GLES20.glDeleteTextures(1, intArrayOf(nisTable), 0)
            GLES20.glDeleteProgram(easuProgram)
            GLES20.glDeleteProgram(rcasProgram)
            GLES20.glDeleteProgram(blendProgram)
            GLES20.glDeleteProgram(motionProgram)
            GLES20.glDeleteProgram(packProgram)
            if (lumaBuffers[0] != 0) GLES20.glDeleteBuffers(2, lumaBuffers, 0)
            GLES20.glDeleteTextures(3, intArrayOf(vectorTextures[0], vectorTextures[1], obmcTexture), 0)
            motionSearch.shutdownNow()
            GLES20.glDeleteProgram(temporalProgram)
            GLES20.glDeleteProgram(weaveProgram)
            filmWorker.shutdownNow()
            handler.removeCallbacksAndMessages(filmToken)
            handler.removeCallbacks(secondField)
            vsync.removeFrameCallback(onVsync)
            latencyMs = 0
            latencyFrames = 0f
            frames.forEach { it.release() }
            surfaceTexture.release()
            inputSurface.release()
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, idle)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        thread.quitSafely()
    }

    private fun onThread(block: () -> Unit) {
        if (Thread.currentThread() == thread) {
            block()
            return
        }
        val done = CountDownLatch(1)
        handler.post {
            try {
                block()
            } finally {
                done.countDown()
            }
        }
        done.await()
    }

    private fun setUp() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(display, version, 0, version, 1)
        // OpenGL ES 3 where there is one — for reading pictures back without waiting — else ES 2.
        fun choose(renderable: Int): EGLConfig? {
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            EGL14.eglChooseConfig(
                display,
                intArrayOf(
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, renderable,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE,
                ),
                0, configs, 0, 1, count, 0,
            )
            return configs[0].takeIf { count[0] > 0 }
        }
        val es3Config = choose(EGLExt.EGL_OPENGL_ES3_BIT_KHR)
        val es3Context = es3Config?.let {
            EGL14.eglCreateContext(
                display, it, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
            )
        }?.takeIf { it != EGL14.EGL_NO_CONTEXT }
        if (es3Config != null && es3Context != null) {
            config = es3Config
            context = es3Context
            es3 = true
        } else {
            config = choose(EGL14.EGL_OPENGL_ES2_BIT) ?: error("No EGL configuration for the picture filters")
            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
        }
        val onePixel = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        idle = EGL14.eglCreatePbufferSurface(display, config, onePixel, 0)
        EGL14.eglMakeCurrent(display, idle, idle, context)

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        oesTexture = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        surfaceTexture = SurfaceTexture(oesTexture)
        surfaceTexture.setOnFrameAvailableListener({
            yuvInput = false
            busy { draw(redraw = false) }
        }, handler)
        GLES20.glGenTextures(3, yuvTextures, 0)
        yuvTextures.forEach {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, it)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        yuvProgram = program(VERTEX, YUV)
        inputSurface = Surface(surfaceTexture)

        copyProgram = program(VERTEX, COPY)
        deinterlaceProgram = program(VERTEX, DEINTERLACE)
        denoiseProgram = program(VERTEX, DENOISE)
        sharpenProgram = program(VERTEX, SHARPEN)
        lanczosProgram = program(VERTEX, LANCZOS)
        downscaleProgram = program(VERTEX, DOWNSCALE)
        ewaProgram = program(VERTEX, EWA)
        ewaTable = ewaTable()
        nisProgram = program(VERTEX, NIS)
        nisTable = nisTable()
        easuProgram = program(VERTEX, FSR_EASU)
        rcasProgram = program(VERTEX, FSR_RCAS)
        blendProgram = program(VERTEX, BLEND)
        motionProgram = program(VERTEX, MOTION)
        packProgram = program(VERTEX, PACK_LUMA)
        for (k in 0 until 2) vectorTextures[k] = byteTexture(1, 1, ByteArray(4))
        obmcTexture = byteTexture(
            32, 32, ByteArray(32 * 32 * 4) { if (it % 4 == 0) MotionInterpolation.obmcWeight(it / 4).toByte() else 0 },
        )
        temporalProgram = program(VERTEX, TEMPORAL)
        weaveProgram = program(VERTEX, WEAVE)
    }

    private fun releaseWindow() {
        if (window != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, idle, idle, context)
            EGL14.eglDestroySurface(display, window)
            window = EGL14.EGL_NO_SURFACE
        }
        windowSurface = null
    }

    /** One decoded frame through the filters onto the output; [redraw] repeats the last one. */
    private fun draw(redraw: Boolean) {
        if (!redraw) {
            if (yuvInput) {
                frameTimestamp = System.nanoTime()
            } else {
                surfaceTexture.updateTexImage()
                surfaceTexture.getTransformMatrix(texMatrix)
                frameTimestamp = surfaceTexture.timestamp
            }
            hasFrame = true
            statistics?.let { stats ->
                stats.frameArrived(System.nanoTime())
                // The thread's work since the frame before, this frame's own included as it ends below.
                stats.graphics(busyNanos)
            }
            busyNanos = 0L
            handler.removeCallbacks(secondField)
        }
        if (window == EGL14.EGL_NO_SURFACE) return
        val picture = filters
        if (picture.deinterlace == PictureAdjustment.Deinterlace.FILM) {
            if (!redraw) film() else if (filmShown >= 0) showFilm(filmShown)
            return
        }
        handler.removeCallbacksAndMessages(filmToken)
        if (picture.interpolation != PictureAdjustment.Interpolation.OFF) {
            if (!redraw) keep(picture)
            startBlending()
            return
        }
        val (width, height) = eyeSize()
        val source = filtered(picture, width, height, fresh = !redraw)
        latencyMs = deinterlaceLatencyMs
        latencyFrames = deinterlaceFrames
        output(source, source.width, source.height, picture)
        if (!redraw) EGLExt.eglPresentationTimeANDROID(display, window, shownAt)
        EGL14.eglSwapBuffers(display, window)
        if (!redraw) statistics?.pictureShown(System.nanoTime())
        // At field rate the other field follows half a frame later, as its own picture.
        if (!redraw && picture.isFieldRate && historyCount >= 2) {
            val halfMs = frameIntervalNs / 2_000_000L
            if (halfMs in 1..MAX_LATENCY_MS) handler.postDelayed(secondField, halfMs)
        }
    }

    /** The second field of the frame on screen, rebuilt from it and the frame after it. */
    private fun drawSecondField() {
        val picture = filters
        if (window == EGL14.EGL_NO_SURFACE || !picture.isFieldRate || historyCount < 2) return
        val (width, height) = eyeSize()
        val source = filtered(picture, width, height, fresh = false, secondField = true)
        output(source, source.width, source.height, picture)
        EGL14.eglSwapBuffers(display, window)
        statistics?.pictureShown(System.nanoTime())
    }

    /**
     * Steps 1 to 3: the decoder's picture (its 3D eye, if one is chosen), deinterlaced, brought down to 1080p if
     * larger (see [reduced]) and denoised; the frame returned has the size it ends at. [fresh] is a new
     * decoded frame; [secondField] rebuilds the other field of a temporal deinterlacer's picture.
     */
    private fun filtered(
        picture: PictureAdjustment,
        width: Int,
        height: Int,
        fresh: Boolean,
        secondField: Boolean = false,
    ): Frame {
        grainNow = 0f
        EGL14.eglMakeCurrent(display, idle, idle, context)
        if (picture.deinterlace.isTemporal) return temporal(picture, width, height, fresh, secondField)
        historyCount = 0
        deinterlaceLatencyMs = 0
        deinterlaceFrames = 0f
        shownAt = frameTimestamp
        val copied = frames[0].ensure(width, height)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, copied.framebuffer)
        GLES20.glViewport(0, 0, width, height)
        copyInput()
        var source = copied
        if (picture.deinterlace != PictureAdjustment.Deinterlace.OFF) {
            val woven = frames[2].ensure(width, height)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, woven.framebuffer)
            run(deinterlaceProgram, GLES20.GL_TEXTURE_2D, copied.texture, identity) { p ->
                GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uTexel"), 1f / width, 1f / height)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uMode"), picture.deinterlace.ordinal)
            }
            source = woven
        }
        source = reduced(source)
        return denoised(picture, source, source.width, source.height)
    }

    /**
     * YADIF or BWDIF (see [PictureAdjustment.Deinterlace]): a [fresh] frame joins the last three, and
     * the one before it is shown with a field rebuilt from its neighbours — one frame behind, which the
     * sound is told to wait. Until three have come, the missing neighbours are the frames there are.
     */
    private fun temporal(
        picture: PictureAdjustment,
        width: Int,
        height: Int,
        fresh: Boolean,
        secondField: Boolean,
    ): Frame {
        if (fresh || historyCount == 0) {
            remember(width, height)
            if (framesToJudge > 0) judge(width, height)
        }
        val next = frames[history[2]]
        val cur = if (historyCount >= 2) frames[history[1]] else next
        val prev = if (historyCount >= 3) frames[history[0]] else cur
        shownAt = historyAt[if (historyCount >= 2) 1 else 2]
        val intervalMs = if (historyCount >= 2) frameIntervalNs / 1_000_000L else 0L
        deinterlaceLatencyMs = intervalMs.toInt().coerceIn(0, MAX_LATENCY_MS)
        deinterlaceFrames = if (historyCount >= 2) 1f else 0f
        val woven = frames[2].ensure(width, height)
        rebuildField(woven, prev, cur, next, secondField, picture.deinterlace == PictureAdjustment.Deinterlace.BWDIF)
        val shrunk = reduced(woven)
        return denoised(picture, shrunk, shrunk.width, shrunk.height)
    }

    /** YADIF or BWDIF into [target]: [cur] with a field rebuilt from its neighbours. */
    private fun rebuildField(
        target: Frame,
        prev: Frame,
        cur: Frame,
        next: Frame,
        secondField: Boolean,
        bwdif: Boolean,
    ) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.framebuffer)
        GLES20.glViewport(0, 0, target.width, target.height)
        GLES20.glUseProgram(temporalProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prev.texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(temporalProgram, "uPrev"), 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, next.texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(temporalProgram, "uNext"), 2)
        run(temporalProgram, GLES20.GL_TEXTURE_2D, cur.texture, identity) { p ->
            GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uSize"), target.width.toFloat(), target.height.toFloat())
            GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uSecond"), if (secondField) 1 else 0)
            GLES20.glUniform1f(
                GLES20.glGetUniformLocation(p, "uParity"),
                Deinterlacer.rebuiltParity(secondField, fieldOrder.topFieldFirst).toFloat(),
            )
            GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uBwdif"), if (bwdif) 1 else 0)
        }
    }

    /**
     * Film: a decoded frame joins the history, and the one before it is matched with its neighbours'
     * fields (see Telecine) into the bank being filled — or, still combed after matching, deinterlaced by
     * YADIF. Once a bank holds a cycle of five, the one repeated frame is dropped and the other four are
     * shown evenly over the five frames' time, starting now: the film's own 24 a second from 30, about
     * five frames behind, which the sound is told to wait. A cycle with no repeated frame — video, not
     * film — keeps all five, so Film left on for ordinary video costs only the delay.
     */
    private fun film() {
        val (width, height) = eyeSize()
        grainNow = 0f
        EGL14.eglMakeCurrent(display, idle, idle, context)
        // The verdict on the frame before must be woven before the history moves on past its frames.
        finishFilm()
        val afresh = remember(width, height)
        if (afresh) {
            handler.removeCallbacksAndMessages(filmToken)
            filmGeneration++
            filmLumas.fill(null)
            filmPosition = 0
            filmAfresh = true
        }
        val step = if (width > MAX_FULL_FILM_WIDTH) 2 else 1
        val luma = packedLuma(frames[history[2]], width, height, step, 1)
        if (framesToJudge > 0) {
            framesToJudge--
            filmLumas[2]?.let { newest -> filmLumas[1]?.let { fieldOrder.add(it, newest, luma) } }
        }
        filmLumas[0] = filmLumas[1]
        filmLumas[1] = filmLumas[2]
        filmLumas[2] = luma
        if (historyCount < 2) return
        // Film chosen with a history already running has no lumas of its own yet.
        val curLuma = filmLumas[1] ?: return
        val prevLuma = filmLumas[0] ?: curLuma
        val restart = filmAfresh
        filmAfresh = false
        val topFirst = fieldOrder.topFieldFirst
        val generation = filmGeneration
        val prev = if (historyCount >= 3) history[0] else history[1]
        val cur = history[1]
        val next = history[2]
        val interval = frameIntervalNs
        filmPending = filmWorker.submit<FilmVerdict> {
            if (restart || fieldMatch.field != (if (topFirst) 1 else 0)) {
                fieldMatch = Telecine.FieldMatch(topFirst)
                if (restart) decimate = Telecine.Decimate(mixed = true, duplicatePercent = FILM_DUPLICATE_PERCENT)
            }
            val started = System.nanoTime()
            val (matched, woven) = fieldMatch.add(prevLuma, curLuma, luma)
            val cycle = decimate.add(woven)
            statistics?.let { stats ->
                stats.work(System.nanoTime() - started)
                if (cycle != null && cycle.drop >= 0) stats.dropped()
            }
            val verdict = FilmVerdict(
                matched, cycle, fieldMatch.field, prev, cur, next, generation, width, height, interval,
            )
            handler.post { busy { applyFilm(verdict) } }
            verdict
        }
    }

    /** One frame's Film verdict from the worker, with the frames it was judged on. */
    private class FilmVerdict(
        val matched: Telecine.Matched,
        val cycle: Telecine.Decimate.Cycle?,
        val field: Int,
        val prev: Int,
        val cur: Int,
        val next: Int,
        val generation: Int,
        val width: Int,
        val height: Int,
        val intervalNs: Long,
    ) {
        var applied = false
    }

    /** Waits for the verdict still out, if any, and weaves it. */
    private fun finishFilm() {
        val pending = filmPending ?: return
        filmPending = null
        val verdict = runCatching { pending.get() }.getOrNull() ?: return
        applyFilm(verdict)
    }

    /**
     * A verdict woven into the bank being filled — or, still combed after matching, deinterlaced by YADIF —
     * and, at the end of a cycle, the kept frames scheduled evenly over the cycle's time.
     */
    private fun applyFilm(verdict: FilmVerdict) {
        if (verdict.applied || verdict.generation != filmGeneration) return
        verdict.applied = true
        val width = verdict.width
        val height = verdict.height
        val prev = frames[verdict.prev]
        val cur = frames[verdict.cur]
        val next = frames[verdict.next]
        EGL14.eglMakeCurrent(display, idle, idle, context)
        val slot = frames[FILM_SLOTS + filmBank * Telecine.CYCLE + filmPosition].ensure(width, height)
        if (verdict.matched.combed) {
            rebuildField(slot, prev, cur, next, secondField = false, bwdif = false)
        } else {
            val (other, rows) = when (verdict.matched.match) {
                Telecine.Match.PREVIOUS -> prev to verdict.field
                Telecine.Match.NEXT -> next to verdict.field
                Telecine.Match.BOTTOM -> prev to 1 - verdict.field
                Telecine.Match.UPPER -> next to 1 - verdict.field
                Telecine.Match.CURRENT -> cur to -1
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, slot.framebuffer)
            GLES20.glViewport(0, 0, width, height)
            GLES20.glUseProgram(weaveProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, other.texture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(weaveProgram, "uOther"), 1)
            run(weaveProgram, GLES20.GL_TEXTURE_2D, cur.texture, identity) { p ->
                GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uSize"), width.toFloat(), height.toFloat())
                GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uRows"), rows.toFloat())
            }
        }

        val cycle = verdict.cycle
        if (cycle == null) {
            filmPosition++
            return
        }
        val base = FILM_SLOTS + filmBank * Telecine.CYCLE
        // Video with nothing repeated in the cycle (mixed decimation drops none) plays on frame by frame.
        val shown = if (cycle.drop < 0) Telecine.CYCLE else Telecine.CYCLE - 1
        val stepNs = verdict.intervalNs * Telecine.CYCLE / shown
        val start = android.os.SystemClock.uptimeMillis()
        (0 until Telecine.CYCLE).filter { it != cycle.drop }.forEachIndexed { j, position ->
            handler.postAtTime({ busy { showFilm(base + position) } }, filmToken, start + j * stepNs / 1_000_000L)
        }
        latencyMs = (verdict.intervalNs * Telecine.CYCLE / 1_000_000L).toInt().coerceIn(0, MAX_FILM_LATENCY_MS)
        latencyFrames = Telecine.CYCLE.toFloat()
        filmBank = 1 - filmBank
        filmPosition = 0
    }

    /** One of Film's kept frames onto the screen, through the rest of the filters. */
    private fun showFilm(slot: Int) {
        val picture = filters
        if (window == EGL14.EGL_NO_SURFACE || picture.deinterlace != PictureAdjustment.Deinterlace.FILM) return
        val frame = frames[slot]
        if (frame.texture == 0) return
        filmShown = slot
        grainNow = 0f
        EGL14.eglMakeCurrent(display, idle, idle, context)
        val shrunk = reduced(frame)
        val source = denoised(picture, shrunk, shrunk.width, shrunk.height)
        output(source, source.width, source.height, picture)
        EGL14.eglSwapBuffers(display, window)
        statistics?.pictureShown(System.nanoTime())
    }

    /**
     * A fresh decoded frame joins the last three, with when it was due; returns whether the history
     * started afresh — after a seek, a stall or a new size the frames before are no neighbours.
     * Leaves the newest frame's framebuffer bound.
     */
    private fun remember(width: Int, height: Int): Boolean {
        val step = frameTimestamp - historyAt[2]
        if (step <= 0 || step > MAX_FRAME_STEP_NS) {
            historyCount = 0
            frameIntervalNs = 0L
        } else {
            frameIntervalNs = if (frameIntervalNs == 0L) step else (frameIntervalNs * 7 + step) / 8
        }
        val oldest = history[0]
        history[0] = history[1]
        history[1] = history[2]
        history[2] = oldest
        historyAt[0] = historyAt[1]
        historyAt[1] = historyAt[2]
        historyAt[2] = frameTimestamp
        val newest = frames[history[2]]
        if (frames[history[1]].width != width || frames[history[1]].height != height) {
            if (historyCount > 0) fieldOrder.reset()
            historyCount = 0
        }
        val afresh = historyCount == 0
        if (afresh) {
            lumas.fill(null)
            framesToJudge = FRAMES_TO_JUDGE
        }
        newest.ensure(width, height)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, newest.framebuffer)
        GLES20.glViewport(0, 0, width, height)
        copyInput()
        historyCount = minOf(historyCount + 1, 3)
        return afresh
    }

    /** The newest frame's luma, every fourth column, into the field-order verdict. */
    private fun judge(width: Int, height: Int) {
        framesToJudge--
        lumas[0] = lumas[1]
        lumas[1] = lumas[2]
        lumas[2] = packedLuma(frames[history[2]], width, height, JUDGED_COLUMN_STEP, 1)
        val prev = lumas[0] ?: return
        val cur = lumas[1] ?: return
        fieldOrder.add(prev, cur, lumas[2]!!)
    }

    /**
     * [from]'s luma, every [stepX]th column of every [stepY]th row from the top, worked out on the graphics
     * chip four values to a texel, so the read back is a quarter of the samples' bytes — with the weights the
     * decoder's colour went through: BT.601 for standard definition, BT.709 above, so no chroma noise leaks
     * into the luma the verdicts judge.
     */
    private fun packedLuma(from: Frame, width: Int, height: Int, stepX: Int, stepY: Int): Deinterlacer.Plane {
        val (columns, rows, texels) = packLuma(from, width, height, stepX, stepY)
        val size = texels * rows * 4
        val bytes = readBack?.takeIf { it.capacity() == size }
            ?: ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder()).also { readBack = it }
        bytes.rewind()
        GLES20.glReadPixels(0, 0, texels, rows, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
        return unpackLuma(bytes, columns, rows, texels)
    }

    /** The packing pass into the bound framebuffer; returns the columns, rows and texels it wrote. */
    private fun packLuma(from: Frame, width: Int, height: Int, stepX: Int, stepY: Int): Triple<Int, Int, Int> {
        val columns = width / stepX
        val rows = height / stepY
        val texels = (columns + 3) / 4
        val target = frames[PACKED_LUMA].ensure(texels, rows)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.framebuffer)
        GLES20.glViewport(0, 0, texels, rows)
        run(packProgram, GLES20.GL_TEXTURE_2D, from.texture, identity) { p ->
            GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uSize"), width.toFloat(), height.toFloat())
            GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uStep"), stepX.toFloat(), stepY.toFloat())
            GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uSd"), if (height <= SD_HEIGHT) 1 else 0)
        }
        return Triple(columns, rows, texels)
    }

    /** Packed luma bytes back into a plane; the pass wrote row v at framebuffer row v, so rows run from the top. */
    private fun unpackLuma(bytes: ByteBuffer, columns: Int, rows: Int, texels: Int): Deinterlacer.Plane {
        val luma = IntArray(columns * rows)
        for (y in 0 until rows) {
            val line = y * texels * 4
            for (x in 0 until columns) luma[y * columns + x] = bytes.get(line + x).toInt() and 0xFF
        }
        return Deinterlacer.Plane(columns, rows, luma)
    }

    /**
     * On ES 3: starts reading [from]'s packed luma into a pixel buffer without waiting, and returns the one
     * started for the frame before — with that frame's [id] — or null while there is none.
     */
    private fun lumaLater(from: Frame, width: Int, height: Int, step: Int, id: Long): Pair<Long, Deinterlacer.Plane>? {
        val earlier = collectLuma(1 - lumaSlot)
        if (lumaBuffers[0] == 0) GLES20.glGenBuffers(2, lumaBuffers, 0)
        val (columns, rows, texels) = packLuma(from, width, height, step, step)
        val size = texels * rows * 4
        val slot = lumaSlot
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, lumaBuffers[slot])
        if (lumaBufferSizes[slot] != size) {
            GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, size, null, GLES30.GL_STREAM_READ)
            lumaBufferSizes[slot] = size
        }
        GLES30.glReadPixels(0, 0, texels, rows, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, 0)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        lumaPending[slot] = PendingLuma(id, columns, rows, texels)
        lumaSlot = 1 - slot
        return earlier
    }

    /** The read-back waiting in [slot], unpacked, with its frame's id; null when none waits. */
    private fun collectLuma(slot: Int): Pair<Long, Deinterlacer.Plane>? {
        val pending = lumaPending[slot] ?: return null
        lumaPending[slot] = null
        val size = pending.texels * pending.rows * 4
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, lumaBuffers[slot])
        val mapped = GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER, 0, size, GLES30.GL_MAP_READ_BIT)
            as? ByteBuffer
        val plane = mapped?.let {
            unpackLuma(it.order(ByteOrder.nativeOrder()), pending.columns, pending.rows, pending.texels)
        }
        if (mapped != null) GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        return plane?.let { pending.id to it }
    }

    /**
     * [from] brought down to fit 1080p with [downscaler] (see [PictureSize.reduced]), or [from] itself when it
     * fits. Nearest and Bilinear take one sample; the others weigh every pixel a new one spans, across and
     * then down, so a 4K picture costs a few dozen samples a pixel rather than the square of that.
     */
    private fun reduced(from: Frame): Frame {
        val scaler = downscaler
        val size = PictureSize(from.width, from.height).reduced(scaler)
        if (size.width == from.width && size.height == from.height) return from
        val target = frames[REDUCED].ensure(size.width, size.height)
        if (!scaler.separable) {
            val filter = if (scaler == Downscaler.NEAREST) GLES20.GL_NEAREST else GLES20.GL_LINEAR
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, from.texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
            downscalePass(from, target, scaler, 0f, 0f, 1f)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, from.texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            return target
        }
        val across = frames[REDUCED_ACROSS].ensure(size.width, from.height)
        downscalePass(from, across, scaler, 1f, 0f, from.width.toFloat() / size.width)
        downscalePass(across, target, scaler, 0f, 1f, from.height.toFloat() / size.height)
        return target
    }

    /** One pass of [reduced]: [from] into [target] along ([dirX], [dirY]), [scale] old pixels to a new one. */
    private fun downscalePass(from: Frame, target: Frame, scaler: Downscaler, dirX: Float, dirY: Float, scale: Float) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.framebuffer)
        GLES20.glViewport(0, 0, target.width, target.height)
        run(downscaleProgram, GLES20.GL_TEXTURE_2D, from.texture, identity) { p ->
            GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uInSize"), from.width.toFloat(), from.height.toFloat())
            GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uDir"), dirX, dirY)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uScale"), scale)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uRadius"), scaler.radius)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uKernel"), scaler.ordinal)
        }
    }

    /** Step 3: the denoise filter [picture] asks for, if any, over [from]. */
    private fun denoised(picture: PictureAdjustment, from: Frame, width: Int, height: Int): Frame {
        var source = from
        val kernel = picture.denoiseKernel()
        if (kernel != null) {
            val denoised = frames[1].ensure(width, height)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, denoised.framebuffer)
            run(denoiseProgram, GLES20.GL_TEXTURE_2D, source.texture, identity) { p ->
                GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uTexel"), 1f / width, 1f / height)
                val radius = kernel.radius
                val sigmaR = kernel.similarity
                GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uRadius"), radius)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uSigmaS2"), 2f * radius * radius)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uSigmaR2"), 2f * sigmaR * sigmaR)
            }
            source = denoised
        }
        return source
    }

    /** A new frame for blending: filtered and sharpened at its own size, kept with when it arrived. */
    private fun keep(picture: PictureAdjustment) {
        val (width, height) = eyeSize()
        val source = filtered(picture, width, height, fresh = true)
        val keptWidth = source.width
        val keptHeight = source.height
        val oldest = keptRing[0]
        keptRing[0] = keptRing[1]
        keptRing[1] = keptRing[2]
        keptRing[2] = oldest
        keptAt[0] = keptAt[1]
        keptAt[1] = keptAt[2]
        val target = frames[keptRing[2]].ensure(keptWidth, keptHeight)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.framebuffer)
        GLES20.glViewport(0, 0, keptWidth, keptHeight)
        run(sharpenProgram, GLES20.GL_TEXTURE_2D, source.texture, identity) { p ->
            GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uTexel"), 1f / keptWidth, 1f / keptHeight)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uSharpness"), picture.sharpness)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uClarity"), picture.clarity)
        }
        keptAt[2] = System.nanoTime()
        kept = minOf(kept + 1, 3)
        val motion = picture.interpolation.isMotionCompensated
        if (motion) searchMotion(keptWidth, keptHeight) else motionLumas.fill(null)
        // Blending runs half a frame behind, Motion two frames; the sound is told to wait as long.
        if (kept >= 2) {
            val intervalMs = (keptAt[2] - keptAt[1]) / 1_000_000L
            latencyMs = if (motion) {
                (intervalMs * 2).toInt().coerceIn(0, MAX_MOTION_LATENCY_MS)
            } else {
                (intervalMs / 2).toInt().coerceIn(0, MAX_LATENCY_MS)
            } + deinterlaceLatencyMs
            latencyFrames = (if (motion) 2f else 0.5f) + deinterlaceFrames
        }
    }

    /**
     * The newest kept frame's luma read back at the search's scale (see [SearchScale]), and the motion search
     * between it and the one before started on its own thread; its vectors reach one of the two textures
     * once done — the one not holding the pair on screen — named by the later frame's arrival.
     */
    private fun searchMotion(width: Int, height: Int) {
        val pace = searchScale?.takeIf { it.width == width && it.height == height }
            ?: SearchScale(width, height).also { searchScale = it }
        // On ES 3 the read-back is collected a frame later — the search then runs while its pair is on screen;
        // on ES 2 it waits for the graphics chip and the search gets the pair before.
        val (pair, luma) = if (es3) {
            lumaLater(frames[keptRing[2]], width, height, pace.scale, keptAt[2]) ?: return
        } else {
            keptAt[2] to packedLuma(frames[keptRing[2]], width, height, pace.scale, pace.scale)
        }
        val scale = width / luma.width
        val prev = motionLumas[1]
        motionLumas[0] = prev
        motionLumas[1] = luma
        if (prev == null || prev.width != luma.width || prev.height != luma.height) return
        if (luma.width < 2 * MotionInterpolation.BLOCK || luma.height < 2 * MotionInterpolation.BLOCK) return
        motionSearch.execute {
            val started = System.nanoTime()
            val search = estimator?.takeIf { it.width == luma.width && it.height == luma.height }
                ?: MotionInterpolation.Estimator(luma.width, luma.height).also { estimator = it }
            search.advance()
            search.estimate(prev, luma)
            val took = System.nanoTime() - started
            val cut = sceneChange.check(prev, luma)
            val bytes = ByteArray(search.blocksWide * search.blocksHigh * 4)
            for (b in 0 until search.blocksWide * search.blocksHigh) {
                bytes[b * 4] = (search.vectors[b * 2] + VECTOR_ZERO).toByte()
                bytes[b * 4 + 1] = (search.vectors[b * 2 + 1] + VECTOR_ZERO).toByte()
            }
            val wide = search.blocksWide
            val high = search.blocksHigh
            handler.post {
                busy {
                    // Only for the pair on screen or the next one; anything older is past, and the search too slow.
                    val late = pair != keptAt[1] && pair != keptAt[2]
                    if (pace === searchScale) pace.report(took, keptAt[2] - keptAt[1], late)
                    statistics?.let { stats ->
                        stats.work(took)
                        stats.searchScale(scale)
                        if (late) stats.late()
                    }
                    if (late) return@post
                    val slot = if (vectorsFor[0] == keptAt[1]) 1 else 0
                    EGL14.eglMakeCurrent(display, idle, idle, context)
                    GLES20.glDeleteTextures(1, intArrayOf(vectorTextures[slot]), 0)
                    vectorTextures[slot] = byteTexture(wide, high, bytes)
                    gridWidth[slot] = wide
                    gridScale[slot] = scale
                    gridHeight[slot] = high
                    vectorsFor[slot] = pair
                    vectorsCut[slot] = cut
                }
            }
        }
    }

    /** A texture of [width] × [height] RGBA bytes, read texel by texel. */
    private fun byteTexture(width: Int, height: Int, rgba: ByteArray): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        val buffer = ByteBuffer.allocateDirect(rgba.size).order(ByteOrder.nativeOrder()).put(rgba)
        buffer.position(0)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer,
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun startBlending() {
        if (blending) return
        blending = true
        vsync.postFrameCallback(onVsync)
    }

    /**
     * One screen refresh while blending: the previous and the current frame mixed by how far the clock
     * is between them (see [PictureAdjustment.Interpolation.weight]), shown one frame late — with Motion
     * the two frames before those, whose vectors had a whole frame's time to come in. Stops once the later
     * frame shows whole, until the next arrives.
     */
    private fun blend(frameTimeNanos: Long) {
        blending = false
        val picture = filters
        if (window == EGL14.EGL_NO_SURFACE || kept == 0 ||
            picture.interpolation == PictureAdjustment.Interpolation.OFF
        ) {
            return
        }
        val motion = picture.interpolation.isMotionCompensated && kept == 3
        // The pair shown: the two before the newest with Motion, otherwise the newest two.
        val later = if (motion) 1 else 2
        val current = keptRing[later]
        val previous = keptRing[if (kept >= 2) later - 1 else later]
        val width = frames[current].width
        val height = frames[current].height
        val interval = (keptAt[2] - keptAt[1]).takeIf { kept >= 2 && it > 0 } ?: 1L
        val phase = (frameTimeNanos - keptAt[2]).toFloat() / interval
        var weight = if (kept < 2) 1f else picture.interpolation.weight(phase)
        // Motion along the vectors once they are in for this pair; a cut switches half-way, as FFmpeg's does,
        // and without vectors the earlier frame stays until the later one is due.
        val slot = if (vectorsFor[0] == keptAt[1]) 0 else if (vectorsFor[1] == keptAt[1]) 1 else -1
        val alongMotion = motion && slot >= 0 && !vectorsCut[slot] && weight > 0f && weight < 1f
        if (picture.interpolation.isMotionCompensated && !alongMotion && kept >= 2) {
            val due = if (motion && slot >= 0 && vectorsCut[slot]) 0.5f else 1f
            weight = if (phase < due) 0f else 1f
        }
        EGL14.eglMakeCurrent(display, idle, idle, context)
        val mixed = frames[7].ensure(width, height)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mixed.framebuffer)
        GLES20.glViewport(0, 0, width, height)
        val program = if (alongMotion) motionProgram else blendProgram
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frames[previous].texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uPrevious"), 1)
        if (alongMotion) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, vectorTextures[slot])
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uVectors"), 2)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, obmcTexture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uObmc"), 3)
        }
        run(program, GLES20.GL_TEXTURE_2D, frames[current].texture, identity) { p ->
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uWeight"), weight)
            GLES20.glUniform1f(
                GLES20.glGetUniformLocation(p, "uAlpha"),
                (weight * MotionInterpolation.ALPHA_MAX).toInt().toFloat(),
            )
            GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uSize"), width.toFloat(), height.toFloat())
            val k = maxOf(slot, 0)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uGrid"), gridWidth[k].toFloat(), gridHeight[k].toFloat())
            val searchWidth = (width / gridScale[k]).toFloat()
            val searchHeight = (height / gridScale[k]).toFloat()
            GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uSearch"), searchWidth, searchHeight)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uScale"), gridScale[k].toFloat())
        }
        output(mixed, width, height, picture.copy(sharpness = 0f, clarity = 0f))
        EGL14.eglSwapBuffers(display, window)
        // A new picture when the pair or the mix changed.
        val blendKey = keptAt[later] * 4099 + (weight * 1024).toLong()
        if (blendKey != lastBlend) {
            lastBlend = blendKey
            statistics?.pictureShown(System.nanoTime())
        }
        if (weight < 1f) startBlending()
    }

    /** Steps 4 and 5: sharpness and clarity, and the picture onto the screen, enlarged as chosen. */
    private fun output(from: Frame, width: Int, height: Int, picture: PictureAdjustment) {
        var source = from
        grainNow = 0f
        grainToShow = filters.grain
        val outWidth = query(EGL14.EGL_WIDTH)
        val outHeight = query(EGL14.EGL_HEIGHT)
        val upscaling = picture.upscales && outWidth > width
        val sharpen: (Int) -> Unit = { p ->
            GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uTexel"), 1f / width, 1f / height)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uSharpness"), picture.sharpness)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uClarity"), picture.clarity)
        }
        if (!upscaling) {
            // 4: sharpness and clarity, drawn onto the screen at its size by its own bilinear scaling.
            onWindow(outWidth, outHeight)
            run(sharpenProgram, GLES20.GL_TEXTURE_2D, source.texture, identity, sharpen)
        } else {
            // 4: sharpness and clarity at the picture's own size, then 5: enlarged onto the screen.
            if (picture.sharpness > 0f || picture.clarity > 0f) {
                val sharpened = frames[3].ensure(width, height)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sharpened.framebuffer)
                run(sharpenProgram, GLES20.GL_TEXTURE_2D, source.texture, identity, sharpen)
                source = sharpened
            }
            val inSize: (Int) -> Unit = { p ->
                GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uInSize"), width.toFloat(), height.toFloat())
            }
            if (picture.upscaler == PictureAdjustment.Upscaler.LANCZOS) {
                onWindow(outWidth, outHeight)
                run(lanczosProgram, GLES20.GL_TEXTURE_2D, source.texture, identity, inSize)
            } else if (picture.upscaler == PictureAdjustment.Upscaler.NIS) {
                // NVIDIA Image Scaling to at most twice the picture's size, as NIS allows, and the screen's own
                // bilinear scaling for whatever is left.
                val midWidth = minOf(outWidth, width * 2)
                val midHeight = minOf(outHeight, height * 2)
                val enlarged = frames[4].ensure(midWidth, midHeight)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, enlarged.framebuffer)
                GLES20.glViewport(0, 0, midWidth, midHeight)
                GLES20.glUseProgram(nisProgram)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, nisTable)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(nisProgram, "uCoef"), 1)
                val config = Nis.Config(NIS_SHARPNESS)
                run(nisProgram, GLES20.GL_TEXTURE_2D, source.texture, identity) { p ->
                    inSize(p)
                    fun set(name: String, value: Float) =
                        GLES20.glUniform1f(GLES20.glGetUniformLocation(p, name), value)
                    GLES20.glUniform2f(
                        GLES20.glGetUniformLocation(p, "uOutSize"), midWidth.toFloat(), midHeight.toFloat(),
                    )
                    set("uDetectRatio", config.detectRatio)
                    set("uDetectThres", config.detectThres)
                    set("uMinContrastRatio", config.minContrastRatio)
                    set("uRatioNorm", config.ratioNorm)
                    set("uSharpStartY", config.sharpStartY)
                    set("uSharpScaleY", config.sharpScaleY)
                    set("uSharpStrengthMin", config.sharpStrengthMin)
                    set("uSharpStrengthScale", config.sharpStrengthScale)
                    set("uSharpLimitMin", config.sharpLimitMin)
                    set("uSharpLimitScale", config.sharpLimitScale)
                }
                onWindow(outWidth, outHeight)
                run(sharpenProgram, GLES20.GL_TEXTURE_2D, enlarged.texture, identity) { p ->
                    GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uTexel"), 1f / midWidth, 1f / midHeight)
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uSharpness"), 0f)
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uClarity"), 0f)
                }
            } else if (picture.upscaler == PictureAdjustment.Upscaler.EWA) {
                // EWA to at most twice the picture's size — its 8 × 8 taps stay affordable — and the screen's
                // own bilinear scaling for whatever is left.
                val midWidth = minOf(outWidth, width * 2)
                val midHeight = minOf(outHeight, height * 2)
                val enlarged = frames[4].ensure(midWidth, midHeight)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, enlarged.framebuffer)
                GLES20.glViewport(0, 0, midWidth, midHeight)
                GLES20.glUseProgram(ewaProgram)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ewaTable)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(ewaProgram, "uTable"), 1)
                run(ewaProgram, GLES20.GL_TEXTURE_2D, source.texture, identity) { p ->
                    inSize(p)
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uRadius"), Ewa.RADIUS.toFloat())
                }
                onWindow(outWidth, outHeight)
                run(sharpenProgram, GLES20.GL_TEXTURE_2D, enlarged.texture, identity) { p ->
                    GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uTexel"), 1f / midWidth, 1f / midHeight)
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uSharpness"), 0f)
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uClarity"), 0f)
                }
            } else {
                // FSR 1: edge-adaptive upscaling to the screen's size, then contrast-adaptive sharpening on it.
                val enlarged = frames[4].ensure(outWidth, outHeight)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, enlarged.framebuffer)
                GLES20.glViewport(0, 0, outWidth, outHeight)
                run(easuProgram, GLES20.GL_TEXTURE_2D, source.texture, identity, inSize)
                onWindow(outWidth, outHeight)
                run(rcasProgram, GLES20.GL_TEXTURE_2D, enlarged.texture, identity) { p ->
                    GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uTexel"), 1f / outWidth, 1f / outHeight)
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uSharp"), RCAS_SHARPNESS)
                }
            }
            // Below full strength the plain picture, as the screen's own scaling gives it, is laid over the
            // upscaled one with that share: the graphics chip mixes the two as it draws.
            val plain = picture.plainShare
            if (plain > 0f) {
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendColor(0f, 0f, 0f, plain)
                GLES20.glBlendFunc(GLES20.GL_CONSTANT_ALPHA, GLES20.GL_ONE_MINUS_CONSTANT_ALPHA)
                run(sharpenProgram, GLES20.GL_TEXTURE_2D, source.texture, identity) { p ->
                    GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uTexel"), 1f / width, 1f / height)
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uSharpness"), 0f)
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uClarity"), 0f)
                }
                GLES20.glDisable(GLES20.GL_BLEND)
            }
        }
    }

    /**
     * The EWA weights by distance (see [Ewa]) as a texture of [EWA_TABLE_SIZE] × 1: each weight shifted
     * and scaled into 0..1 and kept to 16 bits over the red and green channels.
     */
    private fun ewaTable(): Int {
        val weights = Ewa.table(EWA_TABLE_SIZE)
        val bytes = ByteBuffer.allocateDirect(EWA_TABLE_SIZE * 4).order(ByteOrder.nativeOrder())
        for (w in weights) {
            val v = (((w + EWA_OFFSET) / EWA_SPAN) * 65535f).toInt().coerceIn(0, 65535)
            bytes.put((v shr 8).toByte()).put((v and 0xFF).toByte()).put(0).put(0)
        }
        bytes.position(0)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, EWA_TABLE_SIZE, 1, 0, GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE, bytes,
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    /**
     * NIS's two filter tables (see [Nis]) as a 6 × 64 texture: per phase a row, per tap a texel holding the
     * scaling tap in red and green and the sharpening tap in blue and alpha — one fetch for both — each
     * shifted and scaled into 0..1 and kept to 16 bits.
     */
    private fun nisTable(): Int {
        val bytes = ByteBuffer.allocateDirect(6 * Nis.PHASES * 4).order(ByteOrder.nativeOrder())
        fun put(c: Float) {
            val v = (((c + NIS_OFFSET) / NIS_SPAN) * 65535f + 0.5f).toInt().coerceIn(0, 65535)
            bytes.put((v shr 8).toByte()).put((v and 0xFF).toByte())
        }
        for (phase in 0 until Nis.PHASES) {
            for (tap in 0 until 6) {
                put(Nis.scaleCoefficient(phase, tap))
                put(Nis.sharpenCoefficient(phase, tap))
            }
        }
        bytes.position(0)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 6, Nis.PHASES, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes,
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    /** Draws onto the screen from here on. */
    private fun onWindow(width: Int, height: Int) {
        grainNow = grainToShow
        seed = (seed + 1f) % 1000f
        EGL14.eglMakeCurrent(display, window, window, context)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, width, height)
    }

    private fun query(what: Int): Int {
        val value = IntArray(1)
        EGL14.eglQuerySurface(display, window, what, value, 0)
        return value[0]
    }

    private fun surfaceWidth() = if (window != EGL14.EGL_NO_SURFACE) query(EGL14.EGL_WIDTH) else 1

    private fun surfaceHeight() = if (window != EGL14.EGL_NO_SURFACE) query(EGL14.EGL_HEIGHT) else 1

    /** Draws a full-screen quad with [program] sampling [texture]; [uniforms] sets the rest. */
    private fun run(program: Int, target: Int, texture: Int, matrix: FloatArray, uniforms: (Int) -> Unit) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, matrix, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uGrain"), grainNow)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uSeed"), seed)
        uniforms(program)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        // A pass that works from gl_FragCoord alone has its texture coordinate optimised away.
        val coordinate = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, QUAD)
        if (coordinate >= 0) {
            GLES20.glEnableVertexAttribArray(coordinate)
            GLES20.glVertexAttribPointer(coordinate, 2, GLES20.GL_FLOAT, false, 0, QUAD_TEX)
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        if (coordinate >= 0) GLES20.glDisableVertexAttribArray(coordinate)
    }

    /** A texture with its framebuffer, sized on demand. */
    private class Frame {
        var texture = 0
        var framebuffer = 0
        var width = 0
            private set
        var height = 0
            private set

        fun ensure(w: Int, h: Int): Frame {
            if (w == width && h == height && texture != 0) return this
            release()
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            texture = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
            )
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glGenFramebuffers(1, ids, 0)
            framebuffer = ids[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0,
            )
            width = w
            height = h
            return this
        }

        fun release() {
            if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            if (framebuffer != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            texture = 0
            framebuffer = 0
            width = 0
            height = 0
        }
    }

    companion object {
        /**
         * How far the picture runs behind, for the sound to wait as long, as measured from the gaps between
         * frames: the fallback while the video's frame rate is unknown (see [latencyFrames]).
         */
        @Volatile
        var latencyMs = 0
            private set

        /**
         * How many frames the picture runs behind: half a frame while frames blend (the change shows half-way
         * to the next), two with Motion, one with YADIF or BWDIF, five with Film; 0 without such a filter.
         * Turned into time by the video's frame rate (PlaybackMath.pictureLatencyMs), which holds still where
         * the measured gaps do not.
         */
        @Volatile
        var latencyFrames = 0f
            private set

        private const val MAX_LATENCY_MS = 100

        /** The tallest picture taken as standard definition, coloured by BT.601: PAL's 576 lines. */
        private const val SD_HEIGHT = 576

        /** Film runs about five frames behind: a sixth of a second for NTSC. */
        private const val MAX_FILM_LATENCY_MS = 250

        /**
         * How unlike its predecessor a frame may be and still count as Film's repeated one, in FFmpeg's terms
         * (dupthresh, its default 1.1): the luma read back from the graphics chip carries the colour
         * conversion's rounding, so a repeated MPEG-2 frame measured about 1.7 % here, real motion over 25 %.
         */
        private const val FILM_DUPLICATE_PERCENT = 3.0

        /** Where Film's two banks of matched frames start in the frames. */
        private const val FILM_SLOTS = 11

        /** Where luma is packed four values to a texel for reading back, after Film's banks. */
        private const val PACKED_LUMA = FILM_SLOTS + 2 * Telecine.CYCLE

        // Luma for reading back, four values to a texel: texel (u, v) holds columns 4u .. 4u + 3 (in steps of
        // uStep.x) of row v (in steps of uStep.y), rows counted from the top, with the weights the decoder's
        // colour went through (BT.601 up to 576 lines, BT.709 above).
        private const val PACK_LUMA = """
            precision highp float;
            uniform sampler2D uTex;
            uniform vec2 uSize;
            uniform vec2 uStep;
            uniform bool uSd;
            varying vec2 vTex;
            float luma(float x, float y) {
                vec3 c = texture2D(uTex, vec2(x + 0.5, uSize.y - y - 0.5) / uSize).rgb;
                return uSd ? dot(c, vec3(0.299, 0.587, 0.114)) : dot(c, vec3(0.2126, 0.7152, 0.0722));
            }
            void main() {
                float x = floor(gl_FragCoord.x) * 4.0 * uStep.x;
                float y = floor(gl_FragCoord.y) * uStep.y;
                gl_FragColor = vec4(
                    luma(x, y), luma(x + uStep.x, y), luma(x + 2.0 * uStep.x, y), luma(x + 3.0 * uStep.x, y));
            }
        """

        /** The third kept frame for blending and Motion, after the packed luma. */
        private const val KEPT_THIRD = PACKED_LUMA + 1

        /** A picture larger than 1080p brought down, and its first pass across, after the kept frames. */
        private const val REDUCED_ACROSS = KEPT_THIRD + 1
        private const val REDUCED = REDUCED_ACROSS + 1

        /** Motion runs two frames behind: a twelfth of a second at 24 frames a second. */
        private const val MAX_MOTION_LATENCY_MS = 250

        /** Above this width Film judges every other column, to keep the work on the processor in bounds. */
        private const val MAX_FULL_FILM_WIDTH = 1024

        /** The longest gap between two frames that still play on from each other: a 4 fps slide show. */
        private const val MAX_FRAME_STEP_NS = 250_000_000L

        /** How many frames after a start or a seek are read back for the field order: two seconds' worth. */
        private const val FRAMES_TO_JUDGE = 50

        /** Every how many columns the field-order verdict looks at. */
        private const val JUDGED_COLUMN_STEP = 4

        // YADIF and BWDIF, converted from FFmpeg's vf_yadif.c (Michael Niedermayer) and vf_bwdif.c (Thomas
        // Mundt), LGPL-2.1-or-later, by way of the core's Deinterlacer, which is checked against FFmpeg byte
        // for byte; this is the same arithmetic on each colour channel, in whole steps of 0..255. Rows
        // count from the top; uParity picks the field rebuilt (see Deinterlacer.rebuiltParity): with uSecond
        // the earlier one, from the shown frame and the next, otherwise the later one, from the previous
        // frame and the shown one.
        private const val TEMPORAL = """
            precision highp float;
            uniform sampler2D uTex;
            uniform sampler2D uPrev;
            uniform sampler2D uNext;
            uniform vec2 uSize;
            uniform bool uSecond;
            uniform float uParity;
            uniform bool uBwdif;
            varying vec2 vTex;
            vec3 px(sampler2D s, float x, float y) {
                return floor(texture2D(s, vec2(x + 0.5, uSize.y - y - 0.5) / uSize).rgb * 255.0 + 0.5);
            }
            vec3 cur(float x, float y) { return px(uTex, x, y); }
            // The two pictures either side of the rebuilt field's moment.
            vec3 before(float x, float y) { return uSecond ? px(uTex, x, y) : px(uPrev, x, y); }
            vec3 after(float x, float y) { return uSecond ? px(uNext, x, y) : px(uTex, x, y); }
            vec3 score(float x, float y, float m, float p, float j) {
                return abs(cur(x - 1.0 + j, y + m) - cur(x - 1.0 - j, y + p)) +
                    abs(cur(x + j, y + m) - cur(x - j, y + p)) +
                    abs(cur(x + 1.0 + j, y + m) - cur(x + 1.0 - j, y + p));
            }
            vec3 along(float x, float y, float m, float p, float j) {
                return floor((cur(x + j, y + m) + cur(x - j, y + p)) * 0.5);
            }
            vec3 yadif(float x, float y) {
                float m = y > 0.0 ? -1.0 : 1.0;
                float p = y + 1.0 < uSize.y ? 1.0 : -1.0;
                vec3 c = cur(x, y + m);
                vec3 e = cur(x, y + p);
                vec3 b0 = before(x, y);
                vec3 a0 = after(x, y);
                vec3 d = floor((b0 + a0) * 0.5);
                vec3 t0 = abs(b0 - a0);
                vec3 t1 = floor((abs(px(uPrev, x, y + m) - c) + abs(px(uPrev, x, y + p) - e)) * 0.5);
                vec3 t2 = floor((abs(px(uNext, x, y + m) - c) + abs(px(uNext, x, y + p) - e)) * 0.5);
                vec3 diff = max(max(floor(t0 * 0.5), t1), t2);
                vec3 pred = floor((c + e) * 0.5);
                if (x >= 3.0 && x < uSize.x - 3.0) {
                    vec3 best = abs(cur(x - 1.0, y + m) - cur(x - 1.0, y + p)) + abs(c - e) +
                        abs(cur(x + 1.0, y + m) - cur(x + 1.0, y + p)) - 1.0;
                    for (int side = 0; side < 2; side++) {
                        float j = side == 0 ? -1.0 : 1.0;
                        vec3 s = score(x, y, m, p, j);
                        vec3 take = vec3(lessThan(s, best));
                        best = mix(best, s, take);
                        pred = mix(pred, along(x, y, m, p, j), take);
                        vec3 s2 = score(x, y, m, p, 2.0 * j);
                        vec3 take2 = take * vec3(lessThan(s2, best));
                        best = mix(best, s2, take2);
                        pred = mix(pred, along(x, y, m, p, 2.0 * j), take2);
                    }
                }
                if (y != 1.0 && y + 2.0 != uSize.y) {
                    vec3 b = floor((before(x, y + 2.0 * m) + after(x, y + 2.0 * m)) * 0.5);
                    vec3 f = floor((before(x, y + 2.0 * p) + after(x, y + 2.0 * p)) * 0.5);
                    vec3 most = max(max(d - e, d - c), min(b - c, f - e));
                    vec3 least = min(min(d - e, d - c), max(b - c, f - e));
                    diff = max(max(diff, least), -most);
                }
                return clamp(pred, d - diff, d + diff);
            }
            vec3 both(float x, float y) { return before(x, y) + after(x, y); }
            vec3 bwdif(float x, float y) {
                bool edge = y < 4.0 || y + 5.0 > uSize.y;
                float m = (!edge || y > 0.0) ? -1.0 : 1.0;
                float p = (!edge || y + 1.0 < uSize.y) ? 1.0 : -1.0;
                vec3 c = cur(x, y + m);
                vec3 e = cur(x, y + p);
                vec3 b0 = before(x, y);
                vec3 a0 = after(x, y);
                vec3 d = floor((b0 + a0) * 0.5);
                vec3 t0 = abs(b0 - a0);
                vec3 t1 = floor((abs(px(uPrev, x, y + m) - c) + abs(px(uPrev, x, y + p) - e)) * 0.5);
                vec3 t2 = floor((abs(px(uNext, x, y + m) - c) + abs(px(uNext, x, y + p) - e)) * 0.5);
                vec3 still = max(max(floor(t0 * 0.5), t1), t2);
                vec3 diff = still;
                if (!edge || (y >= 2.0 && y + 3.0 <= uSize.y)) {
                    vec3 b = floor(both(x, y - 2.0) * 0.5) - c;
                    vec3 f = floor(both(x, y + 2.0) * 0.5) - e;
                    vec3 most = max(max(d - e, d - c), min(b, f));
                    vec3 least = min(min(d - e, d - c), max(b, f));
                    diff = max(max(diff, least), -most);
                }
                vec3 interpol = floor((c + e) * 0.5);
                if (!edge) {
                    vec3 outer = cur(x, y - 3.0) + cur(x, y + 3.0);
                    vec3 hf = floor((5570.0 * both(x, y) - 3801.0 * (both(x, y - 2.0) + both(x, y + 2.0)) +
                        1016.0 * (both(x, y - 4.0) + both(x, y + 4.0))) / 4.0);
                    vec3 high = floor((hf + 4309.0 * (c + e) - 213.0 * outer) / 8192.0);
                    vec3 spatial = floor((5077.0 * (c + e) - 981.0 * outer) / 8192.0);
                    interpol = mix(spatial, high, vec3(greaterThan(abs(c - e), t0)));
                }
                vec3 result = clamp(clamp(interpol, d - diff, d + diff), 0.0, 255.0);
                return mix(result, d, vec3(equal(still, vec3(0.0))));
            }
            void main() {
                float x = floor(gl_FragCoord.x);
                float y = uSize.y - 1.0 - floor(gl_FragCoord.y);
                vec3 v;
                if (mod(y + uParity, 2.0) < 0.5) {
                    v = cur(x, y);
                } else {
                    v = uBwdif ? bwdif(x, y) : yadif(x, y);
                }
                gl_FragColor = vec4(v / 255.0, 1.0);
            }
        """

        // Film's weave: the matched frame with the rows uRows (0 even, 1 odd, from the top; -1 none) taken
        // from the frame its other field comes from.
        private const val WEAVE = """
            precision mediump float;
            uniform sampler2D uTex;
            uniform sampler2D uOther;
            uniform vec2 uSize;
            uniform float uRows;
            varying vec2 vTex;
            void main() {
                vec2 p = gl_FragCoord.xy / uSize;
                float y = uSize.y - 1.0 - floor(gl_FragCoord.y);
                bool other = abs(mod(y, 2.0) - uRows) < 0.5;
                gl_FragColor = vec4((other ? texture2D(uOther, p) : texture2D(uTex, p)).rgb, 1.0);
            }
        """

        /** The vector texture's zero: half-vectors of -32..32 kept as bytes around it. */
        private const val VECTOR_ZERO = 128

        // Motion-compensated interpolation, written from the core's MotionInterpolation.compensate (FFmpeg's
        // minterpolate, bilateral OBMC), whose search ran on the picture uScale times smaller (uSearch): each
        // pixel takes the up to four 16-pixel blocks (of that grid) whose doubled windows cover it, weighted by
        // uObmc, and mixes the previous frame uAlpha/1024 along the block's vector with the current frame the
        // rest of the way back; offsets are whole pixels of the search's size, uScale times that at full size.
        // Rows count from the top.
        private const val MOTION = """
            precision highp float;
            uniform sampler2D uTex;
            uniform sampler2D uPrevious;
            uniform sampler2D uVectors;
            uniform sampler2D uObmc;
            uniform vec2 uSize;
            uniform vec2 uGrid;
            uniform vec2 uSearch;
            uniform float uScale;
            uniform float uAlpha;
            varying vec2 vTex;
            vec3 at(sampler2D s, vec2 p) {
                p = clamp(p, vec2(0.0), uSize - 1.0);
                return texture2D(s, vec2(p.x + 0.5, uSize.y - p.y - 0.5) / uSize).rgb;
            }
            vec2 truncate(vec2 v) { return sign(v) * floor(abs(v)); }
            void main() {
                vec2 pixel = vec2(floor(gl_FragCoord.x), uSize.y - 1.0 - floor(gl_FragCoord.y));
                vec2 h = floor(pixel / uScale);
                vec2 first = floor((h + 8.0) / 16.0) - 1.0;
                vec3 sum = vec3(0.0);
                float weights = 0.0;
                for (int j = 0; j < 2; j++) {
                    for (int i = 0; i < 2; i++) {
                        vec2 mb = first + vec2(float(i), float(j));
                        if (mb.x < 0.0 || mb.y < 0.0 || mb.x >= uGrid.x || mb.y >= uGrid.y) continue;
                        vec2 start = mb * 16.0 - 8.0;
                        vec2 lo = max(start, vec2(0.0));
                        vec2 hi = min(start + 32.0, uSearch - 1.0);
                        if (h.x < lo.x || h.y < lo.y || h.x >= hi.x || h.y >= hi.y) continue;
                        float w = floor(texture2D(uObmc, (h - start + 0.5) / 32.0).r * 255.0 + 0.5);
                        if (w == 0.0) continue;
                        vec2 v = (floor(texture2D(uVectors, (mb + 0.5) / uGrid).rg * 255.0 + 0.5) - 128.0) * 2.0;
                        vec2 o1 = clamp(truncate(v * uAlpha / 1024.0), -h, uSearch - 1.0 - h);
                        vec2 o2 = clamp(truncate(-v * (1024.0 - uAlpha) / 1024.0), -h, uSearch - 1.0 - h);
                        float w1 = w * (1024.0 - uAlpha);
                        float w2 = w * uAlpha;
                        sum += w1 * at(uPrevious, pixel + uScale * o1) + w2 * at(uTex, pixel + uScale * o2);
                        weights += w1 + w2;
                    }
                }
                vec3 c = weights > 0.0 ? sum / weights : mix(at(uPrevious, pixel), at(uTex, pixel), uAlpha / 1024.0);
                gl_FragColor = vec4(c, 1.0);
            }
        """

        // Frame blending: the current frame over the previous one by the weight.
        private const val BLEND = """
            precision mediump float;
            uniform sampler2D uTex;
            uniform sampler2D uPrevious;
            uniform float uWeight;
            varying vec2 vTex;
            void main() {
                gl_FragColor = vec4(mix(texture2D(uPrevious, vTex).rgb, texture2D(uTex, vTex).rgb, uWeight), 1.0);
            }
        """

        /** RCAS strength on AMD's scale of stops below full sharpness (FsrRcasCon): 0.2 stops, exp2(-0.2). */
        private const val RCAS_SHARPNESS = 0.8705506f


        private val QUAD: FloatBuffer = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        private val QUAD_TEX: FloatBuffer = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        private fun floats(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(values)
                position(0)
            }

        private fun program(vertex: String, fragment: String): Int {
            fun shader(type: Int, source: String): Int = GLES20.glCreateShader(type).also {
                GLES20.glShaderSource(it, source)
                GLES20.glCompileShader(it)
                val ok = IntArray(1)
                GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, ok, 0)
                check(ok[0] != 0) { "Shader: " + GLES20.glGetShaderInfoLog(it) }
            }
            return GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, shader(GLES20.GL_VERTEX_SHADER, vertex))
                GLES20.glAttachShader(it, shader(GLES20.GL_FRAGMENT_SHADER, fragment))
                GLES20.glLinkProgram(it)
            }
        }

        private const val VERTEX = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTex;
            void main() {
                gl_Position = aPosition;
                vTex = (uTexMatrix * aTexCoord).xy;
            }
        """

        /** Pictures from FFmpeg waiting to be drawn, beyond which the oldest is let go. */
        private const val MAX_YUV_QUEUED = 4

        /** A screen refresh until the refreshes are measured (60 Hz), and the range a measured one may have. */
        private const val DEFAULT_VSYNC_NS = 16_666_667L
        private const val MIN_VSYNC_NS = 4_000_000L
        private const val MAX_VSYNC_NS = 50_000_000L

        // YUV planes to RGB, as Media3's VideoDecoderGLSurfaceView does (Apache-2.0): luma less its 16/256
        // foot, chroma about its middle, through the colour space's matrix (BT601, BT709, BT2020 below).
        private const val YUV = """
            precision mediump float;
            uniform sampler2D uTex;
            uniform sampler2D uU;
            uniform sampler2D uV;
            uniform mat3 uColor;
            uniform float uChroma;
            varying vec2 vTex;
            void main() {
                vec2 c = vec2(vTex.x * uChroma, vTex.y);
                vec3 yuv = vec3(
                    texture2D(uTex, vTex).r - 0.0625,
                    texture2D(uU, c).r - 0.5,
                    texture2D(uV, c).r - 0.5
                );
                gl_FragColor = vec4(uColor * yuv, 1.0);
            }
        """

        // Limited-range YUV to RGB, column-major, copied from Media3's VideoDecoderGLSurfaceView.
        private val BT601 = floatArrayOf(1.164f, 1.164f, 1.164f, 0.0f, -0.392f, 2.017f, 1.596f, -0.813f, 0.0f)
        private val BT709 = floatArrayOf(1.164f, 1.164f, 1.164f, 0.0f, -0.213f, 2.112f, 1.793f, -0.533f, 0.0f)
        private val BT2020 = floatArrayOf(1.168f, 1.168f, 1.168f, 0.0f, -0.188f, 2.148f, 1.683f, -0.652f, 0.0f)

        private const val COPY = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTex;
            varying vec2 vTex;
            void main() { gl_FragColor = vec4(texture2D(uTex, vTex).rgb, 1.0); }
        """

        // Deinterlacing; uMode is Deinterlace's ordinal. Blend: every line with half of each neighbour. Bob:
        // the odd lines — the second field — rebuilt from the lines above and below. Auto: the same, but
        // only where a pixel combs — it differs from both neighbours in the same direction (their
        // luma-weighted product is positive beyond noise), the motion artefact of two fields woven
        // together; the first field always stays, or the comb would only be turned over.
        private const val DEINTERLACE = """
            precision highp float;
            uniform sampler2D uTex;
            uniform vec2 uTexel;
            uniform int uMode;
            varying vec2 vTex;
            void main() {
                vec3 c = texture2D(uTex, vTex).rgb;
                vec3 up = texture2D(uTex, vTex + vec2(0.0, uTexel.y)).rgb;
                vec3 down = texture2D(uTex, vTex - vec2(0.0, uTexel.y)).rgb;
                vec3 rebuilt = (up + down) * 0.5;
                if (uMode == 2) {
                    gl_FragColor = vec4((2.0 * c + up + down) * 0.25, 1.0);
                } else {
                    bool second = mod(floor(gl_FragCoord.y), 2.0) >= 1.0;
                    float comb = dot((c - up) * (c - down), vec3(0.2126, 0.7152, 0.0722));
                    bool rebuild = second && (uMode == 3 || comb > 0.003);
                    gl_FragColor = vec4(rebuild ? rebuilt : c, 1.0);
                }
            }
        """

        // Bilateral filter (Tomasi & Manduchi, ICCV 1998): neighbours weigh by distance and by how alike
        // they are, so flat areas and block edges smooth while real edges stay.
        private const val DENOISE = """
            precision mediump float;
            uniform sampler2D uTex;
            uniform vec2 uTexel;
            uniform float uRadius;
            uniform float uSigmaS2;
            uniform float uSigmaR2;
            varying vec2 vTex;
            void main() {
                vec3 center = texture2D(uTex, vTex).rgb;
                vec3 sum = vec3(0.0);
                float weights = 0.0;
                for (int y = -2; y <= 2; y++) {
                    for (int x = -2; x <= 2; x++) {
                        vec2 offset = vec2(float(x), float(y));
                        if (abs(offset.x) > uRadius || abs(offset.y) > uRadius) continue;
                        vec3 neighbour = texture2D(uTex, vTex + offset * uTexel).rgb;
                        vec3 d = neighbour - center;
                        float w = exp(-dot(offset, offset) / uSigmaS2 - dot(d, d) / uSigmaR2);
                        sum += neighbour * w;
                        weights += w;
                    }
                }
                gl_FragColor = vec4(sum / weights, 1.0);
            }
        """

        /** NIS's sharpness, on its own 0..1 scale: the middle, NVIDIA's neutral setting. */
        private const val NIS_SHARPNESS = 0.5f

        /** How NIS's filter taps (-0.62..1.2) map into 0..1 in its table. */
        private const val NIS_OFFSET = 1f
        private const val NIS_SPAN = 2.5f

        // NVIDIA Image Scaling's NVScaler (MIT, see Nis, which it is transcribed from): per output pixel the 6 × 6
        // source luma, four directional edge strengths from the 3 × 3 around the nearest four pixels, a 6-tap
        // scaler blended with directional 6-tap filters that sharpen by luma and hold back ringing; the luma
        // change is added to a bilinear tap of the colour. Rows count from the top, as in NIS. Every value is
        // its own variable (pIJ is the luma of row I, column J) rather than an array: mobile graphics chips keep
        // local arrays in memory, not registers, which made the pass about 18 times slower.
        private const val NIS = """
            precision highp float;
            uniform sampler2D uTex;
            uniform sampler2D uCoef;
            uniform vec2 uInSize;
            uniform vec2 uOutSize;
            uniform float uDetectRatio;
            uniform float uDetectThres;
            uniform float uMinContrastRatio;
            uniform float uRatioNorm;
            uniform float uSharpStartY;
            uniform float uSharpScaleY;
            uniform float uSharpStrengthMin;
            uniform float uSharpStrengthScale;
            uniform float uSharpLimitMin;
            uniform float uSharpLimitScale;
            varying vec2 vTex;
            // Both taps of a phase in one fetch: the scaling tap in x, the sharpening tap in y.
            vec2 coef(float phase, float tap) {
                vec4 t = texture2D(uCoef, vec2((tap + 0.5) / 6.0, (phase + 0.5) / 64.0));
                return (vec2(t.r, t.b) * 255.0 * 256.0 + vec2(t.g, t.a) * 255.0) / 65535.0 * 2.5 - 1.0;
            }
            float lumaOf(vec3 c) { return 0.2126 * c.r + 0.7152 * c.g + 0.0722 * c.b; }
            float lumaAt(float column, float row) {
                return lumaOf(texture2D(uTex, vec2(column + 0.5, uInSize.y - row - 0.5) / uInSize).rgb);
            }
            vec4 edgeMap(float q0, float q1, float q2, float q3, float q4, float q5, float q6, float q7, float q8) {
                float g0 = abs(q0 + q1 + q2 - q6 - q7 - q8);
                float g45 = abs(q3 + q0 + q1 - q7 - q8 - q5);
                float g90 = abs(q0 + q3 + q6 - q2 - q5 - q8);
                float g135 = abs(q3 + q6 + q7 - q1 - q2 - q5);
                float a = max(g0, g90);
                float aMin = min(g0, g90);
                float b = max(g45, g135);
                float bMin = min(g45, g135);
                if (a + b == 0.0) return vec4(0.0);
                float e090 = min(a / (a + b), 1.0);
                float e45135 = 1.0 - e090;
                bool c090 = a > aMin * uDetectRatio && a > uDetectThres && a > bMin;
                bool c45135 = b > bMin * uDetectRatio && b > uDetectThres && b > aMin;
                bool cG0 = a == g0;
                bool cG45 = b == g45;
                float f090 = (c090 && c45135) ? e090 : 1.0;
                float f45135 = (c090 && c45135) ? e45135 : 1.0;
                return vec4(
                    (c090 && cG0) ? f090 : 0.0,
                    (c090 && !cG0) ? f090 : 0.0,
                    (c45135 && cG45) ? f45135 : 0.0,
                    (c45135 && !cG45) ? f45135 : 0.0);
            }
            float evalPoly6(float x0, float x1, float x2, float x3, float x4, float x5, float phase) {
                vec2 c0 = coef(phase, 0.0);
                vec2 c1 = coef(phase, 1.0);
                vec2 c2 = coef(phase, 2.0);
                vec2 c3 = coef(phase, 3.0);
                vec2 c4 = coef(phase, 4.0);
                vec2 c5 = coef(phase, 5.0);
                float y = c0.x * x0 + c1.x * x1 + c2.x * x2 + c3.x * x3 + c4.x * x4 + c5.x * x5;
                float usm = c0.y * x0 + c1.y * x1 + c2.y * x2 + c3.y * x3 + c4.y * x4 + c5.y * x5;
                float yScale = 1.0 - clamp((y - uSharpStartY) * uSharpScaleY, 0.0, 1.0);
                usm *= yScale * uSharpStrengthScale + uSharpStrengthMin;
                float limit = (yScale * uSharpLimitScale + uSharpLimitMin) * y;
                usm = min(limit, max(-limit, usm));
                bool selector = phase <= 32.0;
                float sel = selector ? x0 : x3;
                float aCont = max(max(x1, x2), sel) - min(min(x1, x2), sel);
                sel = selector ? x2 : x5;
                float bCont = max(max(x3, x4), sel) - min(min(x3, x4), sel);
                float ratio = max(aCont, bCont) / (min(aCont, bCont) + 1.0 / 255.0);
                usm *= 1.0 - clamp((ratio - uMinContrastRatio) * uRatioNorm, 0.0, 1.0);
                return y + usm;
            }
            void main() {
                float dstX = floor(gl_FragCoord.x);
                float dstY = uOutSize.y - 1.0 - floor(gl_FragCoord.y);
                vec2 scale = uInSize / uOutSize;
                float srcX = (0.5 + dstX) * scale.x - 0.5;
                float srcY = (0.5 + dstY) * scale.y - 0.5;
                float fx = srcX - floor(srcX);
                float fy = srcY - floor(srcY);
                float phaseX = floor(fx * 64.0);
                float phaseY = floor(fy * 64.0);
                float bx = floor(srcX) - 2.0;
                float by = floor(srcY) - 2.0;
                float p00 = lumaAt(bx + 0.0, by + 0.0);
                float p01 = lumaAt(bx + 1.0, by + 0.0);
                float p02 = lumaAt(bx + 2.0, by + 0.0);
                float p03 = lumaAt(bx + 3.0, by + 0.0);
                float p04 = lumaAt(bx + 4.0, by + 0.0);
                float p05 = lumaAt(bx + 5.0, by + 0.0);
                float p10 = lumaAt(bx + 0.0, by + 1.0);
                float p11 = lumaAt(bx + 1.0, by + 1.0);
                float p12 = lumaAt(bx + 2.0, by + 1.0);
                float p13 = lumaAt(bx + 3.0, by + 1.0);
                float p14 = lumaAt(bx + 4.0, by + 1.0);
                float p15 = lumaAt(bx + 5.0, by + 1.0);
                float p20 = lumaAt(bx + 0.0, by + 2.0);
                float p21 = lumaAt(bx + 1.0, by + 2.0);
                float p22 = lumaAt(bx + 2.0, by + 2.0);
                float p23 = lumaAt(bx + 3.0, by + 2.0);
                float p24 = lumaAt(bx + 4.0, by + 2.0);
                float p25 = lumaAt(bx + 5.0, by + 2.0);
                float p30 = lumaAt(bx + 0.0, by + 3.0);
                float p31 = lumaAt(bx + 1.0, by + 3.0);
                float p32 = lumaAt(bx + 2.0, by + 3.0);
                float p33 = lumaAt(bx + 3.0, by + 3.0);
                float p34 = lumaAt(bx + 4.0, by + 3.0);
                float p35 = lumaAt(bx + 5.0, by + 3.0);
                float p40 = lumaAt(bx + 0.0, by + 4.0);
                float p41 = lumaAt(bx + 1.0, by + 4.0);
                float p42 = lumaAt(bx + 2.0, by + 4.0);
                float p43 = lumaAt(bx + 3.0, by + 4.0);
                float p44 = lumaAt(bx + 4.0, by + 4.0);
                float p45 = lumaAt(bx + 5.0, by + 4.0);
                float p50 = lumaAt(bx + 0.0, by + 5.0);
                float p51 = lumaAt(bx + 1.0, by + 5.0);
                float p52 = lumaAt(bx + 2.0, by + 5.0);
                float p53 = lumaAt(bx + 3.0, by + 5.0);
                float p54 = lumaAt(bx + 4.0, by + 5.0);
                float p55 = lumaAt(bx + 5.0, by + 5.0);
                vec4 e00 = edgeMap(p11, p12, p13, p21, p22, p23, p31, p32, p33);
                vec4 e01 = edgeMap(p12, p13, p14, p22, p23, p24, p32, p33, p34);
                vec4 e10 = edgeMap(p21, p22, p23, p31, p32, p33, p41, p42, p43);
                vec4 e11 = edgeMap(p22, p23, p24, p32, p33, p34, p42, p43, p44);
                vec4 w = mix(mix(e00, e01, fx), mix(e10, e11, fx), fy);
                float baseWeight = 1.0 - w.x - w.y - w.z - w.w;
                // The scaling taps once per pixel, not once per use.
                float cy0 = coef(phaseY, 0.0).x;
                float cx0 = coef(phaseX, 0.0).x;
                float cy1 = coef(phaseY, 1.0).x;
                float cx1 = coef(phaseX, 1.0).x;
                float cy2 = coef(phaseY, 2.0).x;
                float cx2 = coef(phaseX, 2.0).x;
                float cy3 = coef(phaseY, 3.0).x;
                float cx3 = coef(phaseX, 3.0).x;
                float cy4 = coef(phaseY, 4.0).x;
                float cx4 = coef(phaseX, 4.0).x;
                float cy5 = coef(phaseY, 5.0).x;
                float cx5 = coef(phaseX, 5.0).x;
                float v0 = p00 * cy0 + p10 * cy1 + p20 * cy2 + p30 * cy3 + p40 * cy4 + p50 * cy5;
                float v1 = p01 * cy0 + p11 * cy1 + p21 * cy2 + p31 * cy3 + p41 * cy4 + p51 * cy5;
                float v2 = p02 * cy0 + p12 * cy1 + p22 * cy2 + p32 * cy3 + p42 * cy4 + p52 * cy5;
                float v3 = p03 * cy0 + p13 * cy1 + p23 * cy2 + p33 * cy3 + p43 * cy4 + p53 * cy5;
                float v4 = p04 * cy0 + p14 * cy1 + p24 * cy2 + p34 * cy3 + p44 * cy4 + p54 * cy5;
                float v5 = p05 * cy0 + p15 * cy1 + p25 * cy2 + p35 * cy3 + p45 * cy4 + p55 * cy5;
                float normal = v0 * cx0 + v1 * cx1 + v2 * cx2 + v3 * cx3 + v4 * cx4 + v5 * cx5;
                float opY = normal * baseWeight;
                if (w.x > 0.0) {
                    opY += evalPoly6(
                        mix(p02, p03, fx), mix(p12, p13, fx), mix(p22, p23, fx),
                        mix(p32, p33, fx), mix(p42, p43, fx), mix(p52, p53, fx),
                        phaseY) * w.x;
                }
                if (w.y > 0.0) {
                    opY += evalPoly6(
                        mix(p20, p30, fy), mix(p21, p31, fy), mix(p22, p32, fy),
                        mix(p23, p33, fy), mix(p24, p34, fy), mix(p25, p35, fy),
                        phaseX) * w.y;
                }
                if (w.z > 0.0) {
                    float b45 = 0.5 + 0.5 * (fx - fy);
                    float t1 = mix(p21, p12, b45);
                    float t3 = mix(p32, p23, b45);
                    float t5 = mix(p43, p34, b45);
                    b45 -= 0.5;
                    bool up = b45 >= 0.0;
                    float k = abs(b45);
                    float t0 = mix(p11, up ? p02 : p20, k);
                    float t2 = mix(p22, up ? p13 : p31, k);
                    float t4 = mix(p33, up ? p24 : p42, k);
                    float t6 = mix(p44, up ? p35 : p53, k);
                    float p45 = fx + fy;
                    bool shift = p45 >= 1.0;
                    if (shift) p45 -= 1.0;
                    opY += (shift
                        ? evalPoly6(t1, t2, t3, t4, t5, t6, floor(p45 * 64.0))
                        : evalPoly6(t0, t1, t2, t3, t4, t5, floor(p45 * 64.0))) * w.z;
                }
                if (w.w > 0.0) {
                    float b135 = 0.5 * (fx + fy);
                    float t1 = mix(p31, p42, b135);
                    float t3 = mix(p22, p33, b135);
                    float t5 = mix(p13, p24, b135);
                    b135 -= 0.5;
                    bool up = b135 >= 0.0;
                    float k = abs(b135);
                    float t0 = mix(p41, up ? p52 : p30, k);
                    float t2 = mix(p32, up ? p43 : p21, k);
                    float t4 = mix(p23, up ? p34 : p12, k);
                    float t6 = mix(p14, up ? p25 : p03, k);
                    float p135 = 1.0 + (fx - fy);
                    bool shift = p135 >= 1.0;
                    if (shift) p135 -= 1.0;
                    opY += (shift
                        ? evalPoly6(t1, t2, t3, t4, t5, t6, floor(p135 * 64.0))
                        : evalPoly6(t0, t1, t2, t3, t4, t5, floor(p135 * 64.0))) * w.w;
                }
                vec3 c = texture2D(uTex, vec2(srcX + 0.5, uInSize.y - srcY - 0.5) / uInSize).rgb;
                gl_FragColor = vec4(clamp(c + (opY - lumaOf(c)), 0.0, 1.0), 1.0);
            }
        """

        /** How many distances the EWA table holds, and how its weights (-0.25..1) map into 0..1. */
        private const val EWA_TABLE_SIZE = 512
        private const val EWA_OFFSET = 0.25f
        private const val EWA_SPAN = 1.25f

        // EWA Lanczos-sharp (see Ewa): the source pixels in a circle of uRadius around the position weigh by
        // their distance, read from uTable; the result is pulled halfway into the four nearest pixels' range,
        // as the Lanczos pass does.
        private const val EWA = """
            precision highp float;
            uniform sampler2D uTex;
            uniform sampler2D uTable;
            uniform vec2 uInSize;
            uniform float uRadius;
            varying vec2 vTex;
            float weight(float d) {
                if (d >= uRadius) return 0.0;
                float i = floor(d / uRadius * 511.0 + 0.5);
                vec4 t = texture2D(uTable, vec2((i + 0.5) / 512.0, 0.5));
                return (t.r * 255.0 * 256.0 + t.g * 255.0) / 65535.0 * 1.25 - 0.25;
            }
            void main() {
                vec2 pos = vTex * uInSize - 0.5;
                vec2 f = fract(pos);
                vec2 base = floor(pos);
                vec3 sum = vec3(0.0);
                float weights = 0.0;
                vec3 lo = vec3(1.0);
                vec3 hi = vec3(0.0);
                for (int j = -3; j <= 4; j++) {
                    for (int i = -3; i <= 4; i++) {
                        vec2 offset = vec2(float(i), float(j));
                        float w = weight(length(offset - f));
                        if (w == 0.0) continue;
                        vec3 c = texture2D(uTex, (base + offset + 0.5) / uInSize).rgb;
                        sum += c * w;
                        weights += w;
                        if (i >= 0 && i <= 1 && j >= 0 && j <= 1) {
                            lo = min(lo, c);
                            hi = max(hi, c);
                        }
                    }
                }
                vec3 c = sum / weights;
                gl_FragColor = vec4(mix(c, clamp(c, lo, hi), 0.5), 1.0);
            }
        """

        // Lanczos-3 (a = 3), 6 × 6 taps around the position; the result is pulled halfway back into the range
        // of the four nearest pixels, which holds back most of the ringing Lanczos leaves at hard edges.
        // Bringing a picture down (see reduced and Downscaler): uKernel is the Downscaler's ordinal. Below
        // Bicubic one sample, the texture's own filtering doing Nearest or Bilinear; from Bicubic on every pixel
        // within the kernel's reach along uDir, weighed by Keys' cubic (a = -0.5), Lanczos-3, or by how much of
        // it the new pixel covers (Area), the edge pixels repeated beyond the picture.
        private const val DOWNSCALE = """
            precision highp float;
            uniform sampler2D uTex;
            uniform vec2 uInSize;
            uniform vec2 uDir;
            uniform float uScale;
            uniform float uRadius;
            uniform int uKernel;
            varying vec2 vTex;
            const float PI = 3.14159265;
            float sinc(float x) {
                if (abs(x) < 1e-4) return 1.0;
                float p = PI * x;
                return sin(p) / p;
            }
            void main() {
                if (uKernel < 3) {
                    gl_FragColor = texture2D(uTex, vTex);
                    return;
                }
                float length = dot(uInSize, uDir);
                float along = dot(vTex * uInSize, uDir);
                float reach = uRadius * uScale;
                float first = floor(along - reach);
                vec2 across = vTex * (vec2(1.0) - uDir);
                vec4 sum = vec4(0.0);
                float total = 0.0;
                for (int k = 0; k < 64; k++) {
                    float i = first + float(k);
                    if (i > along + reach) break;
                    float x = (i + 0.5 - along) / uScale;
                    float a = abs(x);
                    float w;
                    if (uKernel == 3) {
                        w = a < 1.0
                            ? (1.5 * a - 2.5) * a * a + 1.0
                            : (a < 2.0 ? ((-0.5 * a + 2.5) * a - 4.0) * a + 2.0 : 0.0);
                    } else if (uKernel == 4) {
                        w = a < 3.0 ? sinc(x) * sinc(x / 3.0) : 0.0;
                    } else {
                        w = max(0.0, min(i + 1.0, along + 0.5 * uScale) - max(i, along - 0.5 * uScale));
                    }
                    float at = clamp(i + 0.5, 0.5, length - 0.5) / length;
                    sum += w * texture2D(uTex, across + uDir * at);
                    total += w;
                }
                gl_FragColor = sum / total;
            }
        """

        private const val LANCZOS = """
            precision highp float;
            uniform sampler2D uTex;
            uniform vec2 uInSize;
            uniform float uGrain;
            uniform float uSeed;
            // Film grain: a different random value at every pixel and frame, the same on all three channels;
            // Dave Hoskins' "hash without sine" (MIT), which stays patternless at large coordinates.
            vec3 grain(vec3 color) {
                if (uGrain <= 0.0) return color;
                vec3 p3 = fract(vec3(gl_FragCoord.xy, uSeed).xyz * 0.1031);
                p3 += dot(p3, p3.zyx + 31.32);
                float n = fract((p3.x + p3.y) * p3.z) - 0.5;
                return clamp(color + n * 2.0 * uGrain, 0.0, 1.0);
            }
            varying vec2 vTex;
            float lanczos(float x) {
                x = abs(x);
                if (x < 1e-5) return 1.0;
                if (x >= 3.0) return 0.0;
                float px = 3.14159265 * x;
                return 3.0 * sin(px) * sin(px / 3.0) / (px * px);
            }
            void main() {
                vec2 pos = vTex * uInSize - 0.5;
                vec2 f = fract(pos);
                vec2 base = floor(pos);
                vec3 sum = vec3(0.0);
                float weights = 0.0;
                vec3 lo = vec3(1.0);
                vec3 hi = vec3(0.0);
                for (int j = -2; j <= 3; j++) {
                    float wy = lanczos(float(j) - f.y);
                    for (int i = -2; i <= 3; i++) {
                        vec3 c = texture2D(uTex, (base + vec2(float(i), float(j)) + 0.5) / uInSize).rgb;
                        float w = lanczos(float(i) - f.x) * wy;
                        sum += c * w;
                        weights += w;
                        if (i >= 0 && i <= 1 && j >= 0 && j <= 1) {
                            lo = min(lo, c);
                            hi = max(hi, c);
                        }
                    }
                }
                vec3 c = sum / weights;
                gl_FragColor = vec4(grain(mix(c, clamp(c, lo, hi), 0.5)), 1.0);
            }
        """

        // AMD FidelityFX Super Resolution 1, EASU (edge-adaptive spatial upsampling), from ffx_fsr1.h
        // (MIT, see THIRD-PARTY-NOTICE.FidelityFX-FSR.txt): the 12 texels around 'f' read one by one where
        // AMD gathers them, the approximate reciprocals written exactly. Taps, as in the original:
        //     b c
        //   e f g h
        //   i j k l
        //     n o
        private const val FSR_EASU = """
            precision highp float;
            uniform sampler2D uTex;
            uniform vec2 uInSize;
            varying vec2 vTex;
            vec2 fp;
            vec3 tex(float x, float y) { return texture2D(uTex, (fp + vec2(x, y) + 0.5) / uInSize).rgb; }
            float luma2(vec3 c) { return c.b * 0.5 + (c.r * 0.5 + c.g); }
            void setF(inout vec2 dir, inout float len, float w, float lA, float lB, float lC, float lD, float lE) {
                float dc = lD - lC;
                float cb = lC - lB;
                float lenX = 1.0 / max(max(abs(dc), abs(cb)), 1e-6);
                float dirX = lD - lB;
                dir.x += dirX * w;
                lenX = clamp(abs(dirX) * lenX, 0.0, 1.0);
                lenX *= lenX;
                len += lenX * w;
                float ec = lE - lC;
                float ca = lC - lA;
                float lenY = 1.0 / max(max(abs(ec), abs(ca)), 1e-6);
                float dirY = lE - lA;
                dir.y += dirY * w;
                lenY = clamp(abs(dirY) * lenY, 0.0, 1.0);
                lenY *= lenY;
                len += lenY * w;
            }
            void tap(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len, float lob, float clp, vec3 c) {
                vec2 v = vec2(off.x * dir.x + off.y * dir.y, off.x * -dir.y + off.y * dir.x) * len;
                float d2 = min(dot(v, v), clp);
                float wB = 0.4 * d2 - 1.0;
                float wA = lob * d2 - 1.0;
                wB *= wB;
                wA *= wA;
                wB = 1.5625 * wB - 0.5625;
                float w = wB * wA;
                aC += c * w;
                aW += w;
            }
            void main() {
                vec2 pp = vTex * uInSize - 0.5;
                fp = floor(pp);
                pp -= fp;
                vec3 b = tex(0.0, -1.0); vec3 c = tex(1.0, -1.0);
                vec3 e = tex(-1.0, 0.0); vec3 f = tex(0.0, 0.0); vec3 g = tex(1.0, 0.0); vec3 h = tex(2.0, 0.0);
                vec3 i = tex(-1.0, 1.0); vec3 j = tex(0.0, 1.0); vec3 k = tex(1.0, 1.0); vec3 l = tex(2.0, 1.0);
                vec3 n = tex(0.0, 2.0); vec3 o = tex(1.0, 2.0);
                float bL = luma2(b); float cL = luma2(c); float eL = luma2(e); float fL = luma2(f);
                float gL = luma2(g); float hL = luma2(h); float iL = luma2(i); float jL = luma2(j);
                float kL = luma2(k); float lL = luma2(l); float nL = luma2(n); float oL = luma2(o);
                vec2 dir = vec2(0.0);
                float len = 0.0;
                setF(dir, len, (1.0 - pp.x) * (1.0 - pp.y), bL, eL, fL, gL, jL);
                setF(dir, len, pp.x * (1.0 - pp.y), cL, fL, gL, hL, kL);
                setF(dir, len, (1.0 - pp.x) * pp.y, fL, iL, jL, kL, nL);
                setF(dir, len, pp.x * pp.y, gL, jL, kL, lL, oL);
                vec2 dir2 = dir * dir;
                float dirR = dir2.x + dir2.y;
                bool zro = dirR < 1.0 / 32768.0;
                dirR = zro ? 1.0 : inversesqrt(dirR);
                dir.x = zro ? 1.0 : dir.x;
                dir *= dirR;
                len = len * 0.5;
                len *= len;
                float stretch = (dir.x * dir.x + dir.y * dir.y) / max(abs(dir.x), abs(dir.y));
                vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);
                float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;
                float clp = 1.0 / lob;
                vec3 min4 = min(min(f, g), min(j, k));
                vec3 max4 = max(max(f, g), max(j, k));
                vec3 aC = vec3(0.0);
                float aW = 0.0;
                tap(aC, aW, vec2(0.0, -1.0) - pp, dir, len2, lob, clp, b);
                tap(aC, aW, vec2(1.0, -1.0) - pp, dir, len2, lob, clp, c);
                tap(aC, aW, vec2(-1.0, 1.0) - pp, dir, len2, lob, clp, i);
                tap(aC, aW, vec2(0.0, 1.0) - pp, dir, len2, lob, clp, j);
                tap(aC, aW, vec2(0.0, 0.0) - pp, dir, len2, lob, clp, f);
                tap(aC, aW, vec2(-1.0, 0.0) - pp, dir, len2, lob, clp, e);
                tap(aC, aW, vec2(1.0, 1.0) - pp, dir, len2, lob, clp, k);
                tap(aC, aW, vec2(2.0, 1.0) - pp, dir, len2, lob, clp, l);
                tap(aC, aW, vec2(2.0, 0.0) - pp, dir, len2, lob, clp, h);
                tap(aC, aW, vec2(1.0, 0.0) - pp, dir, len2, lob, clp, g);
                tap(aC, aW, vec2(1.0, 2.0) - pp, dir, len2, lob, clp, o);
                tap(aC, aW, vec2(0.0, 2.0) - pp, dir, len2, lob, clp, n);
                gl_FragColor = vec4(min(max4, max(min4, aC / aW)), 1.0);
            }
        """

        // AMD FidelityFX Super Resolution 1, RCAS (robust contrast-adaptive sharpening), from ffx_fsr1.h
        // (MIT): the strongest negative lobe that cannot clip, limited to FSR_RCAS_LIMIT = 0.25 - 1/16,
        // times the strength; divisions guarded where AMD's reciprocals would meet zero.
        private const val FSR_RCAS = """
            precision highp float;
            uniform sampler2D uTex;
            uniform vec2 uTexel;
            uniform float uSharp;
            uniform float uGrain;
            uniform float uSeed;
            // Film grain: a different random value at every pixel and frame, the same on all three channels;
            // Dave Hoskins' "hash without sine" (MIT), which stays patternless at large coordinates.
            vec3 grain(vec3 color) {
                if (uGrain <= 0.0) return color;
                vec3 p3 = fract(vec3(gl_FragCoord.xy, uSeed).xyz * 0.1031);
                p3 += dot(p3, p3.zyx + 31.32);
                float n = fract((p3.x + p3.y) * p3.z) - 0.5;
                return clamp(color + n * 2.0 * uGrain, 0.0, 1.0);
            }
            varying vec2 vTex;
            void main() {
                vec3 b = texture2D(uTex, vTex - vec2(0.0, uTexel.y)).rgb;
                vec3 d = texture2D(uTex, vTex - vec2(uTexel.x, 0.0)).rgb;
                vec3 e = texture2D(uTex, vTex).rgb;
                vec3 f = texture2D(uTex, vTex + vec2(uTexel.x, 0.0)).rgb;
                vec3 h = texture2D(uTex, vTex + vec2(0.0, uTexel.y)).rgb;
                vec3 mn4 = min(min(b, d), min(f, h));
                vec3 mx4 = max(max(b, d), max(f, h));
                vec3 hitMin = min(mn4, e) / max(4.0 * mx4, vec3(1e-5));
                vec3 hitMax = (1.0 - max(mx4, e)) / min(4.0 * mn4 - 4.0, vec3(-1e-5));
                vec3 lobeRGB = max(-hitMin, hitMax);
                float lobe = max(-0.1875, min(max(lobeRGB.r, max(lobeRGB.g, lobeRGB.b)), 0.0)) * uSharp;
                vec3 c = (lobe * (b + d + h + f) + e) / (4.0 * lobe + 1.0);
                gl_FragColor = vec4(grain(c), 1.0);
            }
        """

        // Unsharp masking on the luma: the picture plus its difference from a blurred copy. Sharpness
        // uses the eight nearest neighbours; clarity a ring six pixels out, which lifts texture and
        // local contrast rather than edges. Rec. 709 luma weights.
        private const val SHARPEN = """
            precision highp float;
            uniform sampler2D uTex;
            uniform vec2 uTexel;
            uniform float uSharpness;
            uniform float uClarity;
            uniform float uGrain;
            uniform float uSeed;
            // Film grain: a different random value at every pixel and frame, the same on all three channels;
            // Dave Hoskins' "hash without sine" (MIT), which stays patternless at large coordinates.
            vec3 grain(vec3 color) {
                if (uGrain <= 0.0) return color;
                vec3 p3 = fract(vec3(gl_FragCoord.xy, uSeed).xyz * 0.1031);
                p3 += dot(p3, p3.zyx + 31.32);
                float n = fract((p3.x + p3.y) * p3.z) - 0.5;
                return clamp(color + n * 2.0 * uGrain, 0.0, 1.0);
            }
            varying vec2 vTex;
            float luma(vec2 at) { return dot(texture2D(uTex, at).rgb, vec3(0.2126, 0.7152, 0.0722)); }
            void main() {
                vec3 color = texture2D(uTex, vTex).rgb;
                float l = dot(color, vec3(0.2126, 0.7152, 0.0722));
                float near = 0.0;
                float far = 0.0;
                for (int i = 0; i < 8; i++) {
                    float angle = float(i) * 0.785398;
                    vec2 direction = vec2(cos(angle), sin(angle));
                    near += luma(vTex + direction * uTexel);
                    far += luma(vTex + direction * uTexel * 6.0);
                }
                float boost = uSharpness * 2.0 * (l - near / 8.0) + uClarity * 0.8 * (l - far / 8.0);
                gl_FragColor = vec4(grain(clamp(color + boost, 0.0, 1.0)), 1.0);
            }
        """
    }
}
