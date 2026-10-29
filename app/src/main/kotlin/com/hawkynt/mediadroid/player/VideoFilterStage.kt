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
    private val frames = Array(8) { Frame() }

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
        }
        if (window == EGL14.EGL_NO_SURFACE) return
        val picture = filters
        if (picture.interpolation != PictureAdjustment.Interpolation.OFF) {
            if (!redraw) keep(picture)
            startBlending()
            return
        }
        latencyMs = 0
        val width = videoWidth.takeIf { it > 0 } ?: surfaceWidth()
        val height = videoHeight.takeIf { it > 0 } ?: surfaceHeight()
        val source = filtered(picture, width, height)
        output(source, width, height, picture)
        if (!redraw) EGLExt.eglPresentationTimeANDROID(display, window, surfaceTexture.timestamp)
        EGL14.eglSwapBuffers(display, window)
    }

    /** Steps 1 to 3: the decoder's picture, deinterlaced and denoised, at its own size. */
    private fun filtered(picture: PictureAdjustment, width: Int, height: Int): Frame {
        EGL14.eglMakeCurrent(display, idle, idle, context)
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
        if (picture.denoise > 0) {
            val denoised = frames[1].ensure(width, height)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, denoised.framebuffer)
            run(denoiseProgram, GLES20.GL_TEXTURE_2D, source.texture, identity) { p ->
                GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uTexel"), 1f / width, 1f / height)
                val (radius, sigmaR) = DENOISE_LEVELS[picture.denoise.coerceIn(1, DENOISE_LEVELS.size) - 1]
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
        val source = filtered(picture, width, height)
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
        if (kept == 2) latencyMs = ((currentAt - previousAt) / 2_000_000L).toInt().coerceIn(0, MAX_LATENCY_MS)
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
        uniforms(program)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val coordinate = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, QUAD)
        GLES20.glEnableVertexAttribArray(coordinate)
        GLES20.glVertexAttribPointer(coordinate, 2, GLES20.GL_FLOAT, false, 0, QUAD_TEX)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(coordinate)
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

        /** Denoise strengths: reach in pixels, and how different a neighbour may be and still count. */
        private val DENOISE_LEVELS = listOf(1f to 0.06f, 2f to 0.09f, 2f to 0.15f)

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
                gl_FragColor = vec4(mix(c, clamp(c, lo, hi), 0.5), 1.0);
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
                gl_FragColor = vec4(c, 1.0);
            }
        """

        // Unsharp masking on the luma: the picture plus its difference from a blurred copy. Sharpness
        // uses the eight nearest neighbours; clarity a ring six pixels out, which lifts texture and
        // local contrast rather than edges. Rec. 709 luma weights.
        private const val SHARPEN = """
            precision mediump float;
            uniform sampler2D uTex;
            uniform vec2 uTexel;
            uniform float uSharpness;
            uniform float uClarity;
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
                gl_FragColor = vec4(clamp(color + boost, 0.0, 1.0), 1.0);
            }
        """
    }
}
