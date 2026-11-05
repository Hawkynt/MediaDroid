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
import com.hawkynt.mediadroid.imaging.Xbrz
import com.hawkynt.mediadroid.shaders.Shaders
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import kotlin.math.ceil

/**
 * The picture filters' own GL stage between the decoder and the screen, for what a colour matrix
 * cannot do: denoising, sharpening, clarity. The decoder — the device's or the bundled FFmpeg's —
 * draws into [inputSurface]; every frame is copied into a texture of the video's size, filtered, and
 * drawn into the [setOutput] surface (the player view's own TextureView), with the frame's own
 * presentation time. The player uses it while a filter is set, and for HDR on a screen without HDR, which it
 * tone-maps into the SDR picture the filters work on (see [hdr]); otherwise the decoder draws straight onto the view.
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
    private val refresh = RefreshPeriod()
    private val yuvTextures = IntArray(3)
    private var yuvProgram = 0

    // Anime4K (see glsl/anime4k): its four convolution passes, the step back up to twice the size, and whether the
    // graphics chip can draw into half-float textures, which the passes' values outside 0..1 need.
    private val anime4kPrograms = IntArray(4)
    private var anime4kDepthToSpace = 0
    private var halfTargets = false

    // ACNet (see glsl/acnet): its eleven convolution passes and the pixel shuffle to twice the size — built when first
    // picked, so the stage starts as quickly as ever; null until then, false where the chip cannot build them.
    private val acnetPrograms = IntArray(11)
    private var acnetShuffle = 0
    private var acnetBuilt: Boolean? = null

    private fun acnetReady(): Boolean = halfTargets && (acnetBuilt ?: runCatching {
        listOf(
            Shaders.ACNET_CONV_1, Shaders.ACNET_CONV_2, Shaders.ACNET_CONV_3, Shaders.ACNET_CONV_4,
            Shaders.ACNET_CONV_5, Shaders.ACNET_CONV_6, Shaders.ACNET_CONV_7, Shaders.ACNET_CONV_8,
            Shaders.ACNET_CONV_9, Shaders.ACNET_CONV_10, Shaders.ACNET_CONV_11,
        ).forEachIndexed { i, fragment -> acnetPrograms[i] = program(Shaders.VIDEO_VERTEX, fragment) }
        acnetShuffle = program(Shaders.VIDEO_VERTEX, Shaders.ACNET_PIXEL_SHUFFLE)
    }.isSuccess.also { acnetBuilt = it })

    // xBRZ (see glsl/xbrz): its three passes, the table of its patterns' shares, and whether the programs built.
    private val xbrzPrograms = IntArray(3)
    private var xbrzTable = 0
    private var xbrzReady = false

    // Real-ESRGAN (see NeuralUpscaler): opened on this thread on the unit the household's self-test chose, the
    // picture read back for its tiles, and the RGB texture at four times the size their output goes into.
    private var neural: NeuralUpscaler? = null
    private var neuralRead: ByteBuffer? = null
    private var neuralTexture = 0
    private var neuralTextureWidth = 0
    private var neuralTextureHeight = 0

    /** Where Real-ESRGAN runs, from the household's self-test; null while neural upscaling is off. */
    @Volatile
    var neuralUnit: NeuralUpscaler.Unit? = null

    /**
     * The neural smooth-motion networks (RIFE, DAIN, XVFI) the household's self-test passed: where each runs and
     * what an in-between took there per tested size — the in-betweens are made at the material's size, or as large
     * as the device keeps up with (see NeuralInterpolation.workingSize). A network not in it is off.
     */
    @Volatile
    var neuralMotion: Map<PictureAdjustment.Interpolation, NeuralInterpolation.Passed> = emptyMap()

    // RIFE (see NeuralInterpolator): opened on this thread, the two frames read back at the working size, and the
    // last two in-betweens (at rifeSize) with the later frame of the pair each belongs to (by its arrival, as keptAt
    // holds it).
    private var rife: NeuralInterpolator? = null
    private val rifeRead = Array(3) { ByteBuffer.allocateDirect(0) }
    private val rifeTextures = IntArray(2)
    private val rifeFor = LongArray(2)
    private var rifeSize = NeuralInterpolation.Size(0, 0)

    /**
     * The upscalers on ncnn (waifu2x, Real-CUGAN) the household's self-test passed, with the unit each runs on; one
     * not in it plays as Standard scaling.
     */
    @Volatile
    var neuralEnlargers: Map<PictureAdjustment.Upscaler, NeuralInterpolation.Passed> = emptyMap()

    // The picture's upscaler on ncnn (see NeuralEnlarger): opened on this thread, the picture read back for it, and
    // the RGB texture at twice the size its output goes into.
    private var enlarger: NeuralEnlarger? = null
    private var enlargerRead: ByteBuffer? = null
    private var enlargerTexture = 0
    private var enlargerTextureWidth = 0
    private var enlargerTextureHeight = 0

    /** The application, to open Real-ESRGAN's model from. */
    @Volatile
    var appContext: android.content.Context? = null
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
    private var adjustProgram = 0
    private var deinterlaceProgram = 0
    private var denoiseProgram = 0
    private var debandProgram = 0

    // Debanding's frame index, which seeds its random numbers with the pixel's position: libplacebo's 8-bit counter.
    private var debandFrame = 0
    private var sharpenProgram = 0
    private var lanczosProgram = 0
    private var gsrProgram = 0
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

    // HDR on a screen without HDR (see hdr): the tone mapping, the measurement of the source's peak, and where GLSL ES
    // 3.00 has GL_EXT_YUV_target the copy that reads the decoder's 10-bit YUV itself (0 without it).
    private var tonemapProgram = 0
    private var peakProgram = 0
    private var y2yProgram = 0
    private val peakDetect = PeakDetect()

    // The peak measurement's read-back on ES 3: two pixel buffers taking turns, each a frame's blocks until the next
    // frame collects them — libplacebo's delayed peak detection; ES 2 reads each frame's back at once.
    private val peakBuffers = IntArray(2)
    private val peakBufferSizes = IntArray(2)
    private val peakPending = IntArray(2)
    private var peakSlot = 0
    private val frames = Array(HDR_BLOCKS + 1) { Frame() }

    // Temporal deinterlacing: the last three decoded frames (frames[8] to [10], taking turns), oldest
    // first, with when each was due; the picture shown is the middle one, one frame behind.
    private val history = intArrayOf(8, 9, 10)
    private val historyAt = LongArray(3)
    private var historyCount = 0
    // The frames' interval, averaged: decoders hand frames over on the screen's refreshes, so one gap
    // alone is uneven (25 fps on 60 Hz arrives 33 and 50 ms apart).
    private var frameIntervalNs = 0L
    private var shownAt = 0L

    /** The refresh an FFmpeg picture is drawn for: its time is the picture's, not the clock's once drawn. */
    private var yuvRefreshNs = 0L
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

    // Blending's steady timeline (see BlendTimeline): every kept frame's steady time, oldest first as keptAt.
    private val blendTimeline = BlendTimeline()
    private val keptSteady = LongArray(3)
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

    /**
     * Whether each picture is waited for until the graphics chip has drawn it — for PictureChecker's dry run, so
     * the thread's busy time holds the chip's work too; in playback the chip works on while the thread goes on.
     */
    @Volatile
    var waitForGpu = false

    /**
     * Hands the picture drawn to the window, waiting for the graphics chip first where [waitForGpu] asks to; a
     * snapshot asked for reads it back first, as it is about to be shown.
     */
    private fun swap() {
        if (waitForGpu) GLES20.glFinish()
        snapshotTaker?.let { take ->
            snapshotTaker = null
            handler.removeCallbacks(snapshotTimeout)
            take(runCatching { readWindow() }.getOrNull())
        }
        EGL14.eglSwapBuffers(display, window)
    }

    /** A picture read back from the window: RGBA rows from the bottom, as OpenGL reads them. */
    class ReadBack(val rgba: ByteArray, val width: Int, val height: Int)

    // A snapshot waiting for the next picture handed to the window, and what ends the wait when none comes.
    private var snapshotTaker: ((ReadBack?) -> Unit)? = null
    private val snapshotTimeout = Runnable { snapshotTaker?.also { snapshotTaker = null }?.invoke(null) }

    /**
     * The picture on screen as the stage draws it — every filter applied, at the size it is shown — handed to
     * [onPicture] on the stage's own thread: read back from the window just before the next picture goes to it, at
     * once while paused (the last picture drawn again). Null without a window or picture, or when none is drawn
     * within [SNAPSHOT_WAIT_MS].
     */
    fun snapshot(onPicture: (ReadBack?) -> Unit) {
        handler.post {
            busy {
                if (window == EGL14.EGL_NO_SURFACE || !hasFrame) {
                    onPicture(null)
                    return@busy
                }
                snapshotTaker?.invoke(null)
                snapshotTaker = onPicture
                handler.postDelayed(snapshotTimeout, SNAPSHOT_WAIT_MS)
                draw(redraw = true)
            }
        }
    }

    // The window's picture as drawn so far, before it is handed to the screen.
    private fun readWindow(): ReadBack {
        val width = query(EGL14.EGL_WIDTH)
        val height = query(EGL14.EGL_HEIGHT)
        val bytes = ByteBuffer.allocateDirect(width * height * 4)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
        val rgba = ByteArray(width * height * 4)
        bytes.get(rgba)
        return ReadBack(rgba, width, height)
    }

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

    /**
     * The filters to run; read on every frame. A change redraws the picture on screen at once, so the colour or a
     * filter set while paused shows without waiting for the next frame.
     */
    @Volatile
    var filters: PictureAdjustment = PictureAdjustment.Neutral
        set(value) {
            if (value == field) return
            field = value
            handler.post { busy { if (hasFrame && window != EGL14.EGL_NO_SURFACE) draw(redraw = true) } }
        }

    /**
     * How the video playing is coded when the stage is to tone-map it — HDR10's PQ or HLG, for a screen without HDR
     * (see [ToneMap]) — or null: SDR, or HDR the screen shows itself. Its peak is measured as it plays ([PeakDetect]),
     * afresh with every change.
     */
    @Volatile
    var hdr: ToneMap.Transfer? = null
        set(value) {
            if (value == field) return
            field = value
            handler.post { peakDetect.reset() }
        }

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

    /**
     * Asks for the next screen refresh, once, while pictures wait; [fromRefresh] when asked from within a refresh
     * callback, so the gap to the next one is a single refresh (see [RefreshPeriod]).
     */
    private fun waitForRefresh(fromRefresh: Boolean = false) {
        if (yuvWaiting || yuvQueue.isEmpty()) return
        yuvWaiting = true
        if (fromRefresh) refresh.askedAgain()
        vsync.postFrameCallback(onYuvVsync)
    }

    /**
     * At a screen refresh ([frameTimeNanos]): the oldest waiting picture is drawn when the pacer has it due by
     * half a refresh from now, so pictures fall on whole refreshes — with Even frames each one on the same
     * number of them — and the next refresh is asked for while more wait.
     */
    private fun pumpYuv(frameTimeNanos: Long) {
        yuvWaiting = false
        refresh.refreshed(frameTimeNanos)
        yuvQueue.forEach {
            if (!it.counted) {
                it.counted = true
                pacer.arrived(it.atNs)
            }
        }
        if (yuvQueue.isEmpty()) return
        if (pacer.dueAt(frameTimeNanos, yuvQueue.size) - frameTimeNanos <= refresh.periodNs / 2) {
            yuvQueue.pollFirst()?.let { arrival ->
                pacer.shown(frameTimeNanos)
                yuvRefreshNs = frameTimeNanos
                takeYuv(arrival.buffer)
            }
        }
        waitForRefresh(fromRefresh = true)
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
            val signalled = when (buffer.colorspace) {
                VideoDecoderOutputBuffer.COLORSPACE_BT601 -> YuvMatrix.BT601
                VideoDecoderOutputBuffer.COLORSPACE_BT709 -> YuvMatrix.BT709
                VideoDecoderOutputBuffer.COLORSPACE_BT2020 -> YuvMatrix.BT2020
                else -> null
            }
            System.arraycopy(YuvMatrix.of(signalled, buffer.height).toRgb(), 0, yuvColor, 0, 9)
            yuvInput = true
            draw(redraw = false)
        } finally {
            buffer.release()
        }
    }

    /**
     * The frame just arrived into [into] — the decoder's surface, or FFmpeg's planes — tone-mapped first where it is HDR
     * for a screen without (see [hdr]): copied as it is coded into a frame of its own (half floats where the chip draws
     * them), its peak measured when [measure] (a new frame, not a redraw), then mapped into [into].
     */
    private fun copyInput(into: Frame, measure: Boolean = true) {
        val transfer = hdr
        if (transfer == null) {
            into.bind()
            copyCoded(tenBit = false)
            return
        }
        val coded = frames[HDR_CODED].ensure(into.width, into.height, half = halfTargets)
        coded.bind()
        copyCoded(tenBit = true)
        if (measure) measurePeak(coded, transfer)
        val pass = ToneMap.pass(transfer, peakDetect.peak)
        into.bind()
        run(tonemapProgram, GLES20.GL_TEXTURE_2D, coded.texture, identity) { p -> toneUniforms(p, pass) }
    }

    /** Hands [pass]'s uniforms (see [ToneMap.Pass.uniforms]) to [program]. */
    private fun toneUniforms(program: Int, pass: ToneMap.Pass) = pass.uniforms { name, v ->
        val at = GLES20.glGetUniformLocation(program, name)
        when (v.size) {
            1 -> GLES20.glUniform1f(at, v[0])
            2 -> GLES20.glUniform2f(at, v[0], v[1])
            3 -> GLES20.glUniform3f(at, v[0], v[1], v[2])
            4 -> GLES20.glUniform4f(at, v[0], v[1], v[2], v[3])
            else -> GLES20.glUniformMatrix3fv(at, 1, false, v, 0)
        }
    }

    /**
     * [coded]'s blocks measured (glsl/video/peak.frag) and taken into [peakDetect]: on ES 3 those of the frame before,
     * read back without waiting while this frame's go into the other buffer; on ES 2 this frame's, at once.
     */
    private fun measurePeak(coded: Frame, transfer: ToneMap.Transfer) {
        val side = PeakDetect.BLOCK
        val blocks = frames[HDR_BLOCKS].ensure((coded.width + side - 1) / side, (coded.height + side - 1) / side)
        blocks.bind()
        run(peakProgram, GLES20.GL_TEXTURE_2D, coded.texture, identity) { p ->
            toneUniforms(p, PeakDetect.pass(transfer))
            GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uSize"), coded.width.toFloat(), coded.height.toFloat())
        }
        val count = blocks.width * blocks.height
        if (!es3) {
            val bytes = ByteBuffer.allocateDirect(count * 4)
            GLES20.glReadPixels(0, 0, blocks.width, blocks.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
            peakDetect.update(PeakDetect.unpack(ByteArray(count * 4).also { bytes.get(it) }, count))
            return
        }
        if (peakBuffers[0] == 0) GLES30.glGenBuffers(2, peakBuffers, 0)
        val slot = peakSlot
        peakSlot = 1 - slot
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, peakBuffers[slot])
        if (peakBufferSizes[slot] < count * 4) {
            GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, count * 4, null, GLES30.GL_STREAM_READ)
            peakBufferSizes[slot] = count * 4
        }
        GLES30.glReadPixels(0, 0, blocks.width, blocks.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, 0)
        peakPending[slot] = count
        val other = 1 - slot
        val waiting = peakPending[other]
        if (waiting > 0) {
            peakPending[other] = 0
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, peakBuffers[other])
            val mapped = GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER, 0, waiting * 4, GLES30.GL_MAP_READ_BIT)
                as? ByteBuffer
            if (mapped != null) {
                val bytes = ByteArray(waiting * 4)
                mapped.get(bytes)
                GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
                peakDetect.update(PeakDetect.unpack(bytes, waiting))
            }
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
    }

    /**
     * The frame just arrived, as it is coded, into the bound framebuffer. [tenBit]: an HDR frame, which the decoder's
     * surface hands over as its YUV itself where the chip can (GL_EXT_YUV_target), read nearest at the frame's size.
     */
    private fun copyCoded(tenBit: Boolean) {
        if (!yuvInput && tenBit && y2yProgram != 0) {
            val oes = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
            GLES20.glBindTexture(oes, oesTexture)
            GLES20.glTexParameteri(oes, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(oes, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            run(y2yProgram, oes, oesTexture, frameMatrix()) { p ->
                GLES20.glUniformMatrix3fv(GLES20.glGetUniformLocation(p, "uColor"), 1, false, BT2020_TEN_BIT, 0)
            }
            GLES20.glBindTexture(oes, oesTexture)
            GLES20.glTexParameteri(oes, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(oes, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            return
        }
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
        handler.post(snapshotTimeout)
        while (true) yuvQueue.pollFirst()?.buffer?.release() ?: break
        onThread {
            // On this thread, the vsync's own: a caller's may have no looper to make one on.
            vsync.removeFrameCallback(onYuvVsync)
            GLES20.glDeleteProgram(yuvProgram)
            anime4kPrograms.forEach { GLES20.glDeleteProgram(it) }
            GLES20.glDeleteProgram(anime4kDepthToSpace)
            acnetPrograms.forEach { GLES20.glDeleteProgram(it) }
            GLES20.glDeleteProgram(acnetShuffle)
            acnetBuilt = null
            xbrzPrograms.forEach { GLES20.glDeleteProgram(it) }
            GLES20.glDeleteTextures(1, intArrayOf(xbrzTable), 0)
            GLES20.glDeleteTextures(3, yuvTextures, 0)
            releaseWindow()
            GLES20.glDeleteProgram(copyProgram)
            GLES20.glDeleteProgram(adjustProgram)
            rife?.close()
            rife = null
            if (rifeTextures[0] != 0) GLES20.glDeleteTextures(2, rifeTextures, 0)
            rifeTextures.fill(0)
            rifeSize = NeuralInterpolation.Size(0, 0)
            neural?.close()
            neural = null
            if (neuralTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(neuralTexture), 0)
            enlarger?.close()
            enlarger = null
            if (enlargerTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(enlargerTexture), 0)
            GLES20.glDeleteProgram(deinterlaceProgram)
            GLES20.glDeleteProgram(denoiseProgram)
            GLES20.glDeleteProgram(debandProgram)
            GLES20.glDeleteProgram(tonemapProgram)
            GLES20.glDeleteProgram(peakProgram)
            if (y2yProgram != 0) GLES20.glDeleteProgram(y2yProgram)
            if (peakBuffers[0] != 0) GLES20.glDeleteBuffers(2, peakBuffers, 0)
            GLES20.glDeleteProgram(sharpenProgram)
            GLES20.glDeleteProgram(lanczosProgram)
            GLES20.glDeleteProgram(gsrProgram)
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
        yuvProgram = program(Shaders.VIDEO_VERTEX, Shaders.VIDEO_YUV)
        val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()
        halfTargets = es3 &&
            ("GL_EXT_color_buffer_half_float" in extensions || "GL_EXT_color_buffer_float" in extensions)
        // A chip that cannot build Anime4K's programs gets FSR in its place rather than no picture at all.
        halfTargets = halfTargets && runCatching {
            listOf(Shaders.ANIME4K_CONV_1, Shaders.ANIME4K_CONV_2, Shaders.ANIME4K_CONV_3, Shaders.ANIME4K_CONV_4)
                .forEachIndexed { i, fragment -> anime4kPrograms[i] = program(Shaders.VIDEO_VERTEX, fragment) }
            anime4kDepthToSpace = program(Shaders.VIDEO_VERTEX, Shaders.ANIME4K_DEPTH_TO_SPACE)
        }.isSuccess
        xbrzReady = runCatching {
            listOf(Shaders.XBRZ_CORNERS, Shaders.XBRZ_DECISIONS, Shaders.XBRZ_SCALE)
                .forEachIndexed { i, fragment -> xbrzPrograms[i] = program(Shaders.VIDEO_VERTEX, fragment) }
            xbrzTable = xbrzTable()
        }.isSuccess
        inputSurface = Surface(surfaceTexture)

        copyProgram = program(Shaders.VIDEO_VERTEX, Shaders.VIDEO_COPY)
        adjustProgram = program(Shaders.VIDEO_VERTEX, Shaders.VIDEO_ADJUST)
        deinterlaceProgram = program(Shaders.VIDEO_VERTEX, Shaders.VIDEO_DEINTERLACE)
        denoiseProgram = program(Shaders.VIDEO_VERTEX, Shaders.VIDEO_DENOISE)
        sharpenProgram = program(Shaders.VIDEO_VERTEX, Shaders.VIDEO_SHARPEN)
        lanczosProgram = program(Shaders.VIDEO_VERTEX, Shaders.VIDEO_LANCZOS)
        gsrProgram = program(Shaders.VIDEO_VERTEX, Shaders.VIDEO_SGSR)
        downscaleProgram = program(Shaders.VIDEO_VERTEX, Shaders.VIDEO_DOWNSCALE)
        ewaProgram = program(Shaders.VIDEO_VERTEX, Shaders.VIDEO_EWA)
        ewaTable = ewaTable()
        nisProgram = program(Shaders.VIDEO_VERTEX, Shaders.VIDEO_NIS)
        nisTable = nisTable()
        // FSR reads its floats' bits for AMD's approximate reciprocals where GLSL ES 3.00 can (about half the cost on
        // a Mali G77 at 576p); the GLSL ES 1.00 passes compute the same bits from the floats' fields elsewhere.
        val fsrEs3 = es3 && runCatching {
            easuProgram = linked(program(Shaders.VIDEO_VERTEX_ES3, Shaders.VIDEO_FSR_EASU_ES3))
            rcasProgram = linked(program(Shaders.VIDEO_VERTEX_ES3, Shaders.VIDEO_FSR_RCAS_ES3))
        }.isSuccess
        if (!fsrEs3) {
            easuProgram = program(Shaders.VIDEO_VERTEX, Shaders.VIDEO_FSR_EASU)
            rcasProgram = program(Shaders.VIDEO_VERTEX, Shaders.VIDEO_FSR_RCAS)
        }
        // Debanding draws libplacebo's own random numbers where GLSL ES 3.00 has unsigned integers for them; the
        // GLSL ES 1.00 pass draws others (see glsl/video/deband.frag).
        val debandEs3 = es3 && runCatching {
            debandProgram = linked(program(Shaders.VIDEO_VERTEX_ES3, Shaders.VIDEO_DEBAND_ES3))
        }.isSuccess
        if (!debandEs3) debandProgram = program(Shaders.VIDEO_VERTEX, Shaders.VIDEO_DEBAND)
        tonemapProgram = program(Shaders.VIDEO_VERTEX, Shaders.VIDEO_TONEMAP)
        peakProgram = program(Shaders.VIDEO_VERTEX, Shaders.VIDEO_PEAK)
        // The decoder's 10-bit YUV read as it is, where the chip offers it; else samplerExternalOES's RGB.
        y2yProgram = if (es3 && "GL_EXT_YUV_target" in extensions) {
            runCatching { linked(program(Shaders.VIDEO_VERTEX_ES3, Shaders.VIDEO_COPY_Y2Y_ES3)) }.getOrDefault(0)
        } else {
            0
        }
        blendProgram = program(Shaders.VIDEO_VERTEX, Shaders.VIDEO_BLEND)
        motionProgram = program(Shaders.VIDEO_VERTEX, Shaders.VIDEO_MOTION)
        packProgram = program(Shaders.VIDEO_VERTEX, Shaders.VIDEO_PACK_LUMA)
        for (k in 0 until 2) vectorTextures[k] = byteTexture(1, 1, ByteArray(4))
        obmcTexture = byteTexture(
            32, 32, ByteArray(32 * 32 * 4) { if (it % 4 == 0) MotionInterpolation.obmcWeight(it / 4).toByte() else 0 },
        )
        temporalProgram = program(Shaders.VIDEO_VERTEX, Shaders.VIDEO_TEMPORAL)
        weaveProgram = program(Shaders.VIDEO_VERTEX, Shaders.VIDEO_WEAVE)
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
                frameTimestamp = yuvRefreshNs
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
        swap()
        // On screen at the refresh it is meant for; the clock once drawing is done wanders by milliseconds.
        if (!redraw) statistics?.pictureShown(shownAt)
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
        swap()
        statistics?.pictureShown(shownAt + frameIntervalNs / 2)
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
        copyInput(copied, measure = fresh)
        var source = copied
        if (picture.deinterlace != PictureAdjustment.Deinterlace.OFF) {
            val woven = frames[2].ensure(width, height)
            woven.bind()
            run(deinterlaceProgram, GLES20.GL_TEXTURE_2D, copied.texture, identity) { p ->
                GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uTexel"), 1f / width, 1f / height)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uMode"), picture.deinterlace.ordinal)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uHeight"), height.toFloat())
                // The first field stays, the second is rebuilt: the field order decides which rows those are.
                GLES20.glUniform1f(
                    GLES20.glGetUniformLocation(p, "uParity"),
                    Deinterlacer.rebuiltParity(false, fieldOrder.topFieldFirst).toFloat(),
                )
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
    ) = exact(prev.texture, cur.texture, next.texture) {
        target.bind()
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
            slot.bind()
            exact(cur.texture, other.texture) {
                GLES20.glUseProgram(weaveProgram)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, other.texture)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(weaveProgram, "uOther"), 1)
                run(weaveProgram, GLES20.GL_TEXTURE_2D, cur.texture, identity) { p ->
                    GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uSize"), width.toFloat(), height.toFloat())
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uRows"), rows.toFloat())
                }
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
            // uptimeMillis and nanoTime run on the same monotonic clock: the slot's time is the picture's.
            val atNs = start * 1_000_000L + j * stepNs
            handler.postAtTime({ busy { showFilm(base + position, atNs) } }, filmToken, start + j * stepNs / 1_000_000L)
        }
        latencyMs = (verdict.intervalNs * Telecine.CYCLE / 1_000_000L).toInt().coerceIn(0, MAX_FILM_LATENCY_MS)
        latencyFrames = Telecine.CYCLE.toFloat()
        filmBank = 1 - filmBank
        filmPosition = 0
    }

    /** One of Film's kept frames onto the screen, through the rest of the filters, for [atNs]; null redraws it. */
    private fun showFilm(slot: Int, atNs: Long? = null) {
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
        swap()
        atNs?.let { statistics?.pictureShown(it) }
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
        copyInput(newest)
        newest.bind()
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
        target.bind()
        exact(from.texture) {
            run(packProgram, GLES20.GL_TEXTURE_2D, from.texture, identity) { p ->
                GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uSize"), width.toFloat(), height.toFloat())
                GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uStep"), stepX.toFloat(), stepY.toFloat())
                GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uSd"), if (height <= YuvMatrix.SD_HEIGHT) 1 else 0)
            }
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
        target.bind()
        run(downscaleProgram, GLES20.GL_TEXTURE_2D, from.texture, identity) { p ->
            GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uInSize"), from.width.toFloat(), from.height.toFloat())
            GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uDir"), dirX, dirY)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uScale"), scale)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uRadius"), scaler.radius)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uKernel"), scaler.ordinal)
        }
    }

    /**
     * Step 3: debanding, then the denoise filter, as far as [picture] asks for them, over [from]. Debanding comes first,
     * as libplacebo runs it first on the decoded picture: the steps it looks for are the source's own.
     */
    private fun denoised(picture: PictureAdjustment, from: Frame, width: Int, height: Int): Frame {
        var source = from
        picture.debandParams()?.let { deband ->
            val debanded = frames[DEBANDED].ensure(width, height)
            debanded.bind()
            debandFrame = (debandFrame + 1) and 0xFF
            run(debandProgram, GLES20.GL_TEXTURE_2D, source.texture, identity) { p ->
                GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uSize"), width.toFloat(), height.toFloat())
                // The frames hold the picture's top row where v is 1; libplacebo counts rows from the top.
                GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uRows"), height.toFloat(), -1f)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uFrame"), debandFrame.toFloat())
                GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uIterations"), deband.iterations)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uThreshold"), deband.threshold / 1000f)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uRadius"), deband.radius)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uDebandGrain"), deband.grain / 1000f)
            }
            source = debanded
        }
        val kernel = picture.denoiseKernel()
        if (kernel != null) {
            val denoised = frames[1].ensure(width, height)
            denoised.bind()
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
        keptSteady[0] = keptSteady[1]
        keptSteady[1] = keptSteady[2]
        val target = frames[keptRing[2]].ensure(keptWidth, keptHeight)
        target.bind()
        run(sharpenProgram, GLES20.GL_TEXTURE_2D, source.texture, identity) { p ->
            GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uTexel"), 1f / keptWidth, 1f / keptHeight)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uSharpness"), picture.sharpness)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uClarity"), picture.clarity)
        }
        keptAt[2] = System.nanoTime()
        keptSteady[2] = blendTimeline.arrived(keptAt[2])
        kept = minOf(kept + 1, 3)
        if (picture.interpolation.isNeural && kept >= 2) {
            rifeBetween(
                picture.interpolation, frames[keptRing[1]], frames[keptRing[2]], keptWidth, keptHeight,
                before = frames[keptRing[0]].takeIf { kept >= 3 },
            )
        }
        val motion = picture.interpolation.isMotionCompensated
        if (motion) searchMotion(keptWidth, keptHeight) else motionLumas.fill(null)
        // Blending runs half a frame behind, Motion two frames; the sound is told to wait as long.
        if (kept >= 2) {
            val intervalMs = (keptAt[2] - keptAt[1]) / 1_000_000L
            latencyMs = if (motion) {
                (intervalMs * 2).toInt().coerceIn(0, MAX_MOTION_LATENCY_MS)
            } else {
                // A frame and a quarter behind on the timeline, the mix centred half a frame before that.
                val behindNs = blendTimeline.delayNs - blendTimeline.intervalNs / 2
                (behindNs / 1_000_000L).toInt().coerceIn(0, MAX_LATENCY_MS)
            } + deinterlaceLatencyMs
            latencyFrames = (if (motion) 2f else 0.75f) + deinterlaceFrames
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
        // The pair shown: with Motion the two before the newest, by the newest's arrival; when blending, the two
        // around the point on the steady timeline the picture has reached (see BlendTimeline).
        val oldestKept = 3 - kept
        val (earlier, blendPhase) = if (!picture.interpolation.isMotionCompensated && kept >= 2) {
            blendTimeline.position(frameTimeNanos, keptSteady.copyOfRange(oldestKept, 3))
        } else {
            0 to 1f
        }
        val later = when {
            motion -> 1
            picture.interpolation.isMotionCompensated || kept < 2 -> 2
            else -> oldestKept + earlier + 1
        }
        val current = keptRing[later]
        val previous = keptRing[if (kept >= 2) later - 1 else later]
        val width = frames[current].width
        val height = frames[current].height
        val interval = (keptAt[2] - keptAt[1]).takeIf { kept >= 2 && it > 0 } ?: 1L
        val phase = if (picture.interpolation.isMotionCompensated) {
            (frameTimeNanos - keptAt[2]).toFloat() / interval
        } else {
            blendPhase
        }
        // Blending goes on while a later frame is to come into the mix; it waits only past the newest, whole.
        val waiting = !picture.interpolation.isMotionCompensated && later == 2 && blendPhase >= 1f
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
        // RIFE: through the in-between at the half (see NeuralInterpolation.mix), where it is in for this pair.
        var previousTexture = frames[previous].texture
        var currentTexture = frames[current].texture
        val rifeSlot = if (picture.interpolation.isNeural && kept >= 2) rifeFor.indexOf(keptAt[later]) else -1
        if (rifeSlot >= 0) {
            val mix = NeuralInterpolation.mix(phase)
            if (mix.towardsMiddle) currentTexture = rifeTextures[rifeSlot] else previousTexture = rifeTextures[rifeSlot]
            weight = mix.weight
        }
        val mixed = frames[7].ensure(width, height)
        mixed.bind()
        val program = if (alongMotion) motionProgram else blendProgram
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, previousTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uPrevious"), 1)
        if (alongMotion) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, vectorTextures[slot])
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uVectors"), 2)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, obmcTexture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uObmc"), 3)
        }
        run(program, GLES20.GL_TEXTURE_2D, currentTexture, identity) { p ->
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
        swap()
        // A new picture when the pair or the mix changed.
        val blendKey = keptAt[later] * 4099 + (weight * 1024).toLong()
        if (blendKey != lastBlend) {
            lastBlend = blendKey
            statistics?.pictureShown(frameTimeNanos)
        }
        if (picture.interpolation.isMotionCompensated) {
            if (weight < 1f) startBlending()
        } else if (!waiting) {
            startBlending()
        }
    }

    /** Steps 4 and 5: sharpness and clarity, and the picture onto the screen, enlarged as chosen. */
    private fun output(from: Frame, width: Int, height: Int, picture: PictureAdjustment) {
        var source = from
        grainNow = 0f
        grainToShow = filters.grain
        val outWidth = query(EGL14.EGL_WIDTH)
        val outHeight = query(EGL14.EGL_HEIGHT)
        // The colour correction is the last pass: the rest is drawn at the screen's size into a frame of its own.
        val adjusting = !picture.isColorNeutral
        val upscaling = picture.upscales && outWidth > width
        val sharpen: (Int) -> Unit = { p ->
            GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uTexel"), 1f / width, 1f / height)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uSharpness"), picture.sharpness)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uClarity"), picture.clarity)
        }
        if (!upscaling) {
            // 4: sharpness and clarity, drawn onto the screen at its size by its own bilinear scaling.
            toScreen(outWidth, outHeight, adjusting)
            run(sharpenProgram, GLES20.GL_TEXTURE_2D, source.texture, identity, sharpen)
        } else {
            // 4: sharpness and clarity at the picture's own size, then 5: enlarged onto the screen.
            if (picture.sharpness > 0f || picture.clarity > 0f) {
                val sharpened = frames[3].ensure(width, height)
                sharpened.bind()
                run(sharpenProgram, GLES20.GL_TEXTURE_2D, source.texture, identity, sharpen)
                source = sharpened
            }
            val inSize: (Int) -> Unit = { p ->
                GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uInSize"), width.toFloat(), height.toFloat())
            }
            if (picture.upscaler == PictureAdjustment.Upscaler.LANCZOS) {
                toScreen(outWidth, outHeight, adjusting)
                run(lanczosProgram, GLES20.GL_TEXTURE_2D, source.texture, identity) { p ->
                    inSize(p)
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uAntiring"), ANTIRING)
                }
            } else if (picture.upscaler == PictureAdjustment.Upscaler.GSR) {
                // Snapdragon GSR: one pass straight to the screen's size, any ratio.
                toScreen(outWidth, outHeight, adjusting)
                run(gsrProgram, GLES20.GL_TEXTURE_2D, source.texture, identity, inSize)
            } else if (picture.upscaler == PictureAdjustment.Upscaler.NIS) {
                // NVIDIA Image Scaling to at most twice the picture's size, as NIS allows, and the screen's own
                // bilinear scaling for whatever is left.
                val midWidth = minOf(outWidth, width * 2)
                val midHeight = minOf(outHeight, height * 2)
                val enlarged = frames[4].ensure(midWidth, midHeight)
                enlarged.bind()
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
                    // Divided here, as NVIDIA divides: exactly (see glsl/video/nis.frag).
                    GLES20.glUniform2f(
                        GLES20.glGetUniformLocation(p, "uScale"),
                        width.toFloat() / midWidth, height.toFloat() / midHeight,
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
                toScreen(outWidth, outHeight, adjusting)
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
                enlarged.bind()
                GLES20.glUseProgram(ewaProgram)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ewaTable)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(ewaProgram, "uTable"), 1)
                run(ewaProgram, GLES20.GL_TEXTURE_2D, source.texture, identity) { p ->
                    inSize(p)
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uAntiring"), ANTIRING)
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uRadius"), Ewa.RADIUS.toFloat())
                }
                toScreen(outWidth, outHeight, adjusting)
                run(sharpenProgram, GLES20.GL_TEXTURE_2D, enlarged.texture, identity) { p ->
                    GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uTexel"), 1f / midWidth, 1f / midHeight)
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uSharpness"), 0f)
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uClarity"), 0f)
                }
            } else if (picture.upscaler == PictureAdjustment.Upscaler.ANIME4K && halfTargets) {
                // Anime4K: four convolutions at the picture's size into half-float textures taking turns, then
                // twice the size — the picture's bilinear doubling plus the detail the network adds — and the
                // screen's own scaling for whatever is left.
                var input = source
                anime4kPrograms.forEachIndexed { i, program ->
                    val target = frames[if (i % 2 == 0) ANIME4K_A else ANIME4K_B].ensure(width, height, half = true)
                    target.bind()
                    run(program, GLES20.GL_TEXTURE_2D, input.texture, identity) { p ->
                        GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uTexel"), 1f / width, 1f / height)
                    }
                    input = target
                }
                val enlarged = frames[4].ensure(width * 2, height * 2)
                enlarged.bind()
                GLES20.glUseProgram(anime4kDepthToSpace)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, input.texture)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(anime4kDepthToSpace, "uLast"), 1)
                run(anime4kDepthToSpace, GLES20.GL_TEXTURE_2D, source.texture, identity) { p ->
                    GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uLastSize"), width.toFloat(), height.toFloat())
                }
                toScreen(outWidth, outHeight, adjusting)
                run(sharpenProgram, GLES20.GL_TEXTURE_2D, enlarged.texture, identity) { p ->
                    GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uTexel"), 0.5f / width, 0.5f / height)
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uSharpness"), 0f)
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uClarity"), 0f)
                }
            } else if (picture.upscaler == PictureAdjustment.Upscaler.ACNET && acnetReady()) {
                // ACNet: eleven convolutions at the picture's size over eight channels — a layer is two half-float
                // textures, written one pass each, the layers taking turns between two pairs — then twice the size:
                // the network's luma put into the picture's bilinear doubling, and the screen's own scaling for
                // whatever is left.
                val pairs = arrayOf(
                    arrayOf(frames[ANIME4K_A], frames[ANIME4K_B]),
                    arrayOf(frames[ACNET_C], frames[ACNET_D]),
                )
                acnetPrograms.forEachIndexed { i, program ->
                    // The head (0) into the first pair, each body block (1 to 4) from one pair into the other, the
                    // last convolution (5) into the second; every layer's two halves read the whole layer before.
                    val layer = if (i < 2) 0 else (i - 2) / 2 + 1
                    val into = pairs[layer % 2]
                    val from = pairs[1 - layer % 2]
                    into[i % 2].ensure(width, height, half = true).bind()
                    if (layer > 0) {
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, from[1].texture)
                    }
                    if (i == acnetPrograms.lastIndex) {
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, source.texture)
                    }
                    val input = if (layer == 0) source.texture else from[0].texture
                    run(program, GLES20.GL_TEXTURE_2D, input, identity) { p ->
                        GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uTexel"), 1f / width, 1f / height)
                        GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uTex1"), 1)
                        GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uPicture"), 2)
                    }
                }
                val enlarged = frames[4].ensure(width * 2, height * 2)
                enlarged.bind()
                GLES20.glUseProgram(acnetShuffle)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, pairs[1][0].texture)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(acnetShuffle, "uLast"), 1)
                run(acnetShuffle, GLES20.GL_TEXTURE_2D, source.texture, identity) { p ->
                    GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uLastSize"), width.toFloat(), height.toFloat())
                }
                toScreen(outWidth, outHeight, adjusting)
                run(sharpenProgram, GLES20.GL_TEXTURE_2D, enlarged.texture, identity) { p ->
                    GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uTexel"), 0.5f / width, 0.5f / height)
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uSharpness"), 0f)
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uClarity"), 0f)
                }
            } else if (picture.upscaler == PictureAdjustment.Upscaler.XBRZ && xbrzReady) {
                // xBRZ by the whole factor that reaches the screen's size, 2 to 6, the screen's own scaling then
                // bringing it the rest of the way: the corners where pixels meet, what each pixel draws in its
                // corners, then the enlarged picture.
                val factor = ceil(minOf(outWidth.toFloat() / width, outHeight.toFloat() / height)).toInt()
                    .coerceIn(Xbrz.FACTORS)
                val size: (Int) -> Unit = { p ->
                    GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uSize"), width.toFloat(), height.toFloat())
                }
                val corners = frames[XBRZ_CORNERS].ensure(width + 1, height + 1)
                val decisions = frames[XBRZ_DECISIONS].ensure(width, height)
                val enlarged = frames[4].ensure(width * factor, height * factor)
                exact(source.texture, corners.texture, decisions.texture) {
                    corners.bind()
                    run(xbrzPrograms[0], GLES20.GL_TEXTURE_2D, source.texture, identity, size)
                    decisions.bind()
                    GLES20.glUseProgram(xbrzPrograms[1])
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, corners.texture)
                    GLES20.glUniform1i(GLES20.glGetUniformLocation(xbrzPrograms[1], "uCorners"), 1)
                    run(xbrzPrograms[1], GLES20.GL_TEXTURE_2D, source.texture, identity, size)
                    enlarged.bind()
                    GLES20.glUseProgram(xbrzPrograms[2])
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, decisions.texture)
                    GLES20.glUniform1i(GLES20.glGetUniformLocation(xbrzPrograms[2], "uDecisions"), 1)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, xbrzTable)
                    GLES20.glUniform1i(GLES20.glGetUniformLocation(xbrzPrograms[2], "uTable"), 2)
                    run(xbrzPrograms[2], GLES20.GL_TEXTURE_2D, source.texture, identity) { p ->
                        size(p)
                        GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uFactor"), factor.toFloat())
                    }
                }
                toScreen(outWidth, outHeight, adjusting)
                run(sharpenProgram, GLES20.GL_TEXTURE_2D, enlarged.texture, identity) { p ->
                    val texelX = 1f / (width * factor)
                    val texelY = 1f / (height * factor)
                    GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uTexel"), texelX, texelY)
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uSharpness"), 0f)
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uClarity"), 0f)
                }
            } else if (picture.upscaler.isEnlarger && enlargerNet(picture.upscaler) != null) {
                // waifu2x, Real-CUGAN or RealSR: two or four times the picture's size (see enlargerPass), the
                // screen's own scaling for whatever is left.
                val enlarged = enlargerPass(source, width, height)
                toScreen(outWidth, outHeight, adjusting)
                run(sharpenProgram, GLES20.GL_TEXTURE_2D, enlarged, identity) { p ->
                    val scale = NeuralEnlarging.scale(picture.upscaler)
                    GLES20.glUniform2f(
                        GLES20.glGetUniformLocation(p, "uTexel"), 1f / (width * scale), 1f / (height * scale),
                    )
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uSharpness"), 0f)
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uClarity"), 0f)
                }
            } else if (picture.upscaler == PictureAdjustment.Upscaler.NEURAL && es3 && neuralNet() != null) {
                // Real-ESRGAN: four times the picture's size, tile by tile (see neuralPass), and the screen's own
                // scaling for whatever is left.
                val enlarged = neuralPass(source, width, height)
                toScreen(outWidth, outHeight, adjusting)
                run(sharpenProgram, GLES20.GL_TEXTURE_2D, enlarged, identity) { p ->
                    val scale = NeuralUpscaling.SCALE
                    GLES20.glUniform2f(
                        GLES20.glGetUniformLocation(p, "uTexel"), 1f / (width * scale), 1f / (height * scale),
                    )
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uSharpness"), 0f)
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uClarity"), 0f)
                }
            } else {
                // FSR 1: edge-adaptive upscaling to the screen's size, then contrast-adaptive sharpening on it.
                val enlarged = frames[4].ensure(outWidth, outHeight)
                enlarged.bind()
                run(easuProgram, GLES20.GL_TEXTURE_2D, source.texture, identity, inSize)
                toScreen(outWidth, outHeight, adjusting)
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
        if (adjusting) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, outWidth, outHeight)
            val u = picture.shaderUniforms()
            run(adjustProgram, GLES20.GL_TEXTURE_2D, frames[ADJUSTED].texture, identity) { p ->
                GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uBrightness"), u.brightness)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uContrast"), u.contrast)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uSaturation"), u.saturation)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uInverseGamma"), u.inverseGamma)
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
     * The picture's neural network on the unit its self-test chose, opened here on first use or when either changes;
     * null while it is off.
     */
    private fun rifeNet(network: PictureAdjustment.Interpolation): NeuralInterpolator? {
        val unit = neuralMotion[network]?.let { runCatching { NeuralUpscaler.Unit.valueOf(it.unit) }.getOrNull() }
        if (unit == null) {
            rife?.close()
            rife = null
            return null
        }
        if (rife?.unit != unit || rife?.network != network) {
            rife?.close()
            rife = appContext?.let { NeuralInterpolator.open(it, network, unit) }
        }
        return rife
    }

    /**
     * [network]'s in-between of [earlier] and [later] ([frameWidth] × [frameHeight]): both brought to the working size
     * and read back, the network run, and the result uploaded into the in-between texture not holding the pair
     * before, marked with the later frame. Nothing where the device keeps up with no size for this material.
     */
    private fun rifeBetween(
        network: PictureAdjustment.Interpolation,
        earlier: Frame,
        later: Frame,
        frameWidth: Int,
        frameHeight: Int,
        before: Frame? = null,
    ) {
        val net = rifeNet(network) ?: return
        val times = neuralMotion[net.network]?.msByHeight ?: return
        val size = NeuralInterpolation.workingSize(frameWidth, frameHeight, times) ?: return
        val w = size.width
        val h = size.height
        val target = frames[RIFE_INPUT].ensure(w, h)
        // FLAVR looks at the frame before the pair too; the one after it would cost another frame's wait, so the
        // later frame stands in for it.
        val looked = if (network == PictureAdjustment.Interpolation.FLAVR && before != null) {
            listOf(earlier, later, before)
        } else {
            listOf(earlier, later)
        }
        looked.forEachIndexed { i, frame ->
            target.bind()
            run(sharpenProgram, GLES20.GL_TEXTURE_2D, frame.texture, identity) { p ->
                GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uTexel"), 1f / w, 1f / h)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uSharpness"), 0f)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uClarity"), 0f)
            }
            if (rifeRead[i].capacity() < w * h * 4) {
                rifeRead[i] = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            }
            rifeRead[i].rewind()
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rifeRead[i])
            rifeRead[i].rewind()
        }
        // The frames' arrivals are their ids: the later frame of this pair is the earlier of the next.
        val rgb = net.run(
            rifeRead[0], rifeRead[1], w, h, keptAt[1], keptAt[2], before = rifeRead[2].takeIf { looked.size > 2 },
        ) ?: return
        if (rifeTextures[0] == 0) GLES20.glGenTextures(2, rifeTextures, 0)
        if (size != rifeSize) {
            // A new size (other material, or the first): both in-betweens anew, neither holding a pair yet.
            rifeTextures.forEach {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, it)
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB, w, h, 0, GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, null,
                )
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            }
            rifeFor.fill(0L)
            rifeSize = size
        }
        // Into the slot of the older pair: the newer one may still be on screen.
        val slot = if (rifeFor[0] <= rifeFor[1]) 0 else 1
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, rifeTextures[slot])
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, rgb)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)
        rifeFor[slot] = keptAt[2]
    }

    /**
     * [upscaler] (waifu2x, Real-CUGAN) on the unit its self-test chose, opened here on first use or when either
     * changes; null where the device has not passed it.
     */
    private fun enlargerNet(upscaler: PictureAdjustment.Upscaler): NeuralEnlarger? {
        val unit = neuralEnlargers[upscaler]?.let { runCatching { NeuralUpscaler.Unit.valueOf(it.unit) }.getOrNull() }
        if (unit == null) {
            enlarger?.close()
            enlarger = null
            return null
        }
        if (enlarger?.unit != unit || enlarger?.upscaler != upscaler) {
            enlarger?.close()
            enlarger = appContext?.let { NeuralEnlarger.open(it, upscaler, unit) }
        }
        return enlarger
    }

    /**
     * The open upscaler over [source] ([width] × [height]): the picture read back, through the network, and its
     * output uploaded into an RGB texture twice the size. Returns that texture.
     */
    private fun enlargerPass(source: Frame, width: Int, height: Int): Int {
        val net = enlarger!!
        val scale = NeuralEnlarging.scale(net.upscaler)
        source.bind()
        val bytes = width * height * 4
        val read = enlargerRead?.takeIf { it.capacity() == bytes }
            ?: ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()).also { enlargerRead = it }
        read.rewind()
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, read)
        read.rewind()
        val outWidth = width * scale
        val outHeight = height * scale
        if (enlargerTexture == 0 || enlargerTextureWidth != outWidth || enlargerTextureHeight != outHeight) {
            if (enlargerTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(enlargerTexture), 0)
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            enlargerTexture = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, enlargerTexture)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB, outWidth, outHeight, 0, GLES20.GL_RGB,
                GLES20.GL_UNSIGNED_BYTE, null,
            )
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            enlargerTextureWidth = outWidth
            enlargerTextureHeight = outHeight
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, enlargerTexture)
        net.run(read, width, height)?.let { rgb ->
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D, 0, 0, 0, outWidth, outHeight, GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, rgb,
            )
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)
        }
        return enlargerTexture
    }

    /** Real-ESRGAN on the unit asked for, opened here on first use or when the unit changes; null while off. */
    private fun neuralNet(): NeuralUpscaler? {
        val unit = neuralUnit
        if (unit == null) {
            neural?.close()
            neural = null
            return null
        }
        if (neural?.unit != unit) {
            neural?.close()
            neural = appContext?.let { NeuralUpscaler.open(it, unit) }
        }
        return neural
    }

    /**
     * Real-ESRGAN over [source] ([width] × [height]): the picture read back, every tile through the network, and
     * each tile's core uploaded straight from its output into an RGB texture four times the size — the unpack
     * offsets pick the core out, so no copy goes through the processor. Returns that texture.
     */
    private fun neuralPass(source: Frame, width: Int, height: Int): Int {
        val net = neural!!
        val scale = NeuralUpscaling.SCALE
        source.bind()
        val bytes = width * height * 4
        val read = neuralRead?.takeIf { it.capacity() == bytes }
            ?: ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()).also { neuralRead = it }
        read.rewind()
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, read)
        val outWidth = width * scale
        val outHeight = height * scale
        if (neuralTexture == 0 || neuralTextureWidth != outWidth || neuralTextureHeight != outHeight) {
            if (neuralTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(neuralTexture), 0)
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            neuralTexture = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, neuralTexture)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB, outWidth, outHeight, 0, GLES20.GL_RGB,
                GLES20.GL_UNSIGNED_BYTE, null,
            )
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            neuralTextureWidth = outWidth
            neuralTextureHeight = outHeight
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, neuralTexture)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, NeuralUpscaling.TILE * scale)
        for (tile in NeuralUpscaling.tiles(width, height)) {
            val rgb = net.run(read, width, height, tile)
            GLES20.glPixelStorei(GLES30.GL_UNPACK_SKIP_PIXELS, (tile.coreX - tile.x) * scale)
            GLES20.glPixelStorei(GLES30.GL_UNPACK_SKIP_ROWS, (tile.coreY - tile.y) * scale)
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D, 0, tile.coreX * scale, tile.coreY * scale,
                tile.coreWidth * scale, tile.coreHeight * scale, GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, rgb,
            )
        }
        GLES20.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)
        GLES20.glPixelStorei(GLES30.GL_UNPACK_SKIP_PIXELS, 0)
        GLES20.glPixelStorei(GLES30.GL_UNPACK_SKIP_ROWS, 0)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)
        return neuralTexture
    }

    /**
     * xBRZ's patterns (see [Xbrz.weights]) as a [XBRZ_TABLE_WIDTH] × 25 texture: a row per factor from 2 and
     * pattern, a texel per cell of the block, its share of the neighbour's colour as m in red and n in green.
     */
    private fun xbrzTable(): Int {
        val rows = Xbrz.FACTORS.count() * Xbrz.Pattern.entries.size
        val bytes = ByteBuffer.allocateDirect(XBRZ_TABLE_WIDTH * rows * 4).order(ByteOrder.nativeOrder())
        for (factor in Xbrz.FACTORS) for (pattern in Xbrz.Pattern.entries) {
            val weights = Xbrz.weights(factor, pattern)
            for (cell in 0 until XBRZ_TABLE_WIDTH) {
                val m = if (cell < factor * factor) weights[cell * 2] else 0
                val n = if (cell < factor * factor) weights[cell * 2 + 1] else 1
                bytes.put(m.toByte()).put(n.toByte()).put(0).put(0)
            }
        }
        bytes.position(0)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, XBRZ_TABLE_WIDTH, rows, 0, GLES20.GL_RGBA,
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

    /**
     * Draws the picture at the screen's size from here on: onto the screen, or with a colour correction set
     * ([adjusting]) into the frame it is corrected from (see output).
     */
    private fun toScreen(width: Int, height: Int, adjusting: Boolean) {
        onWindow(width, height)
        if (adjusting) frames[ADJUSTED].ensure(width, height).bind()
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
    /**
     * [block] with [textures] sampled nearest instead of linearly: for the passes that read exact pixels (YADIF and
     * BWDIF, Film's weave, the luma read back, xBRZ). Through linear filtering a texel's centre addressed a hair off
     * — as a GPU's division may — blends in 1/256 of the neighbour, which moves YADIF's choices by up to 139 steps;
     * nearest tolerates half a texel. The textures are linear again afterwards.
     */
    private inline fun <T> exact(vararg textures: Int, block: () -> T): T {
        sampling(textures, GLES20.GL_NEAREST)
        try {
            return block()
        } finally {
            sampling(textures, GLES20.GL_LINEAR)
        }
    }

    private fun sampling(textures: IntArray, mode: Int) = textures.forEach {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, it)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, mode)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, mode)
    }

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

        /** Whether the texture holds half floats (values outside 0..1, see Anime4K) rather than bytes. */
        var half = false
            private set

        /**
         * Draws into this frame from here on, over the whole of it: the framebuffer bound and the viewport set to
         * the frame's own size together, so a pass never inherits the viewport of the one before — a smaller one
         * (Film's luma packing) left part of the frame stale, a larger one (the screen's) drew a corner zoomed.
         */
        fun bind() {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
            GLES20.glViewport(0, 0, width, height)
        }

        fun ensure(w: Int, h: Int, half: Boolean = false): Frame {
            if (w == width && h == height && half == this.half && texture != 0) return this
            release()
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            texture = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            if (half) {
                GLES30.glTexImage2D(
                    GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, w, h, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null,
                )
            } else {
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
                )
            }
            this.half = half
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

        /** How long a snapshot waits for a picture drawn before it gives up. */
        private const val SNAPSHOT_WAIT_MS = 1_000L

        /** The tallest picture taken as standard definition, coloured by BT.601: PAL's 576 lines. */

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

        /** The third kept frame for blending and Motion, after the packed luma. */
        private const val KEPT_THIRD = PACKED_LUMA + 1

        /** A picture larger than 1080p brought down, and its first pass across, after the kept frames. */
        private const val REDUCED_ACROSS = KEPT_THIRD + 1
        private const val REDUCED = REDUCED_ACROSS + 1

        /** Anime4K's two half-float textures, taking turns between its passes. */
        private const val ANIME4K_A = REDUCED + 1
        private const val ANIME4K_B = ANIME4K_A + 1

        /** xBRZ's corners and decisions between its passes (see glsl/xbrz). */
        private const val XBRZ_CORNERS = ANIME4K_B + 1
        private const val XBRZ_DECISIONS = XBRZ_CORNERS + 1

        /** The picture at the screen's size before the colour correction, when one is set. */
        private const val ADJUSTED = XBRZ_DECISIONS + 1

        /** A frame brought to RIFE's working size, to read back for it. */
        private const val RIFE_INPUT = ADJUSTED + 1

        /** ACNet's second pair of half-float textures; its first pair is Anime4K's two. */
        private const val ACNET_C = RIFE_INPUT + 1
        private const val ACNET_D = ACNET_C + 1

        /** The debanded picture, before the denoise filter reads it. */
        private const val DEBANDED = ACNET_D + 1

        /** An HDR frame as it is coded, before the tone mapping, and its peak measurement's blocks (see copyInput). */
        private const val HDR_CODED = DEBANDED + 1
        private const val HDR_BLOCKS = HDR_CODED + 1

        /** 10-bit limited-range BT.2020 YUV to RGB, for the decoder's HDR frames read as YUV (copy-y2y-es3.frag). */
        private val BT2020_TEN_BIT = YuvMatrix.BT2020.toRgbTenBit()

        /** Width of xBRZ's pattern table, one texel a cell of the largest block — as glsl/xbrz/scale.frag reads it. */
        private const val XBRZ_TABLE_WIDTH = 36

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

        /** The vector texture's zero: half-vectors of -32..32 kept as bytes around it. */
        private const val VECTOR_ZERO = 128

        /** RCAS strength on AMD's scale of stops below full sharpness (FsrRcasCon): 0.2 stops, exp2(-0.2). */
        private const val RCAS_SHARPNESS = 0.8705506f

        private val QUAD: FloatBuffer = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        private val QUAD_TEX: FloatBuffer = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        private fun floats(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(values)
                position(0)
            }

        /** [program], or an exception where it failed to link. */
        private fun linked(program: Int): Int {
            val ok = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { "Program: " + GLES20.glGetProgramInfoLog(program) }
            return program
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

        /** Pictures from FFmpeg waiting to be drawn, beyond which the oldest is let go. */
        private const val MAX_YUV_QUEUED = 4


        /** NIS's sharpness, on its own 0..1 scale: the middle, NVIDIA's neutral setting. */
        private const val NIS_SHARPNESS = 0.5f

        /** How NIS's filter taps (-0.62..1.2) map into 0..1 in its table. */
        private const val NIS_OFFSET = 1f
        private const val NIS_SPAN = 2.5f

        /** How many distances the EWA table holds, and how its weights (-0.25..1) map into 0..1. */
        /**
         * How far Lanczos and EWA pull their result into the range of the four nearest pixels: halfway, so ringing
         * at hard edges is held back without flattening fine detail.
         */
        private const val ANTIRING = 0.5f

        private const val EWA_TABLE_SIZE = 512
        private const val EWA_OFFSET = 0.25f
        private const val EWA_SPAN = 1.25f

    }
}
