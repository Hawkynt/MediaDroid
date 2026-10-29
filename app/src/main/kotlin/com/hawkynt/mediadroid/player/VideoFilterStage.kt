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
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
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
class VideoFilterStage {

    private val thread = HandlerThread("video-filters").apply { start() }
    private val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private lateinit var config: EGLConfig
    private var idle: EGLSurface = EGL14.EGL_NO_SURFACE
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var windowSurface: Surface? = null

    private var oesTexture = 0
    private lateinit var surfaceTexture: SurfaceTexture
    private val texMatrix = FloatArray(16)
    private val identity = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }

    private var copyProgram = 0
    private var deinterlaceProgram = 0
    private var denoiseProgram = 0
    private var sharpenProgram = 0
    private var lanczosProgram = 0
    private var easuProgram = 0
    private var rcasProgram = 0
    private var blendProgram = 0
    private var temporalProgram = 0
    private var weaveProgram = 0
    private var packProgram = 0
    private val frames = Array(PACKED_LUMA + 1) { Frame() }

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
    private val secondField = Runnable { drawSecondField() }

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
    private val filmLumas = arrayOfNulls<Deinterlacer.Plane>(3)
    private var filmBank = 0
    private var filmPosition = 0
    private var filmShown = -1
    private val filmToken = Any()

    // Frame blending: the last two filtered frames (frames[5] and frames[6], taking turns), when each
    // arrived, and whether the screen still has a blend to show.
    private var current = 5
    private var currentAt = 0L
    private var previousAt = 0L
    private var kept = 0
    private var blending = false
    private val vsync by lazy { android.view.Choreographer.getInstance() }
    private val onVsync = android.view.Choreographer.FrameCallback { blend(it) }

    /** Where the decoder draws. */
    lateinit var inputSurface: Surface
        private set

    /** The filters to run; read on every frame. */
    @Volatile
    var filters: PictureAdjustment = PictureAdjustment.Neutral

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

    fun release() {
        onThread {
            releaseWindow()
            GLES20.glDeleteProgram(copyProgram)
            GLES20.glDeleteProgram(deinterlaceProgram)
            GLES20.glDeleteProgram(denoiseProgram)
            GLES20.glDeleteProgram(sharpenProgram)
            GLES20.glDeleteProgram(lanczosProgram)
            GLES20.glDeleteProgram(easuProgram)
            GLES20.glDeleteProgram(rcasProgram)
            GLES20.glDeleteProgram(blendProgram)
            GLES20.glDeleteProgram(temporalProgram)
            GLES20.glDeleteProgram(weaveProgram)
            GLES20.glDeleteProgram(packProgram)
            filmWorker.shutdownNow()
            handler.removeCallbacksAndMessages(filmToken)
            handler.removeCallbacks(secondField)
            vsync.removeFrameCallback(onVsync)
            latencyMs = 0
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
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        EGL14.eglChooseConfig(
            display,
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            ),
            0, configs, 0, 1, count, 0,
        )
        config = configs[0] ?: error("No EGL configuration for the picture filters")
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
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
        surfaceTexture.setOnFrameAvailableListener({ draw(redraw = false) }, handler)
        inputSurface = Surface(surfaceTexture)

        copyProgram = program(VERTEX, COPY)
        deinterlaceProgram = program(VERTEX, DEINTERLACE)
        denoiseProgram = program(VERTEX, DENOISE)
        sharpenProgram = program(VERTEX, SHARPEN)
        lanczosProgram = program(VERTEX, LANCZOS)
        easuProgram = program(VERTEX, FSR_EASU)
        rcasProgram = program(VERTEX, FSR_RCAS)
        blendProgram = program(VERTEX, BLEND)
        temporalProgram = program(VERTEX, TEMPORAL)
        weaveProgram = program(VERTEX, WEAVE)
        packProgram = program(VERTEX, PACK_LUMA)
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
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(texMatrix)
            hasFrame = true
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
        val width = videoWidth.takeIf { it > 0 } ?: surfaceWidth()
        val height = videoHeight.takeIf { it > 0 } ?: surfaceHeight()
        val source = filtered(picture, width, height, fresh = !redraw)
        latencyMs = deinterlaceLatencyMs
        output(source, width, height, picture)
        if (!redraw) EGLExt.eglPresentationTimeANDROID(display, window, shownAt)
        EGL14.eglSwapBuffers(display, window)
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
        val width = videoWidth.takeIf { it > 0 } ?: surfaceWidth()
        val height = videoHeight.takeIf { it > 0 } ?: surfaceHeight()
        val source = filtered(picture, width, height, fresh = false, secondField = true)
        output(source, width, height, picture)
        EGL14.eglSwapBuffers(display, window)
    }

    /**
     * Steps 1 to 3: the decoder's picture, deinterlaced and denoised, at its own size. [fresh] is a new
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
        shownAt = surfaceTexture.timestamp
        val copied = frames[0].ensure(width, height)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, copied.framebuffer)
        GLES20.glViewport(0, 0, width, height)
        run(copyProgram, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture, texMatrix) {}
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
        return denoised(picture, source, width, height)
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
        val woven = frames[2].ensure(width, height)
        rebuildField(woven, prev, cur, next, secondField, picture.deinterlace == PictureAdjustment.Deinterlace.BWDIF)
        return denoised(picture, woven, width, height)
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
        val width = videoWidth.takeIf { it > 0 } ?: surfaceWidth()
        val height = videoHeight.takeIf { it > 0 } ?: surfaceHeight()
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
        val prevLuma = filmLumas[0] ?: filmLumas[1]!!
        val curLuma = filmLumas[1]!!
        val topFirst = fieldOrder.topFieldFirst
        val generation = filmGeneration
        val prev = if (historyCount >= 3) history[0] else history[1]
        val cur = history[1]
        val next = history[2]
        val interval = frameIntervalNs
        filmPending = filmWorker.submit<FilmVerdict> {
            if (afresh || fieldMatch.field != (if (topFirst) 1 else 0)) {
                fieldMatch = Telecine.FieldMatch(topFirst)
                if (afresh) decimate = Telecine.Decimate(mixed = true, duplicatePercent = FILM_DUPLICATE_PERCENT)
            }
            val (matched, woven) = fieldMatch.add(prevLuma, curLuma, luma)
            val verdict = FilmVerdict(
                matched, decimate.add(woven), fieldMatch.field, prev, cur, next, generation, width, height, interval,
            )
            handler.post { applyFilm(verdict) }
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
            handler.postAtTime({ showFilm(base + position) }, filmToken, start + j * stepNs / 1_000_000L)
        }
        latencyMs = (verdict.intervalNs * Telecine.CYCLE / 1_000_000L).toInt().coerceIn(0, MAX_FILM_LATENCY_MS)
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
        val source = denoised(picture, frame, frame.width, frame.height)
        output(source, frame.width, frame.height, picture)
        EGL14.eglSwapBuffers(display, window)
    }

    /**
     * A fresh decoded frame joins the last three, with when it was due; returns whether the history
     * started afresh — after a seek, a stall or a new size the frames before are no neighbours.
     * Leaves the newest frame's framebuffer bound.
     */
    private fun remember(width: Int, height: Int): Boolean {
        val step = surfaceTexture.timestamp - historyAt[2]
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
        historyAt[2] = surfaceTexture.timestamp
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
        run(copyProgram, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture, texMatrix) {}
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
        val size = texels * rows * 4
        val bytes = readBack?.takeIf { it.capacity() == size }
            ?: ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder()).also { readBack = it }
        bytes.rewind()
        GLES20.glReadPixels(0, 0, texels, rows, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
        // The pass wrote row v at framebuffer row v, so the rows come back from the top.
        val luma = IntArray(columns * rows)
        for (y in 0 until rows) {
            val line = y * texels * 4
            for (x in 0 until columns) luma[y * columns + x] = bytes.get(line + x).toInt() and 0xFF
        }
        return Deinterlacer.Plane(columns, rows, luma)
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
        val width = videoWidth.takeIf { it > 0 } ?: surfaceWidth()
        val height = videoHeight.takeIf { it > 0 } ?: surfaceHeight()
        val source = filtered(picture, width, height, fresh = true)
        current = if (current == 5) 6 else 5
        val target = frames[current].ensure(width, height)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.framebuffer)
        run(sharpenProgram, GLES20.GL_TEXTURE_2D, source.texture, identity) { p ->
            GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uTexel"), 1f / width, 1f / height)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uSharpness"), picture.sharpness)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uClarity"), picture.clarity)
        }
        previousAt = currentAt
        currentAt = System.nanoTime()
        kept = minOf(kept + 1, 2)
        // The picture runs half a frame behind; the sound is told to wait as long.
        if (kept == 2) {
            latencyMs = ((currentAt - previousAt) / 2_000_000L).toInt().coerceIn(0, MAX_LATENCY_MS) +
                deinterlaceLatencyMs
        }
    }

    private fun startBlending() {
        if (blending) return
        blending = true
        vsync.postFrameCallback(onVsync)
    }

    /**
     * One screen refresh while blending: the previous and the current frame mixed by how far the clock
     * is between them (see [PictureAdjustment.Interpolation.weight]), shown one frame late. Stops once
     * the current frame shows whole, until the next arrives.
     */
    private fun blend(frameTimeNanos: Long) {
        blending = false
        val picture = filters
        if (window == EGL14.EGL_NO_SURFACE || kept == 0 ||
            picture.interpolation == PictureAdjustment.Interpolation.OFF
        ) {
            return
        }
        val width = frames[current].width
        val height = frames[current].height
        val interval = (currentAt - previousAt).takeIf { kept == 2 && it > 0 } ?: 1L
        val phase = (frameTimeNanos - currentAt).toFloat() / interval
        val weight = if (kept < 2) 1f else picture.interpolation.weight(phase)
        EGL14.eglMakeCurrent(display, idle, idle, context)
        val mixed = frames[7].ensure(width, height)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mixed.framebuffer)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(blendProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frames[if (current == 5) 6 else 5].texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(blendProgram, "uPrevious"), 1)
        run(blendProgram, GLES20.GL_TEXTURE_2D, frames[current].texture, identity) { p ->
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uWeight"), weight)
        }
        output(mixed, width, height, picture.copy(sharpness = 0f, clarity = 0f))
        EGL14.eglSwapBuffers(display, window)
        if (weight < 1f) startBlending()
    }

    /** Steps 4 and 5: sharpness and clarity, and the picture onto the screen, enlarged as chosen. */
    private fun output(from: Frame, width: Int, height: Int, picture: PictureAdjustment) {
        var source = from
        grainNow = 0f
        grainToShow = filters.grain
        val outWidth = query(EGL14.EGL_WIDTH)
        val outHeight = query(EGL14.EGL_HEIGHT)
        val upscaling = picture.upscaler != PictureAdjustment.Upscaler.STANDARD && outWidth > width
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
        }
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
         * How far the picture runs behind while frames blend, for the sound to wait as long: half a frame,
         * as the change shows half-way to the next frame. 0 without blending.
         */
        @Volatile
        var latencyMs = 0
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

        // Lanczos-3 (a = 3), 6 × 6 taps around the position; the result is pulled halfway back into the range
        // of the four nearest pixels, which holds back most of the ringing Lanczos leaves at hard edges.
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
