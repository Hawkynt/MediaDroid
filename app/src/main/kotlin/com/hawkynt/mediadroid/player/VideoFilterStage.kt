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
import android.opengl.GLES31
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.media3.decoder.VideoDecoderOutputBuffer
import com.hawkynt.mediadroid.shaders.Shaders
import com.hawkynt.mediadroid.stage.Colouring
import com.hawkynt.mediadroid.stage.Conversion
import com.hawkynt.mediadroid.stage.Deinterlacing
import com.hawkynt.mediadroid.stage.DrawTarget
import com.hawkynt.mediadroid.stage.Finishing
import com.hawkynt.mediadroid.stage.MpvHooks
import com.hawkynt.mediadroid.stage.SampledTexture
import com.hawkynt.mediadroid.stage.StageFrame
import com.hawkynt.mediadroid.stage.Passes
import com.hawkynt.mediadroid.stage.Restoration
import com.hawkynt.mediadroid.stage.Upscaling
import com.hawkynt.mediadroid.stage.WindowTarget
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch

/** The stage's frames: :stage's (StageFrame). */
private typealias Frame = StageFrame

/**
 * The picture filters' own GL stage between the decoder and the screen, for what a colour matrix
 * cannot do: denoising, sharpening, clarity. The decoder — the device's or the bundled FFmpeg's —
 * draws into [inputSurface]; every frame is copied into a texture of the video's size, filtered, and
 * drawn into the [setOutput] surface (the player view's own TextureView), with the frame's own
 * presentation time. The player uses it while a filter is set, and for HDR on a screen without HDR, which it
 * tone-maps into the SDR picture the filters work on (see [hdr]); otherwise the decoder draws straight onto the view.
 * Where the picture holds more than the screen's 8 bits — a 10-bit source, HDR tone-mapped, a colour correction's or
 * debanding's float results — the frames hold half floats and the last pass dithers to 8 bits ([Dither]).
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

    // Whether the context is OpenGL ES 3.1 or later, which runs compute shaders (an mpv user shader's compute passes).
    private var es31 = false

    // The motion searches' read-backs on ES 3 (see LumaReader): Motion's, and temporal denoising's.
    private val motionLuma = LumaReader()
    private val denoiseLuma = LumaReader()

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

    // The passes as :stage runs them (Passes), on this thread's context, the film grain and its seed handed to each;
    // the upscalers' among them (Upscaling).
    private val gl = AndroidGles()
    private val passes = Passes(gl, grain = { grainNow }, seed = { seed })
    private val upscalers = Upscaling(passes)

    // Colour for black-and-white pictures (Colouring): DDColor on a thread of its own, its colour laid under each
    // picture.
    private val colouring = Colouring(gl, passes)

    // The restoration passes at the picture's size (Restoration): dot crawl, temporal denoising, deblocking and
    // deringing, the logo, debanding, denoising, sharpening; whether debanding runs GLSL ES 3.00's pass, and whether
    // deblocking's and deringing's (GLSL ES 3.00 only) built.
    private val restoration = Restoration(gl, passes)

    // The deinterlacers' passes (Deinterlacing).
    private val deinterlacing = Deinterlacing(passes)

    // HDR's peak and tone mapping, and the chroma enlarged from the FFmpeg decoder's planes (Conversion).
    private val conversion = Conversion(passes)

    // Blending, Motion, the colour correction and the dither (Finishing).
    private val finishing = Finishing(passes)

    // The mpv user shaders' runner (MpvHooks), its frames this stage's mpvFrames; compute passes on OpenGL ES 3.1.
    private val mpvHooks = MpvHooks(gl, passes, finishing, compute = { es31 }) { name, w, h -> mpvFrame(name, w, h) }
    private var debandEs3 = false
    private var deblockReady = false

    /**
     * Where DDColor's self-test passed and what one run took there (NeuralChecks.colorizer); null while colour is not
     * offered, when a picture asking for colour is shown as it is.
     */
    @Volatile
    var colorizer: NeuralInterpolation.Passed? = null

    // Whether the graphics chip can draw into half-float textures, which Anime4K's and ACNet's values outside 0..1
    // need, and Anime4K's programs built.
    private var halfTargets = false

    // ACNet (see glsl/acnet): its programs built when first picked, so the stage starts as quickly as ever; null until
    // then, false where the chip cannot build them.
    private var acnetBuilt: Boolean? = null

    private fun acnetReady(): Boolean = halfTargets && (acnetBuilt ?: runCatching {
        upscalers.build(PictureAdjustment.Upscaler.ACNET)
    }.isSuccess.also { acnetBuilt = it })

    // Whether xBRZ's programs built (see glsl/xbrz).
    private var xbrzReady = false

    // Whether FSR runs its GLSL ES 3.00 passes (see setUp).
    private var fsrEs3 = false

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
     * The upscalers' models on ncnn (waifu2x, Real-CUGAN, RealSR) the household's self-test passed, with the unit each
     * runs on; one not in it plays as Standard scaling.
     */
    @Volatile
    var neuralEnlargers: Map<NeuralEnlarging.Choice, NeuralInterpolation.Passed> = emptyMap()

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

    // The FFmpeg decoder's planes as uploaded: the picture's size, its strides, and the chroma upscaling the frame last
    // taken has been drawn with (see chromaUpscaled: the upscaler, or the mpv shader hooking the planes with it), null
    // until it is.
    private var yuvWidth = 0
    private var yuvHeight = 0
    private val yuvStrides = IntArray(2)
    private var chromaDrawn: Any? = null

    // Chroma upscaling's passes: spline36 (GLSL ES 1.00) and KrigBilateral's three (GLSL ES 3.00, half floats between
    // them; where the chip has neither, spline36 takes its place).
    private var krigReady = false

    /** When the frame being drawn arrived, on the clock eglPresentationTimeANDROID and the history use. */
    private var frameTimestamp = 0L
    private val croppedMatrix = FloatArray(16)
    private val eyeMatrix = FloatArray(16)

    /**
     * The frame's own transform, after the crop to one eye of a 3D frame when one is chosen, and then — unless [bars]
     * is false, for the samples the black bars are found in — the crop of those bars.
     */
    private fun frameMatrix(bars: Boolean = true, base: FloatArray = texMatrix): FloatArray {
        val stereo = filters.stereo
        val crop = if (bars) activeCrop() else null
        if (stereo == PictureAdjustment.Stereo.OFF && crop == null) return base
        val eye = if (stereo == PictureAdjustment.Stereo.OFF) {
            base
        } else {
            android.opengl.Matrix.multiplyMM(eyeMatrix, 0, base, 0, stereo.cropMatrix(), 0)
            eyeMatrix
        }
        if (crop == null) return eye
        val whole = wholeEye()
        android.opengl.Matrix.multiplyMM(croppedMatrix, 0, eye, 0, crop.textureMatrix(whole.width, whole.height), 0)
        return croppedMatrix
    }

    // Black bars (see AutoCrop): the decision, made on a thread of its own from a small sample of the picture taken
    // every SAMPLE_INTERVAL_MS, and the crop in effect on this thread — in the eye's pixels, for the eye size it was
    // found for — with the pixel buffers the samples are read back through on ES 3, taking turns.
    private val autoCrop = AutoCrop()
    private val cropWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var barCrop: PictureCrop? = null
    private var barCropFor = PictureSize(0, 0)
    private var lastCropSampleNs = 0L
    private var cropGeneration = 0
    private val cropBuffers = IntArray(2)
    private val cropBufferSizes = IntArray(2)
    private val cropPending = arrayOfNulls<PendingLuma>(2)
    private var cropSlot = 0

    // The crop last told about (see publishCrop).
    private var publishedCrop: PictureCrop? = null

    /**
     * The share of the eye's width and height the picture shows once its black bars are cropped: 1 and 1 for the
     * whole picture — for the view to take the picture's shape.
     */
    @Volatile
    var cropShare: Pair<Float, Float> = 1f to 1f
        private set

    /**
     * The black bars' crop in effect and the size of the picture it is a part of (the eye's), null without one — for
     * the subtitles libass lays out on the whole video (AssOverlayView).
     */
    @Volatile
    var cropArea: Pair<PictureCrop, PictureSize>? = null
        private set

    /** Told on this thread whenever [cropShare] changes. */
    @Volatile
    var onCrop: ((widthShare: Float, heightShare: Float) -> Unit)? = null

    /** The black bars' crop in effect: found for the eye shown now, while the household has cropping on. */
    private fun activeCrop(): PictureCrop? = barCrop.takeIf { filters.cropBlackBars && barCropFor == wholeEye() }

    /** Forgets the black bars found so far — a new video starts its own. */
    fun forgetCrop() {
        handler.post { restartCrop() }
    }

    private fun restartCrop() {
        cropGeneration++
        cropPending.fill(null)
        lastCropSampleNs = 0L
        if (!cropWorker.isShutdown) cropWorker.execute { autoCrop.restart() }
        setCrop(null, PictureSize(0, 0))
    }

    private fun setCrop(crop: PictureCrop?, eye: PictureSize) {
        barCrop = crop
        barCropFor = eye
        publishCrop()
    }

    /**
     * Tells [onCrop] when the crop in effect has changed — a new one found, or cropping turned off, another 3D eye or
     * another size of video making the one found void.
     */
    private fun publishCrop() {
        val now = activeCrop()
        if (now == publishedCrop) return
        publishedCrop = now
        val share = now?.let { it.width.toFloat() / barCropFor.width to it.height.toFloat() / barCropFor.height }
            ?: (1f to 1f)
        cropArea = now?.let { it to barCropFor }
        cropShare = share
        onCrop?.invoke(share.first, share.second)
    }

    /**
     * Every [AutoCrop.SAMPLE_INTERVAL_MS] while cropping is on: the decoded picture, its bars and all, drawn small into
     * a frame of its own and its luma read back — on ES 3 into a pixel buffer collected a sample later, without
     * waiting — for [autoCrop] to judge on its own thread; a changed crop comes back here and takes effect with the next
     * frame.
     */
    private fun sampleForCrop() {
        if (!filters.cropBlackBars) return
        val now = System.nanoTime()
        if (lastCropSampleNs != 0L && now - lastCropSampleNs < AutoCrop.SAMPLE_INTERVAL_MS * 1_000_000L) return
        lastCropSampleNs = now
        val eye = wholeEye()
        val size = AutoCrop.sampleSize(eye.width, eye.height)
        val sample = frames[CROP_SAMPLE].ensure(size.width, size.height)
        sample.bind()
        copyCoded(tenBit = false, bars = false)
        if (!es3) {
            judgeCrop(packedLuma(sample, size.width, size.height, 1, 1), eye)
            return
        }
        val slot = cropSlot
        cropSlot = 1 - slot
        collectCropSample(1 - slot)
        if (cropBuffers[0] == 0) GLES20.glGenBuffers(2, cropBuffers, 0)
        val (columns, rows, texels) = packLuma(sample, size.width, size.height, 1, 1)
        val bytes = texels * rows * 4
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, cropBuffers[slot])
        if (cropBufferSizes[slot] != bytes) {
            GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, bytes, null, GLES30.GL_STREAM_READ)
            cropBufferSizes[slot] = bytes
        }
        GLES30.glReadPixels(0, 0, texels, rows, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, 0)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        // The eye's size rides along in the pending sample's id: width above, height below.
        cropPending[slot] = PendingLuma((eye.width.toLong() shl 32) or eye.height.toLong(), columns, rows, texels)
    }

    /** The sample waiting in [slot]'s pixel buffer, if any, handed on to be judged. */
    private fun collectCropSample(slot: Int) {
        val pending = cropPending[slot] ?: return
        cropPending[slot] = null
        val size = pending.texels * pending.rows * 4
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, cropBuffers[slot])
        val mapped = GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER, 0, size, GLES30.GL_MAP_READ_BIT)
            as? ByteBuffer
        val plane = mapped?.let {
            unpackLuma(it.order(ByteOrder.nativeOrder()), pending.columns, pending.rows, pending.texels)
        }
        if (mapped != null) GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        if (plane != null) judgeCrop(plane, PictureSize((pending.id ushr 32).toInt(), pending.id.toInt()))
    }

    /** [luma], a sample of an [eye]-sized picture, judged off this thread; a changed crop is taken back here. */
    private fun judgeCrop(luma: Deinterlacer.Plane, eye: PictureSize) {
        val generation = cropGeneration
        if (cropWorker.isShutdown) return
        cropWorker.execute {
            if (!autoCrop.add(luma)) return@execute
            val crop = autoCrop.crop(eye.width, eye.height).takeUnless { it.isWhole(eye.width, eye.height) }
            handler.post { if (generation == cropGeneration) setCrop(crop, eye) }
        }
    }
    private val identity = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }

    private var copyProgram = 0

    // Deblocking's pass and deringing's two (GLSL ES 3.00; without, neither runs).

    // Debanding's frame index, which seeds its random numbers with the pixel's position: libplacebo's 8-bit counter.

    // Dithering to the screen's 8 bits (see Dither): the last pass, and libplacebo's blue-noise matrix as a texture
    // (made on first use); whether the picture being drawn is dithered, its frames then held in half floats.
    // Whether the chip dithers, and with GLSL ES 3.00's pass (see setUp).
    private var ditherReady = false
    private var ditherEs3 = false
    private var ditherNow = false
    private var sharpenProgram = 0
    private var downscaleProgram = 0
    private var blendProgram = 0

    // MC Smooth (see TemporalSmoothing): the averaging along the motion, and the contra-sharpening and repair after it.
    private var weaveProgram = 0
    private var packProgram = 0

    // HDR on a screen without HDR (see hdr): the tone mapping, the measurement of the source's peak, and where GLSL ES
    // 3.00 has GL_EXT_YUV_target the copy that reads the decoder's 10-bit YUV itself (0 without it).
    private var y2yProgram = 0
    private val peakDetect = PeakDetect()

    // The peak measurement's read-back on ES 3: two pixel buffers taking turns, each a frame's blocks until the next
    // frame collects them — libplacebo's delayed peak detection; ES 2 reads each frame's back at once.
    private val peakBuffers = IntArray(2)
    private val peakBufferSizes = IntArray(2)
    private val peakPending = IntArray(2)
    private var peakSlot = 0
    private val frames = Array(COLOURED + 1) { Frame(gl) }

    // Temporal deinterlacing: the last three decoded frames (frames[8] to [10], taking turns), oldest
    // first, with when each was due; the picture shown is the middle one, one frame behind.
    private val history = intArrayOf(8, 9, 10)

    // The frames before the oldest of those, newest first (frames[EARLIEST] on, or ones of [8] to [10], taking turns
    // with them), for the dot-crawl comb (DotCrawl): PAL's reads the frame two before the one shown, the first; the
    // comb per field, for a PAL or NTSC capture bobbed to its fields, the same field's back to six before, all [OLDER]
    // — which take turns only while it runs ([olderUsed]), so the frames for them are drawn into only then.
    // [olderCount] of them hold frames of this stretch of playback.
    private val older = IntArray(OLDER) { EARLIEST + it }
    private var olderUsed = 1
    private var olderCount = 0
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

    // Adaptive: whether the picture has stood still long enough to be woven whole, from each new frame's luma.
    private val motionAdaptive = MotionAdaptive()
    private var adaptiveLuma: Deinterlacer.Plane? = null

    // Old console: the last three frames' luma, oldest first, to weigh the shown frame's fields on.
    private val consoleLumas = arrayOfNulls<Deinterlacer.Plane>(3)
    private var framesToJudge = 0
    private var readBack: ByteBuffer? = null

    // Film (3:2 or 2:2 pulldown removed, see FilmCadence): each frame judged on a thread of its own, woven into one
    // of two banks of five slots (frames[11] on), and once a bank's cycle is judged its kept frames shown evenly.
    // The cadence lives on that thread; a new history (a seek) starts a new generation, whose verdicts alone count.
    private val filmWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val filmCadence = FilmCadence()
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
    private var obmcTexture = 0

    // Temporal denoising's own search, on the decoded frames as they join the history: the last frame's luma, the
    // search's state and scale, and the newest vectors as a texture — the pair whose later frame arrived at
    // denoiseVectorsFor (0 for none), its grid, the scale it ran at, the size of the frames it ran on and whether a
    // cut lies between them — and the noise level the searched frames show.
    private var denoisePrevious: Pair<Long, Deinterlacer.Plane>? = null
    private var denoiseEstimator: MotionInterpolation.Estimator? = null
    private val denoiseCut = MotionInterpolation.SceneChange()
    private var denoiseScale: SearchScale? = null
    private var denoiseVectorTexture = 0
    private var denoiseVectorsFor = 0L
    private val denoiseGrid = IntArray(2)
    private var denoiseGridScale = 1
    private val denoiseFrame = IntArray(2)
    private var denoiseVectorsCut = false
    private val noiseLevel = TemporalDenoise.NoiseLevel()
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
        onScreenSample?.let { take ->
            val now = System.nanoTime()
            if (now - lastScreenSampleNs >= SCREEN_SAMPLE_NS) {
                lastScreenSampleNs = now
                runCatching { sampleWindow() }.getOrNull()?.let(take)
            }
        }
        snapshotTaker?.let { take ->
            snapshotTaker = null
            handler.removeCallbacks(snapshotTimeout)
            take(runCatching { readWindow() }.getOrNull())
        }
        EGL14.eglSwapBuffers(display, window)
    }

    /** A picture read back from the window: RGBA rows from the bottom, as OpenGL reads them. */
    class ReadBack(val rgba: ByteArray, val width: Int, val height: Int)

    /**
     * Handed, on the stage's thread, a small copy of the picture on screen — every filter and the colour correction
     * applied — twice a second while set: for the histogram (see [Histogram]). Null takes none.
     */
    @Volatile
    var onScreenSample: ((ReadBack) -> Unit)? = null

    private var lastScreenSampleNs = 0L

    /**
     * The window's picture as drawn so far, every [Histogram.step]th pixel: on GLSL ES 3 chips copied small on the
     * graphics chip first, nearest pixel (no level that is not on screen), then read back; otherwise read whole and
     * stepped through here.
     */
    private fun sampleWindow(): ReadBack {
        val width = query(EGL14.EGL_WIDTH)
        val height = query(EGL14.EGL_HEIGHT)
        val step = Histogram.step(width, height)
        if (!es3 || step == 1) {
            val whole = readWindow()
            if (step == 1) return whole
            val w = width / step
            val h = height / step
            val small = ByteArray(w * h * 4)
            for (y in 0 until h) for (x in 0 until w) {
                System.arraycopy(whole.rgba, ((y * step) * width + x * step) * 4, small, (y * w + x) * 4, 4)
            }
            return ReadBack(small, w, h)
        }
        val w = width / step
        val h = height / step
        val sample = frames[SCREEN_SAMPLE].ensure(w, h)
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, sample.framebuffer)
        GLES30.glBlitFramebuffer(0, 0, width, height, 0, 0, w, h, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sample.framebuffer)
        val bytes = ByteBuffer.allocateDirect(w * h * 4)
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        val rgba = ByteArray(w * h * 4)
        bytes.get(rgba)
        return ReadBack(rgba, w, h)
    }

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
            val cropping = field.cropBlackBars
            field = value
            if (value.cropBlackBars != cropping) handler.post { restartCrop() }
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

    /**
     * The bits per value the video is coded with (its luma's bit depth: 10 for HEVC Main 10), for the last pass to
     * dither a deeper picture to the screen's 8 bits ([Dither.applies]); FFmpeg's planes arrive in 8 bits whatever it.
     */
    @Volatile
    var sourceBits: Int = 8

    /** How a picture larger than 1080p is brought down after deinterlacing (see [reduced]). */
    @Volatile
    var downscaler: Downscaler = Downscaler.DEFAULT

    /**
     * What the last pass does to the picture's geometry on this screen ([ScreenWarp]): a projector's mirror and flip,
     * its keystone — then the stage draws the whole screen, the picture's place on it in the warp — and the panorama,
     * how many times wider than its shape the screen shows the picture ([Panorama]), the view that much wider.
     */
    @Volatile
    var warp: ScreenWarp = ScreenWarp.None
        set(value) {
            if (value == field) return
            field = value
            handler.post { busy { if (hasFrame && window != EGL14.EGL_NO_SURFACE) draw(redraw = true) } }
        }

    /**
     * The size the stage takes the decoder's frame at: the 3D eye's half, pixel for pixel, or the whole frame — less
     * the black bars cropped.
     */
    private fun eyeSize(): PictureSize =
        activeCrop()?.let { PictureSize(it.width, it.height) } ?: wholeEye()

    /** The 3D eye's half of the decoder's frame, pixel for pixel, or the whole frame; black bars and all. */
    private fun wholeEye(): PictureSize =
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
     * The size of the buffers [inputSurface] hands out to a producer that does not set its own — a canvas locked on
     * it (the test patterns): a SurfaceTexture's are one pixel by one until told otherwise. A decoder sets its own.
     */
    fun setInputSize(width: Int, height: Int) = onThread { surfaceTexture.setDefaultBufferSize(width, height) }

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
            yuvWidth = buffer.width
            yuvHeight = buffer.height
            yuvStrides[0] = strides[0]
            yuvStrides[1] = strides[1]
            chromaDrawn = null
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
        publishCrop()
        if (measure) sampleForCrop()
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
        conversion.toneMap(coded, into, pass)
    }

    /**
     * The mpv user shader [picture] chose where the stage runs it and it hooks the FFmpeg decoder's planes
     * (planesHooked): the chroma, or the Y plane where [lumaOnPlanes] — or null.
     */
    private fun chromaShader(picture: PictureAdjustment): MpvShader? = picture.shader
        ?.let { MpvShaders.library[it] }
        ?.takeIf { (it.hooksChroma || lumaOnPlanes(it)) && MpvShaders.runnable(it) && halfTargets && es3 }

    /**
     * Whether [shader]'s LUMA hooks run on the FFmpeg decoder's Y plane, as mpv runs them: where the frame came as
     * planes and the hooks leave the Y plane at its size, which the frames after it keep; a LUMA hook that enlarges it
     * (a luma doubler) runs on the RGB picture's brightness on the way out instead, as on a hardware decoder's frames.
     */
    private fun lumaOnPlanes(shader: MpvShader): Boolean {
        if (!yuvInput || shader.planned("LUMA").isEmpty()) return false
        val sizes = MpvHooks.Sizes(query(EGL14.EGL_WIDTH), query(EGL14.EGL_HEIGHT), yuvWidth, yuvHeight)
        return mpvHooks.lumaSize(shader, yuvWidth, yuvHeight, sizes) == MpvShader.Size(yuvWidth, yuvHeight)
    }

    /**
     * [coded]'s blocks measured (glsl/video/peak.frag) and taken into [peakDetect]: on ES 3 those of the frame before,
     * read back without waiting while this frame's go into the other buffer; on ES 2 this frame's, at once.
     */
    private fun measurePeak(coded: Frame, transfer: ToneMap.Transfer) {
        val side = PeakDetect.BLOCK
        val blocks = frames[HDR_BLOCKS].ensure((coded.width + side - 1) / side, (coded.height + side - 1) / side)
        conversion.peak(coded, blocks, transfer)
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
    private fun copyCoded(tenBit: Boolean, bars: Boolean = true) {
        val matrix = frameMatrix(bars)
        if (!yuvInput && tenBit && y2yProgram != 0) {
            val oes = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
            GLES20.glBindTexture(oes, oesTexture)
            GLES20.glTexParameteri(oes, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(oes, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            run(y2yProgram, oes, oesTexture, matrix) { p ->
                GLES20.glUniformMatrix3fv(GLES20.glGetUniformLocation(p, "uColor"), 1, false, BT2020_TEN_BIT, 0)
            }
            GLES20.glBindTexture(oes, oesTexture)
            GLES20.glTexParameteri(oes, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(oes, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            return
        }
        if (!yuvInput) {
            run(copyProgram, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture, matrix) {}
            return
        }
        if (filters.chroma != PictureAdjustment.Chroma.BILINEAR || chromaShader(filters) != null) {
            val upscaled = chromaUpscaled(filters.chroma, chromaShader(filters))
            // The planes' own transform is behind the upscaled frame already: only the eye and the bars are left.
            run(blendProgram, GLES20.GL_TEXTURE_2D, upscaled.texture, frameMatrix(bars, identity)) { p ->
                // A copy: the blend's other input is the same frame (the program may still point it elsewhere).
                GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uPrevious"), 0)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uWeight"), 1f)
            }
            return
        }
        run(yuvProgram, GLES20.GL_TEXTURE_2D, yuvTextures[0], matrix) { p ->
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

    /**
     * The FFmpeg decoder's frame last taken as RGB at its own size, its chroma enlarged by [chroma] (see
     * ChromaUpscaling): spline36 in one pass, KrigBilateral in three — the luma brought down to the chroma's size as
     * mean and variance, then each pixel's chroma kriged. Drawn once a frame, into a frame of its own; the bound
     * framebuffer and viewport are given back.
     */
    private fun chromaUpscaled(chroma: PictureAdjustment.Chroma, shader: MpvShader? = null): Frame {
        val full = frames[CHROMA_FULL].ensure(yuvWidth, yuvHeight)
        // A shader hooking only LUMA leaves the chroma to the upscaler: both decide the picture.
        val drawn = if (shader == null) chroma else shader to chroma
        if (chromaDrawn == drawn) return full
        chromaDrawn = drawn
        val bound = IntArray(1)
        val viewport = IntArray(4)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, bound, 0)
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0)
        if (shader != null) {
            chromaHooked(shader, chroma, full)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, bound[0])
            GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
            return full
        }
        val chromaWidth = (yuvWidth + 1) / 2
        val chromaHeight = (yuvHeight + 1) / 2
        // The FFmpeg decoder hands over limited range (a full-range source squeezed into it).
        val planes = Conversion.Planes(
            yuvTextures[0], yuvTextures[1], yuvTextures[2], yuvWidth, yuvHeight, yuvStrides[0], yuvStrides[1],
            yuvColor, 16f / 255f, texMatrix, yuvChroma,
        )
        if (chroma == PictureAdjustment.Chroma.KRIG && krigReady) {
            val first = frames[CHROMA_LOW].ensure(yuvWidth, chromaHeight, half = true)
            val second = frames[CHROMA_LOWER].ensure(chromaWidth, chromaHeight, half = true)
            conversion.krig(planes, first, second, full)
        } else {
            conversion.spline(planes, full)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, bound[0])
        GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        return full
    }

    fun release() {
        handler.removeCallbacks(pumpYuv)
        handler.post(snapshotTimeout)
        while (true) yuvQueue.pollFirst()?.buffer?.release() ?: break
        onThread {
            // On this thread, the vsync's own: a caller's may have no looper to make one on.
            vsync.removeFrameCallback(onYuvVsync)
            GLES20.glDeleteProgram(yuvProgram)
            passes.release()
            colouring.close()
            colouring.releaseFrames()
            acnetBuilt = null
            GLES20.glDeleteTextures(3, yuvTextures, 0)
            releaseWindow()
            GLES20.glDeleteProgram(copyProgram)
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
            mpvHooks.release()
            mpvFrames.values.forEach { it.release() }
            if (y2yProgram != 0) GLES20.glDeleteProgram(y2yProgram)
            if (peakBuffers[0] != 0) GLES20.glDeleteBuffers(2, peakBuffers, 0)
            GLES20.glDeleteProgram(sharpenProgram)
            GLES20.glDeleteProgram(downscaleProgram)
            GLES20.glDeleteProgram(blendProgram)
            GLES20.glDeleteProgram(packProgram)
            motionLuma.release()
            denoiseLuma.release()
            GLES20.glDeleteTextures(1, intArrayOf(denoiseVectorTexture), 0)
            if (cropBuffers[0] != 0) GLES20.glDeleteBuffers(2, cropBuffers, 0)
            cropWorker.shutdownNow()
            GLES20.glDeleteTextures(3, intArrayOf(vectorTextures[0], vectorTextures[1], obmcTexture), 0)
            motionSearch.shutdownNow()
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
        if (es3) {
            val version = IntArray(2)
            GLES30.glGetIntegerv(GLES30.GL_MAJOR_VERSION, version, 0)
            GLES30.glGetIntegerv(GLES30.GL_MINOR_VERSION, version, 1)
            es31 = version[0] > 3 || (version[0] == 3 && version[1] >= 1)
        }
        halfTargets = es3 &&
            ("GL_EXT_color_buffer_half_float" in extensions || "GL_EXT_color_buffer_float" in extensions)
        // A chip that cannot build Anime4K's programs gets FSR in its place rather than no picture at all.
        halfTargets = halfTargets && runCatching {
            upscalers.build(PictureAdjustment.Upscaler.ANIME4K)
            // The mpv user shaders' compute passes write half floats, on a chip with compute shaders.
            MpvShaders.compute = es31
        }.isSuccess
        conversion.build(krig = false)
        // KrigBilateral keeps the luma's mean and variance in half floats between its passes; spline36 stands in
        // where the chip draws none or cannot build them.
        krigReady = halfTargets && runCatching { conversion.build(krig = true) }.isSuccess
        xbrzReady = runCatching { upscalers.build(PictureAdjustment.Upscaler.XBRZ) }.isSuccess
        inputSurface = Surface(surfaceTexture)

        copyProgram = program(Shaders.VIDEO_VERTEX, Shaders.VIDEO_COPY)
        finishing.build()
        deinterlacing.build()
        sharpenProgram = program(Shaders.VIDEO_VERTEX, Shaders.VIDEO_SHARPEN)
        upscalers.build(PictureAdjustment.Upscaler.LANCZOS)
        upscalers.build(PictureAdjustment.Upscaler.GSR)
        downscaleProgram = program(Shaders.VIDEO_VERTEX, Shaders.VIDEO_DOWNSCALE)
        upscalers.build(PictureAdjustment.Upscaler.EWA)
        upscalers.build(PictureAdjustment.Upscaler.NIS)
        // FSR reads its floats' bits for AMD's approximate reciprocals where GLSL ES 3.00 can (about half the cost on
        // a Mali G77 at 576p); the GLSL ES 1.00 passes compute the same bits from the floats' fields elsewhere.
        fsrEs3 = es3 && runCatching { upscalers.build(PictureAdjustment.Upscaler.FSR, es3 = true) }.isSuccess
        if (!fsrEs3) upscalers.build(PictureAdjustment.Upscaler.FSR, es3 = false)
        // Debanding draws libplacebo's own random numbers where GLSL ES 3.00 has unsigned integers for them; the
        // GLSL ES 1.00 pass draws others (see glsl/video/deband.frag).
        debandEs3 = es3 && runCatching { passes.program(Shaders.VIDEO_DEBAND_ES3) }.isSuccess
        restoration.build(debandEs3)
        // Deblocking and deringing filter each value as its exact 8-bit step: GLSL ES 3.00's integers and texelFetch.
        deblockReady = es3 && runCatching {
            listOf(Shaders.VIDEO_DEBLOCK_ES3, Shaders.VIDEO_CDEF_DIRECTION_ES3, Shaders.VIDEO_CDEF_ES3)
                .forEach { passes.program(it) }
        }.isSuccess
        listOf(
            Shaders.VIDEO_MPV_LUMA, Shaders.VIDEO_MPV_RECOMBINE, Shaders.VIDEO_MPV_CHROMA, Shaders.VIDEO_MPV_CHROMA_RGB,
        ).forEach { passes.program(it) }
        // Dithering reads its texels exactly with GLSL ES 3.00's texelFetch; the GLSL ES 1.00 pass reads their centres.
        // Only chips that draw into half floats dither (the frames have to hold more than 8 bits), and those run ES 3.
        if (halfTargets) {
            ditherEs3 = runCatching { passes.program(Shaders.VIDEO_DITHER_ES3) }.isSuccess
            ditherReady = ditherEs3 || runCatching { passes.program(Shaders.VIDEO_DITHER) }.isSuccess
            // The matrix takes some tens of milliseconds to make: on a thread of its own, ready before it is needed.
            if (ditherReady) Thread { Dither.blueNoise }.apply { isDaemon = true }.start()
        }
        // The decoder's 10-bit YUV read as it is, where the chip offers it; else samplerExternalOES's RGB.
        y2yProgram = if (es3 && "GL_EXT_YUV_target" in extensions) {
            runCatching { linked(program(Shaders.VIDEO_VERTEX_ES3, Shaders.VIDEO_COPY_Y2Y_ES3)) }.getOrDefault(0)
        } else {
            0
        }
        blendProgram = program(Shaders.VIDEO_VERTEX, Shaders.VIDEO_BLEND)
        packProgram = program(Shaders.VIDEO_VERTEX, Shaders.VIDEO_PACK_LUMA)
        for (k in 0 until 2) vectorTextures[k] = byteTexture(1, 1, ByteArray(4))
        denoiseVectorTexture = byteTexture(1, 1, ByteArray(4))
        obmcTexture = byteTexture(
            32, 32, ByteArray(32 * 32 * 4) { if (it % 4 == 0) MotionInterpolation.obmcWeight(it / 4).toByte() else 0 },
        )
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
        if (picture.deinterlace.isTemporal || picture.temporalDenoiseParams() != null || picture.dotCrawl > 0f) {
            return temporal(picture, width, height, fresh, secondField)
        }
        historyCount = 0
        deinterlaceLatencyMs = 0
        deinterlaceFrames = 0f
        shownAt = frameTimestamp
        val half = deepInput(picture)
        val copied = frames[0].ensure(width, height, half)
        copyInput(copied, measure = fresh)
        var source = copied
        if (picture.deinterlace != PictureAdjustment.Deinterlace.OFF) {
            val woven = frames[2].ensure(width, height, half)
            deinterlacing.spatial(copied, woven, picture.deinterlace, fieldOrder.topFieldFirst)
            source = woven
        }
        source = reduced(source)
        return denoised(picture, source, source.width, source.height)
    }

    /**
     * The dot-crawl comb's standard for [picture] (DotCrawl.Standard): from the frames' interval and its colour system
     * as chosen; no colour tags reach the stage here.
     */
    private fun dotCrawlStandard(picture: PictureAdjustment): DotCrawl.Standard =
        DotCrawl.Standard.of(frameIntervalNs / 1e6f, picture.colourSystem, palColour = false)

    /**
     * A temporal deinterlacer — YADIF, BWDIF, Greedy HM, Greedy 2-Frame, Adaptive (see
     * [PictureAdjustment.Deinterlace]): a [fresh] frame joins the last three, and the one before it is shown with a
     * field rebuilt from its neighbours — or, Adaptive on a still picture, woven as it is — one frame behind, which
     * the sound is told to wait. Until three have come, the missing neighbours are the frames there are. Temporal
     * denoising shares the history, with any deinterlacer or none.
     */
    private fun temporal(
        picture: PictureAdjustment,
        width: Int,
        height: Int,
        fresh: Boolean,
        secondField: Boolean,
    ): Frame {
        // The frames before the history take turns back to six before only while the comb runs per field.
        olderUsed = if (picture.dotCrawl > 0f && dotCrawlStandard(picture).perField) OLDER else 1
        if (fresh || historyCount == 0) {
            val afresh = remember(width, height)
            if (framesToJudge > 0) judge(width, height)
            if (picture.deinterlace == PictureAdjustment.Deinterlace.ADAPTIVE) adapt(width, height, afresh)
            // Old console's lumas are its own: chosen anew, it weighs only frames it has read.
            if (picture.deinterlace == PictureAdjustment.Deinterlace.OLD_CONSOLE) {
                console(width, height, afresh)
            } else {
                consoleLumas.fill(null)
            }
            if (picture.temporalDenoise > 0f) {
                searchDenoise(width, height, afresh)
            } else if (denoisePrevious != null) {
                // Off for a while: the frames read back so far are no neighbours of the next.
                denoisePrevious = null
                denoiseVectorsFor = 0L
                denoiseLuma.drop()
            }
        }
        val next = frames[history[2]]
        val cur = if (historyCount >= 2) frames[history[1]] else next
        val prev = if (historyCount >= 3) frames[history[0]] else cur
        shownAt = historyAt[if (historyCount >= 2) 1 else 2]
        val intervalMs = if (historyCount >= 2) frameIntervalNs / 1_000_000L else 0L
        deinterlaceLatencyMs = intervalMs.toInt().coerceIn(0, MAX_LATENCY_MS)
        deinterlaceFrames = if (historyCount >= 2) 1f else 0f
        val woven = frames[2].ensure(width, height, deepInput(picture))
        // Dot crawl and rainbows combed out of the frame shown, against its neighbours as decoded (DotCrawl), the
        // standard from the rate and the household's choice (Media3 tells BT.470 B/G's colour tags from NTSC's no
        // more than the rate does, so PAL-M is chosen by hand here): NTSC's against the frames either side, PAL's and
        // PAL-M's against the frame two before as well, per field (a capture bobbed to its fields) against the same
        // field's frames two, four and six before (DotCrawl.applyPalFields, applyNtscFields).
        val combed = DotCrawl.threshold(picture.dotCrawl)?.let { threshold ->
            val into = frames[DOT_CRAWL].ensure(width, height, deepInput(picture))
            fun before(k: Int) = if (olderCount > k) frames[older[k]] else null
            when (dotCrawlStandard(picture)) {
                DotCrawl.Standard.NTSC -> restoration.dotCrawl(cur, prev, next, null, into, threshold)
                DotCrawl.Standard.PAL -> restoration.dotCrawl(cur, prev, next, before(0) ?: prev, into, threshold)
                DotCrawl.Standard.PAL_FIELDS -> restoration.dotCrawl(
                    cur, before(0) ?: cur, before(4) ?: cur, before(2) ?: cur, into, threshold,
                )
                DotCrawl.Standard.NTSC_FIELDS -> restoration.dotCrawl(
                    cur, before(2) ?: cur, before(4) ?: cur, before(0) ?: cur, into, threshold,
                )
            }
            into
        } ?: cur
        // Temporal denoising on the frame shown, against its neighbours as decoded, before any deinterlacing: each
        // moved onto it along the newest vectors its search has — the frame before along them, the one after against
        // them, as if the motion went on — or read as it is while there are none; a side behind a cut is not taken.
        val shown = picture.temporalDenoiseParams(noiseLevel.sigma)?.let { params ->
            val into = frames[TEMPORAL_DENOISED].ensure(width, height, deepInput(picture))
            val along = denoiseVectorsFor != 0L && denoiseVectorsFor in historyAt &&
                denoiseFrame[0] == width && denoiseFrame[1] == height
            // A cut is only a reason to leave a side out where it lies between that side and the frame shown: the
            // vectors of the pair before (ES 3's read-back runs a frame later) say nothing about either side then.
            val cut = along && denoiseVectorsCut
            val prevMode = when {
                !along -> 0f
                cut && denoiseVectorsFor == historyAt[1] -> 2f
                cut -> 0f
                else -> 1f
            }
            val nextMode = when {
                !along -> 0f
                cut && denoiseVectorsFor == historyAt[2] -> 2f
                cut -> 0f
                else -> 1f
            }
            fun side(frame: Frame, mode: Float) = Restoration.Side(
                frame, denoiseVectorTexture, mode, denoiseGrid[0], denoiseGrid[1], denoiseGridScale, grid = true,
            )
            restoration.temporalDenoise(combed, side(prev, prevMode), side(next, nextMode), obmcTexture, params, into)
            into
        } ?: combed
        val deinterlaced = when {
            picture.deinterlace.isTemporal -> {
                val adaptiveWeave =
                    picture.deinterlace == PictureAdjustment.Deinterlace.ADAPTIVE && motionAdaptive.weave
                val method = when {
                    picture.deinterlace == PictureAdjustment.Deinterlace.OLD_CONSOLE -> Deinterlacer.OLD_CONSOLE
                    adaptiveWeave -> Deinterlacer.WEAVE
                    else -> Deinterlacer.shaderMethod(picture.deinterlace.method ?: Deinterlacer.Method.YADIF)
                }
                // Old console: the shown frame's field averaged with the one before where they hardly differ.
                val average = method == Deinterlacer.OLD_CONSOLE && consoleLumas[1]?.let { shownLuma ->
                    OldGame.averages(
                        consoleLumas[0], shownLuma, secondField, fieldOrder.topFieldFirst, JUDGED_COLUMN_STEP,
                    )
                } == true
                rebuildField(woven, prev, shown, next, secondField, method, average)
                if (picture.deinterlace == PictureAdjustment.Deinterlace.SMOOTH_MC) {
                    smoothAlongMotion(woven, prev, shown, next, secondField, deepInput(picture))
                } else {
                    woven
                }
            }
            picture.deinterlace != PictureAdjustment.Deinterlace.OFF &&
                picture.deinterlace != PictureAdjustment.Deinterlace.FILM -> {
                deinterlacing.spatial(shown, woven, picture.deinterlace, fieldOrder.topFieldFirst)
                woven
            }
            else -> shown
        }
        val shrunk = reduced(deinterlaced)
        return denoised(picture, shrunk, shrunk.width, shrunk.height)
    }

    /**
     * Adaptive (see MotionAdaptive): the newest frame's luma, every fourth column, read back and weighed against the
     * frame before's; a new history starts the count afresh.
     */
    private fun adapt(width: Int, height: Int, afresh: Boolean) {
        val luma = packedLuma(frames[history[2]], width, height, JUDGED_COLUMN_STEP, 1)
        motionAdaptive.add(if (afresh) null else adaptiveLuma, luma, JUDGED_COLUMN_STEP)
        adaptiveLuma = luma
    }

    /**
     * Old console (see OldGame): the newest frame's luma, every fourth column, read back beside the two before it — the
     * shown frame's and its predecessor's, which its fields are weighed on; a new history starts afresh.
     */
    private fun console(width: Int, height: Int, afresh: Boolean) {
        if (afresh) consoleLumas.fill(null)
        consoleLumas[0] = consoleLumas[1]
        consoleLumas[1] = consoleLumas[2]
        consoleLumas[2] = packedLuma(frames[history[2]], width, height, JUDGED_COLUMN_STEP, 1)
    }

    /**
     * MC Smooth (see TemporalSmoothing): [base], the shown field's BWDIF picture, averaged along the motion with the
     * BWDIF pictures of the fields before and after, contra-sharpened and repaired, the camera's rows of [cur] put
     * back.
     * The stage keeps three frames, so the frame two before and the one after the next are stood in for by their
     * nearest — the neighbours' BWDIF reads them only in its temporal check.
     */
    private fun smoothAlongMotion(
        base: Frame,
        prev: Frame,
        cur: Frame,
        next: Frame,
        second: Boolean,
        half: Boolean,
    ): Frame {
        val w = base.width
        val h = base.height
        val earlier = frames[MC_EARLIER].ensure(w, h, half)
        val later = frames[MC_LATER].ensure(w, h, half)
        val smoothed = frames[MC_SMOOTHED].ensure(w, h, half)
        val out = frames[MC_SHARPENED].ensure(w, h, half)
        deinterlacing.smoothAlongMotion(
            base, prev, cur, next, next, second, earlier, later, smoothed, out, fieldOrder.topFieldFirst,
        )
        return out
    }

    /**
     * A temporal deinterlacer into [target] ([method]: temporal.frag's uMethod): [cur] with a field rebuilt — or, old
     * console, its field stretched, [average]d with the one before.
     */
    private fun rebuildField(
        target: Frame,
        prev: Frame,
        cur: Frame,
        next: Frame,
        secondField: Boolean,
        method: Int,
        average: Boolean = false,
    ) = deinterlacing.field(prev, cur, next, target, secondField, method, fieldOrder.topFieldFirst, average)

    /**
     * Film: a decoded frame joins the history, and the one before it is judged with its neighbours (see
     * FilmCadence) into the bank being filled: woven from its matched fields, or — still combed after matching, or
     * at PAL's rate not 2:2 film — deinterlaced by YADIF. Once a bank holds a cycle of five, a repeated frame is
     * dropped and the others are shown evenly over the five frames' time, starting now: the film's own 24 a second
     * from 30, about five frames behind, which the sound is told to wait. A cycle with no repeated frame — video,
     * not film, and anything at PAL's rate — keeps all five, so Film left on for ordinary video costs only the delay.
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
        val prevLuma = filmLumas[0]
        val restart = filmAfresh
        filmAfresh = false
        val topFirst = fieldOrder.topFieldFirst
        val generation = filmGeneration
        val prev = if (historyCount >= 3) history[0] else history[1]
        val cur = history[1]
        val next = history[2]
        val interval = frameIntervalNs
        filmPending = filmWorker.submit<FilmVerdict> {
            if (restart) filmCadence.reset()
            val started = System.nanoTime()
            val judged = filmCadence.add(prevLuma, curLuma, luma, topFirst, interval)
            val cycle = judged.cycle
            statistics?.let { stats ->
                stats.work(System.nanoTime() - started)
                if (cycle != null && cycle.drop >= 0) stats.dropped()
            }
            val verdict = FilmVerdict(judged, prev, cur, next, generation, width, height, interval)
            handler.post { busy { applyFilm(verdict) } }
            verdict
        }
    }

    /** One frame's Film verdict from the worker, with the frames it was judged on. */
    private class FilmVerdict(
        val judged: FilmCadence.Verdict,
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
        val judged = verdict.judged
        if (judged.combed) {
            val yadif = Deinterlacer.shaderMethod(Deinterlacer.Method.YADIF)
            rebuildField(slot, prev, cur, next, secondField = false, method = yadif)
        } else {
            val (other, rows) = when (judged.match) {
                Telecine.Match.PREVIOUS -> prev to judged.field
                Telecine.Match.NEXT -> next to judged.field
                Telecine.Match.BOTTOM -> prev to 1 - judged.field
                Telecine.Match.UPPER -> next to 1 - judged.field
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

        val cycle = judged.cycle
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
     * A fresh decoded frame joins the last three (the one before them kept as the first of the [older] ones, those
     * before it moving back), with when it was due; returns whether the history started afresh — after a seek, a stall
     * or a new size the frames before are no neighbours.
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
        // The oldest of the three moves on to the older ones, which hold one more frame where it held one.
        val moving = historyCount >= 3
        olderCount = olderCount.coerceAtMost(olderUsed)
        val oldest = older[olderUsed - 1]
        for (k in olderUsed - 1 downTo 1) older[k] = older[k - 1]
        older[0] = history[0]
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
        olderCount = if (afresh) 0 else if (moving) minOf(olderCount + 1, olderUsed) else olderCount
        historyCount = minOf(historyCount + 1, 4)
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
     * A packed-luma read-back on ES 3: two pixel buffers taking turns; each holds one frame's packed luma until the
     * next frame comes and collects it, long finished on the graphics chip by then.
     */
    private inner class LumaReader {
        private val buffers = IntArray(2)
        private val sizes = IntArray(2)
        private val pending = arrayOfNulls<PendingLuma>(2)
        private var slot = 0

        /**
         * Starts reading [from]'s packed luma into a pixel buffer without waiting, and returns the one started for
         * the frame before — with that frame's [id] — or null while there is none.
         */
        fun later(from: Frame, width: Int, height: Int, step: Int, id: Long): Pair<Long, Deinterlacer.Plane>? {
            val earlier = collect(1 - slot)
            if (buffers[0] == 0) GLES20.glGenBuffers(2, buffers, 0)
            val (columns, rows, texels) = packLuma(from, width, height, step, step)
            val size = texels * rows * 4
            val mine = slot
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, buffers[mine])
            if (sizes[mine] != size) {
                GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, size, null, GLES30.GL_STREAM_READ)
                sizes[mine] = size
            }
            GLES30.glReadPixels(0, 0, texels, rows, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, 0)
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            pending[mine] = PendingLuma(id, columns, rows, texels)
            slot = 1 - mine
            return earlier
        }

        /** Forgets the read-back waiting: the frame it holds is no neighbour of the next any more. */
        fun drop() {
            pending.fill(null)
        }

        fun release() {
            if (buffers[0] != 0) GLES20.glDeleteBuffers(2, buffers, 0)
        }

        // The read-back waiting in [at], unpacked, with its frame's id; null when none waits.
        private fun collect(at: Int): Pair<Long, Deinterlacer.Plane>? {
            val waiting = pending[at] ?: return null
            pending[at] = null
            val size = waiting.texels * waiting.rows * 4
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, buffers[at])
            val mapped = GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER, 0, size, GLES30.GL_MAP_READ_BIT)
                as? ByteBuffer
            val plane = mapped?.let {
                unpackLuma(it.order(ByteOrder.nativeOrder()), waiting.columns, waiting.rows, waiting.texels)
            }
            if (mapped != null) GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            return plane?.let { waiting.id to it }
        }
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
        val target = frames[REDUCED].ensure(size.width, size.height, from.half)
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
        val across = frames[REDUCED_ACROSS].ensure(size.width, from.height, from.half)
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
        var source = deblocked(picture, from, width, height)
        // Colour for a black-and-white picture after deblocking, as on the desktop, so the filters after it see it.
        if (picture.colorize != PictureAdjustment.Colorize.OFF) source = coloured(picture, source, width, height)
        // A station logo hidden after deblocking, so the filters after it see the picture without it.
        picture.logo?.let { logo ->
            val rect = logo.rectangle(width, height)
            if (rect.left + rect.width < width && rect.top + rect.height < height) {
                val masked = frames[LOGO_MASKED].ensure(width, height, deepInput(picture))
                restoration.logo(source, masked, logo, rect)
                source = masked
            }
        }
        picture.debandParams()?.let { deband ->
            val debanded = frames[DEBANDED].ensure(width, height, deepInput(picture))
            restoration.deband(source, debanded, deband, debandEs3)
            source = debanded
        }
        val kernel = picture.denoiseKernel()
        if (kernel != null) {
            val denoised = frames[1].ensure(width, height, deepInput(picture))
            restoration.denoise(source, denoised, kernel)
            source = denoised
        }
        return source
    }

    /**
     * Step 3's start: deblocking, then deringing, as far as [picture] asks for them, over [from] — on the decoded
     * picture's own block grid, so not where the picture was brought down to 1080p (its blocks no longer 8 pixels).
     */
    /**
     * [from] in colour by DDColor on the unit its self-test passed on (Colouring), once its first colour is in; [from]
     * itself before, without a passed test, or where the chip draws no half floats (the colour's own texture).
     */
    private fun coloured(picture: PictureAdjustment, from: Frame, width: Int, height: Int): Frame {
        val passed = colorizer ?: return from
        val context = appContext ?: return from
        if (!halfTargets) return from
        val unit = runCatching { NeuralUpscaler.Unit.valueOf(passed.unit) }.getOrNull() ?: return from
        val net = { NeuralColorizer.open(context, unit)?.let { Colouring.network(it) } }
        return colouring.colored(from, passed, net) { frames[COLOURED].ensure(width, height, deepInput(picture)) }
            as Frame
    }

    private fun deblocked(picture: PictureAdjustment, from: Frame, width: Int, height: Int): Frame {
        val deblock = picture.deblockParams()
        val dering = picture.deringParams()
        if ((deblock == null && dering == null) || !deblockReady) return from
        if (PictureSize(width, height) != eyeSize()) return from
        // Where the picture shown starts in the decoder's frame: the 3D eye, then the black bars cropped inside it.
        val video = PictureSize(
            videoWidth.takeIf { it > 0 } ?: surfaceWidth(),
            videoHeight.takeIf { it > 0 } ?: surfaceHeight(),
        )
        val stereo = filters.stereo
        val crop = activeCrop()
        val left = (video.width * stereo.x).toInt() + (crop?.left ?: 0)
        val top = ((1f - stereo.y - stereo.height) * video.height).toInt() + (crop?.top ?: 0)
        val (originX, originY) = Deblock.gridOrigin(left, top)
        var source = from
        val deep = deepInput(picture)
        if (deblock != null) {
            for (pass in 0..2) {
                val into = frames[if (pass % 2 == 0) DEBLOCK_A else DEBLOCK_B].ensure(width, height, deep)
                restoration.deblock(source, into, originX, originY, pass, deblock)
                source = into
            }
        }
        if (dering != null) {
            val blocksWide = (width - originX) / 8
            val blocksHigh = (height - originY) / 8
            if (blocksWide <= 0 || blocksHigh <= 0) return source
            val blocks = frames[CDEF_BLOCKS].ensure(blocksWide, blocksHigh)
            val into = frames[if (source === frames[DEBLOCK_A]) DEBLOCK_B else DEBLOCK_A].ensure(width, height, deep)
            restoration.dering(source, blocks, into, originX, originY, dering)
            source = into
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
        restoration.sharpen(source, target, picture.sharpness, picture.clarity)
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
            motionLuma.later(frames[keptRing[2]], width, height, pace.scale, keptAt[2]) ?: return
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

    /**
     * Temporal denoising's motion search: the newest decoded frame's luma read back at the search's scale (on ES 3
     * collected a frame later, as Motion's), searched against the frame before it on the search's thread, as Motion's
     * search (MotionInterpolation.Estimator); its vectors, and the noise level the later frame's luma shows
     * (TemporalDenoise.noiseSigma), come in for the frames denoised after. After a seek or a stall ([afresh]) the
     * frames before are no neighbours.
     */
    private fun searchDenoise(width: Int, height: Int, afresh: Boolean) {
        if (afresh) {
            denoisePrevious = null
            denoiseVectorsFor = 0L
            denoiseLuma.drop()
        }
        val pace = denoiseScale?.takeIf { it.width == width && it.height == height }
            ?: SearchScale(width, height).also { denoiseScale = it }
        val newest = frames[history[2]]
        val read = if (es3) {
            denoiseLuma.later(newest, width, height, pace.scale, historyAt[2]) ?: return
        } else {
            historyAt[2] to packedLuma(newest, width, height, pace.scale, pace.scale)
        }
        val (pair, luma) = read
        val previous = denoisePrevious
        denoisePrevious = read
        val prev = previous?.second ?: return
        if (prev.width != luma.width || prev.height != luma.height) return
        if (luma.width < 2 * MotionInterpolation.BLOCK || luma.height < 2 * MotionInterpolation.BLOCK) return
        val scale = width / luma.width
        motionSearch.execute {
            val started = System.nanoTime()
            val search = denoiseEstimator?.takeIf { it.width == luma.width && it.height == luma.height }
                ?: MotionInterpolation.Estimator(luma.width, luma.height).also { denoiseEstimator = it }
            search.advance()
            search.estimate(prev, luma)
            val took = System.nanoTime() - started
            val cut = denoiseCut.check(prev, luma)
            val noise = TemporalDenoise.noiseSigma(luma)
            val bytes = ByteArray(search.blocksWide * search.blocksHigh * 4)
            for (b in 0 until search.blocksWide * search.blocksHigh) {
                bytes[b * 4] = (search.vectors[b * 2] + VECTOR_ZERO).toByte()
                bytes[b * 4 + 1] = (search.vectors[b * 2 + 1] + VECTOR_ZERO).toByte()
            }
            val wide = search.blocksWide
            val high = search.blocksHigh
            handler.post {
                busy {
                    // Vectors for a pair no longer beside the frame shown are past, and the search too slow.
                    val late = pair !in historyAt
                    if (pace === denoiseScale && frameIntervalNs > 0) pace.report(took, frameIntervalNs, late)
                    if (late) return@post
                    EGL14.eglMakeCurrent(display, idle, idle, context)
                    GLES20.glDeleteTextures(1, intArrayOf(denoiseVectorTexture), 0)
                    denoiseVectorTexture = byteTexture(wide, high, bytes)
                    denoiseGrid[0] = wide
                    denoiseGrid[1] = high
                    denoiseGridScale = scale
                    denoiseFrame[0] = width
                    denoiseFrame[1] = height
                    denoiseVectorsCut = cut
                    denoiseVectorsFor = pair
                    noiseLevel.add(noise)
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
        val previousPicture = SampledTexture(previousTexture, width, height)
        val currentPicture = SampledTexture(currentTexture, width, height)
        if (alongMotion) {
            finishing.motion(
                previousPicture, currentPicture, vectorTextures[slot], obmcTexture, mixed, weight, gridWidth[slot],
                gridHeight[slot], gridScale[slot],
            )
        } else {
            finishing.blend(previousPicture, currentPicture, mixed, weight)
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

    /**
     * Steps 4 and 5: sharpness and clarity, and the picture onto the screen, enlarged as chosen — an mpv user shader's
     * hooks (see [userHooks]) on the picture before it is enlarged and on it enlarged.
     */
    private fun output(from: Frame, inWidth: Int, inHeight: Int, picture: PictureAdjustment) {
        var source = from
        grainNow = 0f
        grainToShow = filters.grain
        val outWidth = query(EGL14.EGL_WIDTH)
        val outHeight = query(EGL14.EGL_HEIGHT)
        val user = picture.shader?.let { MpvShaders.library[it] }
            ?.takeIf { MpvShaders.runnable(it) && halfTargets && es3 }
        if (user != null) {
            // Its LUMA hooks ran on the decoder's Y plane as the frame came in, where they could (lumaOnPlanes).
            val stages = if (lumaOnPlanes(user)) MpvShader.BEFORE_SCALING - "LUMA" else MpvShader.BEFORE_SCALING
            source = userHooks(user, source, stages, outWidth, outHeight)
        }
        val width = source.width
        val height = source.height
        val afterHooks = user != null && user.passes.any { p -> p.hooks.any { it in MpvShader.AFTER_SCALING } }
        // The colour correction, the panorama and the dithering are the last passes: the rest is drawn at the
        // screen's size into a frame of its own — in half floats where the picture is dithered; so too where the user
        // shader hooks it there.
        ditherNow = dithers(picture)
        val bend = warp
        val adjusting = !picture.isColorNeutral || ditherNow || !bend.isIdentity || afterHooks
        val upscaling = picture.upscales && outWidth > width
        if (!upscaling) {
            // 4: sharpness and clarity, drawn onto the screen at its size by its own bilinear scaling.
            restoration.sharpen(source, toScreen(outWidth, outHeight, adjusting), picture.sharpness, picture.clarity)
        } else {
            // 4: sharpness and clarity at the picture's own size, then 5: enlarged onto the screen.
            if (picture.sharpness > 0f || picture.clarity > 0f) {
                val sharpened = frames[3].ensure(width, height, deepInput(picture))
                restoration.sharpen(source, sharpened, picture.sharpness, picture.clarity)
                source = sharpened
            }
            // Where the enlarged picture is drawn: the screen, or the frame the last passes read.
            var screen: DrawTarget
            if (picture.upscaler == PictureAdjustment.Upscaler.LANCZOS) {
                screen = toScreen(outWidth, outHeight, adjusting)
                upscalers.lanczos(source, screen)
            } else if (picture.upscaler == PictureAdjustment.Upscaler.GSR) {
                // Snapdragon GSR: one pass straight to the screen's size, any ratio.
                screen = toScreen(outWidth, outHeight, adjusting)
                upscalers.gsr(source, screen)
            } else if (picture.upscaler == PictureAdjustment.Upscaler.NIS ||
                picture.upscaler == PictureAdjustment.Upscaler.EWA
            ) {
                // NVIDIA Image Scaling or EWA to at most twice the picture's size — as NIS allows, and EWA's 8 × 8 taps
                // stay affordable — and the screen's own bilinear scaling for whatever is left.
                val midWidth = Upscaling.twice(width, outWidth)
                val midHeight = Upscaling.twice(height, outHeight)
                val enlarged = frames[4].ensure(midWidth, midHeight, ditherNow)
                if (picture.upscaler == PictureAdjustment.Upscaler.NIS) {
                    upscalers.nis(source, enlarged)
                } else {
                    upscalers.ewa(source, enlarged)
                }
                screen = toScreen(outWidth, outHeight, adjusting)
                restoration.sharpen(enlarged, screen, 0f, 0f, 1f / midWidth, 1f / midHeight)
            } else if (picture.upscaler == PictureAdjustment.Upscaler.ANIME4K && halfTargets) {
                // Anime4K: four convolutions at the picture's size, then twice the size, and the screen's own scaling
                // for whatever is left.
                val enlarged = frames[4].ensure(width * 2, height * 2, ditherNow)
                upscalers.anime4k(source, net(ANIME4K_A, width, height), net(ANIME4K_B, width, height), enlarged)
                screen = toScreen(outWidth, outHeight, adjusting)
                restoration.sharpen(enlarged, screen, 0f, 0f, 0.5f / width, 0.5f / height)
            } else if (picture.upscaler == PictureAdjustment.Upscaler.ACNET && acnetReady()) {
                // ACNet: eleven convolutions at the picture's size over two pairs of half-float frames (Anime4K's two
                // and two more), then twice the size, and the screen's own scaling for whatever is left.
                val enlarged = frames[4].ensure(width * 2, height * 2, ditherNow)
                upscalers.acnet(
                    source, net(ANIME4K_A, width, height), net(ANIME4K_B, width, height),
                    net(ACNET_C, width, height), net(ACNET_D, width, height), enlarged,
                )
                screen = toScreen(outWidth, outHeight, adjusting)
                restoration.sharpen(enlarged, screen, 0f, 0f, 0.5f / width, 0.5f / height)
            } else if (picture.upscaler == PictureAdjustment.Upscaler.XBRZ && xbrzReady) {
                // xBRZ by the whole factor that reaches the screen's size, 2 to 6, the screen's own scaling then
                // bringing it the rest of the way.
                val factor = Upscaling.xbrzFactor(width, height, outWidth, outHeight)
                val corners = frames[XBRZ_CORNERS].ensure(width + 1, height + 1)
                val decisions = frames[XBRZ_DECISIONS].ensure(width, height)
                val enlarged = frames[4].ensure(width * factor, height * factor, ditherNow)
                upscalers.xbrz(source, corners, decisions, enlarged, factor)
                val texelX = 1f / (width * factor)
                val texelY = 1f / (height * factor)
                screen = toScreen(outWidth, outHeight, adjusting)
                restoration.sharpen(enlarged, screen, 0f, 0f, texelX, texelY)
            } else if (picture.upscaler.isEnlarger && enlargerNet(picture) != null) {
                // waifu2x, Real-CUGAN or RealSR: two, three or four times the picture's size (see enlargerPass), the
                // screen's own scaling for whatever is left.
                val enlarged = enlargerPass(source, width, height)
                val scale = enlarger!!.choice.scale
                screen = toScreen(outWidth, outHeight, adjusting)
                restoration.sharpen(
                    SampledTexture(enlarged, width * scale, height * scale), screen, 0f, 0f,
                    1f / (width * scale), 1f / (height * scale),
                )
            } else if (picture.upscaler == PictureAdjustment.Upscaler.NEURAL && es3 && neuralNet() != null) {
                // Real-ESRGAN: four times the picture's size, tile by tile (see neuralPass), and the screen's own
                // scaling for whatever is left.
                val enlarged = neuralPass(source, width, height)
                val scale = NeuralUpscaling.SCALE
                screen = toScreen(outWidth, outHeight, adjusting)
                restoration.sharpen(
                    SampledTexture(enlarged, width * scale, height * scale), screen, 0f, 0f,
                    1f / (width * scale), 1f / (height * scale),
                )
            } else {
                // FSR 1: edge-adaptive upscaling to the screen's size, then contrast-adaptive sharpening on it.
                val enlarged = frames[4].ensure(outWidth, outHeight, ditherNow)
                upscalers.easu(source, enlarged, fsrEs3)
                screen = toScreen(outWidth, outHeight, adjusting)
                upscalers.rcas(enlarged, screen, fsrEs3)
            }
            // Below full strength the plain picture, as the screen's own scaling gives it, is laid over the
            // upscaled one with that share: the graphics chip mixes the two as it draws.
            val plain = picture.plainShare
            if (plain > 0f) {
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendColor(0f, 0f, 0f, plain)
                GLES20.glBlendFunc(GLES20.GL_CONSTANT_ALPHA, GLES20.GL_ONE_MINUS_CONSTANT_ALPHA)
                restoration.sharpen(source, screen, 0f, 0f)
                GLES20.glDisable(GLES20.GL_BLEND)
            }
        }
        if (!adjusting) return
        if (afterHooks && user != null) {
            val hooked = userHooks(user, frames[ADJUSTED], MpvShader.AFTER_SCALING, outWidth, outHeight)
            finishing.copy(hooked, frames[ADJUSTED])
            if (picture.isColorNeutral && !ditherNow && bend.isIdentity) {
                finishing.copy(frames[ADJUSTED], WindowTarget(outWidth, outHeight))
                return
            }
        }
        var last = frames[ADJUSTED]
        if (!picture.isColorNeutral || !bend.isIdentity) {
            // The colour correction and the geometry: onto the screen, or into a half-float frame of its own for the
            // dithering.
            val into: DrawTarget = if (ditherNow) {
                frames[DITHER_INPUT].ensure(outWidth, outHeight, half = true).also { last = it }
            } else {
                WindowTarget(outWidth, outHeight)
            }
            finishing.adjust(frames[ADJUSTED], into, picture, bend)
        }
        // The window's top row is where gl_FragCoord.y is the height.
        if (ditherNow) finishing.dither(last, WindowTarget(outWidth, outHeight), ditherEs3)
    }

    // The mpv user shaders' frames by the name the passes save them under, and their programs by source; the frame
    // counter and random number the passes are given.
    private val mpvFrames = HashMap<String, Frame>()

    /**
     * [from] through [shader]'s passes hooking [stages], in mpv's order (see MpvShader) — the frames held the way mpv
     * holds its textures, the picture's top row first (turned round on the way in and out), in half floats; `LUMA` the
     * picture's Y, put back with its colour once its passes have run (glsl/video/mpv-luma.frag, mpv-recombine.frag).
     * [outWidth] × [outHeight] is the screen's size (`OUTPUT`, `target_size`). A pass whose `WHEN` does not hold, or
     * that reads a texture there is not, is left out; one that will not compile ends the shader there. As the desktop
     * stage's GlStage.userHooks, which is held to libplacebo running the same shaders.
     */
    private fun userHooks(shader: MpvShader, from: Frame, stages: List<String>, outWidth: Int, outHeight: Int): Frame =
        mpvHooks.run(shader, from, stages, MpvHooks.Sizes(outWidth, outHeight, videoWidth, videoHeight)) as Frame

    /**
     * A frame of the mpv user shaders' by [name], [w] × [h] half floats; where the chip runs compute shaders, held in
     * immutable storage, which OpenGL ES 3.1 needs of a compute pass's output image.
     */
    private fun mpvFrame(name: String, w: Int, h: Int) =
        mpvFrames.getOrPut(name) { Frame(gl) }.ensure(w, h, half = true, storage = es31)

    /**
     * The FFmpeg decoder's frame last taken through [shader]'s plane hooks, as mpv runs them and as GlStage does
     * (MpvHooks.planes): `LUMA` on the Y plane where [lumaOnPlanes], then `CHROMA` on the Cb and Cr planes as one
     * texture at their own size, its passes binding that `LUMA`, both rows from the top as mpv holds them, the stride's
     * padding cut off; then the Y plane and the hooked chroma — read bilinearly where the passes left it smaller — to
     * RGB into [into], its top row where v is 1; where no `CHROMA` pass ran, the chroma planes enlarged by [chroma]
     * instead, as mpv's `--cscale` (KrigBilateral guided by the hooked Y). The bound framebuffer and viewport are the
     * caller's to give back.
     */
    private fun chromaHooked(shader: MpvShader, chroma: PictureAdjustment.Chroma, into: Frame) {
        val chromaWidth = (yuvWidth + 1) / 2
        val chromaHeight = (yuvHeight + 1) / 2
        fun part(of: Float) = floatArrayOf(of, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        val luma = mpvFrame("mpvPlaneLuma", yuvWidth, yuvHeight)
        val plane = SampledTexture(yuvTextures[0], yuvWidth, yuvHeight)
        finishing.copy(plane, luma, part(yuvWidth.toFloat() / yuvStrides[0]))
        val chromaPart = part(chromaWidth.toFloat() / yuvStrides[1])
        val flip = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)
        // The FFmpeg decoder hands over limited range (a full-range source squeezed into it). The size shown is the
        // screen's, which a pass's WHEN may weigh the planes against.
        val sizes = MpvHooks.Sizes(query(EGL14.EGL_WIDTH), query(EGL14.EGL_HEIGHT), videoWidth, videoHeight)
        val upscaler = if (chroma == PictureAdjustment.Chroma.BILINEAR) {
            null
        } else {
            { y: DrawTarget, whole: DrawTarget ->
                // The hooked Y without the stride's padding beside the chroma planes with theirs.
                val planes = Conversion.Planes(
                    y.texture, yuvTextures[1], yuvTextures[2], y.width, y.height, y.width, yuvStrides[1], yuvColor,
                    16f / 255f, flip, chromaWidth.toFloat() / yuvStrides[1], chromaWidth, chromaHeight,
                )
                if (chroma == PictureAdjustment.Chroma.KRIG && krigReady) {
                    val first = frames[CHROMA_LOW].ensure(y.width, chromaHeight, half = true)
                    val second = frames[CHROMA_LOWER].ensure(chromaWidth, chromaHeight, half = true)
                    conversion.krig(planes, first, second, whole)
                } else {
                    conversion.spline(planes, whole)
                }
            }
        }
        mpvHooks.planes(
            shader, luma, yuvTextures[1], yuvTextures[2], chromaPart, sizes, lumaOnPlanes(shader), yuvColor,
            16f / 255f, flip, upscaler,
        ) { _, _ -> into }
    }

    /**
     * Whether [picture] is dithered to the screen's 8 bits as it goes on screen (see [Dither.applies]): where the chip
     * can hold the frames in half floats and the picture holds more — a deeper source, HDR tone-mapped, a colour
     * correction's or debanding's float results.
     */
    private fun dithers(picture: PictureAdjustment): Boolean {
        if (!ditherReady) return false
        val bits = if (hdr != null) HDR_BITS else if (yuvInput) 8 else sourceBits
        return Dither.applies(bits, SCREEN_BITS, picture)
    }

    /**
     * Whether the frames before the enlarging hold half floats: where [picture] is dithered, unless its upscaler reads
     * the picture back as bytes for a network on the processor (Real-ESRGAN, waifu2x, Real-CUGAN, RealSR).
     */
    private fun deepInput(picture: PictureAdjustment): Boolean {
        val readsBack = picture.upscales &&
            (picture.upscaler == PictureAdjustment.Upscaler.NEURAL || picture.upscaler.isEnlarger)
        return dithers(picture) && !readsBack
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
     * [picture]'s upscaler model (waifu2x, Real-CUGAN, RealSR) on the unit its self-test chose, opened here on first
     * use or when either changes; null where the device has not passed it.
     */
    private fun enlargerNet(picture: PictureAdjustment): NeuralEnlarger? {
        val choice = NeuralEnlarging.choice(picture)
        val unit = choice?.let { neuralEnlargers[it] }
            ?.let { runCatching { NeuralUpscaler.Unit.valueOf(it.unit) }.getOrNull() }
        if (choice == null || unit == null) {
            enlarger?.close()
            enlarger = null
            return null
        }
        if (enlarger?.unit != unit || enlarger?.choice != choice) {
            enlarger?.close()
            enlarger = appContext?.let { NeuralEnlarger.open(it, choice, unit) }
        }
        return enlarger
    }

    /**
     * The open upscaler over [source] ([width] × [height]): the picture read back, through the network, and its
     * output uploaded into an RGB texture twice the size. Returns that texture.
     */
    private fun enlargerPass(source: Frame, width: Int, height: Int): Int {
        val net = enlarger!!
        val scale = net.choice.scale
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
     * Draws the picture at the screen's size from here on: onto the screen, or with a colour correction set or the
     * picture to be dithered ([adjusting]) into the frame those last passes read (see output), in half floats for the
     * dithering.
     */
    private fun toScreen(width: Int, height: Int, adjusting: Boolean): DrawTarget {
        onWindow(width, height)
        if (!adjusting) return WindowTarget(width, height)
        return frames[ADJUSTED].ensure(width, height, ditherNow).also { it.bind() }
    }

    /** Frame [slot] as one of a network's half-float layers at [width] × [height] (Anime4K, ACNet). */
    private fun net(slot: Int, width: Int, height: Int): Frame = frames[slot].ensure(width, height, half = true)

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
         * to the next), two with Motion, one with a temporal deinterlacer, five with Film; 0 without such a filter.
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

        /** The picture drawn small, bars and all, for finding its black bars (see sampleForCrop). */
        private const val CROP_SAMPLE = HDR_BLOCKS + 1

        /** The colour-corrected picture at the screen's size in half floats, for the dithering (see output). */
        private const val DITHER_INPUT = CROP_SAMPLE + 1

        /** How often the histogram's sample is taken. */
        private const val SCREEN_SAMPLE_NS = 500_000_000L

        /** The screen's bits per value: the window's EGL configuration is RGBA8888. */
        private const val SCREEN_BITS = 8

        /** What an HDR picture tone-mapped in the stage holds: HDR10's and HLG's 10 bits, mapped in floats. */
        private const val HDR_BITS = 10

        /**
         * Chroma upscaling's frames: the luma at the chroma's height, then at its size (KrigBilateral's mean and
         * variance, half floats), and the FFmpeg decoder's frame as RGB with its chroma enlarged.
         */
        private const val CHROMA_LOW = DITHER_INPUT + 1
        private const val CHROMA_LOWER = CHROMA_LOW + 1
        private const val CHROMA_FULL = CHROMA_LOWER + 1

        /** The picture with its station logo hidden (see LogoMask). */
        private const val LOGO_MASKED = CHROMA_FULL + 1
        /** Deblocking's and deringing's frames, taking turns, and the blocks' directions and strengths. */
        private const val DEBLOCK_A = LOGO_MASKED + 1
        private const val DEBLOCK_B = DEBLOCK_A + 1
        private const val CDEF_BLOCKS = DEBLOCK_B + 1

        // The picture on screen taken small for the histogram (see onScreenSample).
        private const val SCREEN_SAMPLE = CDEF_BLOCKS + 1

        /** MC Smooth's neighbouring fields' BWDIF pictures, the averaged picture and the finished one. */
        private const val MC_EARLIER = SCREEN_SAMPLE + 1
        private const val MC_LATER = MC_EARLIER + 1
        private const val MC_SMOOTHED = MC_LATER + 1
        private const val MC_SHARPENED = MC_SMOOTHED + 1

        /** The frame shown, denoised against the frames before and after it (see TemporalDenoise). */
        private const val TEMPORAL_DENOISED = MC_SHARPENED + 1

        /** The frame shown with its dot crawl and rainbows combed out (see DotCrawl). */
        private const val DOT_CRAWL = TEMPORAL_DENOISED + 1

        /** The frames before the temporal history, taking turns with frames[8] to [10] (see older): OLDER of them. */
        private const val EARLIEST = DOT_CRAWL + 1

        /** How many frames before the temporal history are kept: back to the sixth before the one shown. */
        private const val OLDER = 5

        /** The picture in colour (see coloured). */
        private const val COLOURED = EARLIEST + OLDER

        /** 10-bit limited-range BT.2020 YUV to RGB, for the decoder's HDR frames read as YUV (copy-y2y-es3.frag). */
        private val BT2020_TEN_BIT = YuvMatrix.BT2020.toRgbTenBit()

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
    }
}
