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
    private var denoiseProgram = 0
    private var sharpenProgram = 0
    private val frames = arrayOf(Frame(), Frame())

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
            GLES20.glDeleteProgram(denoiseProgram)
            GLES20.glDeleteProgram(sharpenProgram)
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
        denoiseProgram = program(VERTEX, DENOISE)
        sharpenProgram = program(VERTEX, SHARPEN)
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
        val width = videoWidth.takeIf { it > 0 } ?: surfaceWidth()
        val height = videoHeight.takeIf { it > 0 } ?: surfaceHeight()
        EGL14.eglMakeCurrent(display, idle, idle, context)

        // 1: the decoder's picture into a plain texture of its own size, upright.
        val copied = frames[0].ensure(width, height)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, copied.framebuffer)
        GLES20.glViewport(0, 0, width, height)
        run(copyProgram, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture, texMatrix) {}

        // 2: denoise, edge-preserving.
        var source = copied
        if (picture.denoise > 0) {
            val denoised = frames[1].ensure(width, height)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, denoised.framebuffer)
            run(denoiseProgram, GLES20.GL_TEXTURE_2D, copied.texture, identity) { p ->
                GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uTexel"), 1f / width, 1f / height)
                val (radius, sigmaR) = DENOISE_LEVELS[picture.denoise.coerceIn(1, DENOISE_LEVELS.size) - 1]
                GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uRadius"), radius)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uSigmaS2"), 2f * radius * radius)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uSigmaR2"), 2f * sigmaR * sigmaR)
            }
            source = denoised
        }

        // 3: sharpness and clarity, drawn onto the screen at its size.
        EGL14.eglMakeCurrent(display, window, window, context)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, query(EGL14.EGL_WIDTH), query(EGL14.EGL_HEIGHT))
        run(sharpenProgram, GLES20.GL_TEXTURE_2D, source.texture, identity) { p ->
            GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uTexel"), 1f / width, 1f / height)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uSharpness"), picture.sharpness)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(p, "uClarity"), picture.clarity)
        }
        if (!redraw) EGLExt.eglPresentationTimeANDROID(display, window, surfaceTexture.timestamp)
        EGL14.eglSwapBuffers(display, window)
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
        private var width = 0
        private var height = 0

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

    private companion object {
        /** Denoise strengths: reach in pixels, and how different a neighbour may be and still count. */
        val DENOISE_LEVELS = listOf(1f to 0.06f, 2f to 0.09f, 2f to 0.15f)

        val QUAD: FloatBuffer = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val QUAD_TEX: FloatBuffer = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        fun floats(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(values)
                position(0)
            }

        fun program(vertex: String, fragment: String): Int {
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

        const val VERTEX = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTex;
            void main() {
                gl_Position = aPosition;
                vTex = (uTexMatrix * aTexCoord).xy;
            }
        """

        const val COPY = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTex;
            varying vec2 vTex;
            void main() { gl_FragColor = vec4(texture2D(uTex, vTex).rgb, 1.0); }
        """

        // Bilateral filter (Tomasi & Manduchi, ICCV 1998): neighbours weigh by distance and by how alike
        // they are, so flat areas and block edges smooth while real edges stay.
        const val DENOISE = """
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

        // Unsharp masking on the luma: the picture plus its difference from a blurred copy. Sharpness
        // uses the eight nearest neighbours; clarity a ring six pixels out, which lifts texture and
        // local contrast rather than edges. Rec. 709 luma weights.
        const val SHARPEN = """
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
