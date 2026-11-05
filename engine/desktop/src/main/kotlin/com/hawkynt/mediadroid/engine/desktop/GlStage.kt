package com.hawkynt.mediadroid.engine.desktop

import com.hawkynt.mediadroid.imaging.Xbrz
import com.hawkynt.mediadroid.player.Deinterlacer
import com.hawkynt.mediadroid.player.Ewa
import com.hawkynt.mediadroid.player.Nis
import com.hawkynt.mediadroid.player.PeakDetect
import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.player.ToneMap
import com.hawkynt.mediadroid.shaders.Shaders
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL43.*
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.pow

/**
 * The picture stage on the desktop: the Android stage's shaders (:stage) on OpenGL 4.3 — which compiles GLSL ES 1.00
 * and 3.00 as they are — in a hidden window's context on a thread of its own. A decoded picture goes in, the picture
 * as the household set it comes out at the size it is shown: an HDR one tone-mapped to SDR as it comes in (ToneMap,
 * its peak measured as it plays: PeakDetect), deinterlaced (Auto, Blend, Bob, YADIF, BWDIF), debanded,
 * denoised, sharpened, enlarged (Lanczos, FSR, EWA, NIS, GSR, Anime4K, ACNet, xBRZ; the neural ones plain for now),
 * colour-corrected. The passes and their
 * uniforms are the Android stage's (VideoFilterStage), the shaders those tools/shader-check holds to their originals.
 */
class GlStage : AutoCloseable {

    private val thread = Executors.newSingleThreadExecutor { Thread(it, "mediadroid-stage").apply { isDaemon = true } }
    private var window = 0L
    private val programs = HashMap<String, Int>()
    private val targets = HashMap<String, Target>()
    private var vao = 0
    private var nisTable = 0
    private var ewaTable = 0
    private var xbrzTable = 0

    // Debanding's frame index, which seeds its random numbers: libplacebo's 8-bit counter.
    private var debandFrame = 0

    // HDR's peak as measured over the pictures so far, and the transfer it was measured for.
    private val peakDetect = PeakDetect()
    private var peakTransfer: ToneMap.Transfer? = null
    private var history = ArrayDeque<Target>()
    private var lastPicture: MediaReader.Picture? = null
    private var out: ByteBuffer? = null

    /** A texture with its framebuffer. */
    private class Target(val texture: Int, val framebuffer: Int, val width: Int, val height: Int)

    /** Whether the context came up; without it, pictures pass untouched. */
    @Volatile var ready = false
        private set

    /** The OpenGL driver's own name for what draws (GL_RENDERER): "llvmpipe (…)" for Mesa in software. */
    @Volatile var renderer = ""
        private set

    init {
        thread.submit { start() }.get()
    }

    private fun start() {
        // X11 on Linux (XWayland under Wayland); asked for on Windows, it makes glfwInit fail — GLFW picks Win32 there.
        if (!System.getProperty("os.name").startsWith("Windows")) {
            GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_PLATFORM_X11)
        }
        if (!GLFW.glfwInit()) return
        GLFW.glfwDefaultWindowHints()
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE)
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4)
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3)
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE)
        window = GLFW.glfwCreateWindow(16, 16, "MediaDroid stage", 0, 0)
        if (window == 0L) return
        GLFW.glfwMakeContextCurrent(window)
        GL.createCapabilities()
        renderer = org.lwjgl.opengl.GL11C.glGetString(org.lwjgl.opengl.GL11C.GL_RENDERER).orEmpty()
        vao = glGenVertexArrays()
        glBindVertexArray(vao)
        val quad = glGenBuffers()
        glBindBuffer(GL_ARRAY_BUFFER, quad)
        val corners = floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)
        glBufferData(GL_ARRAY_BUFFER, corners, GL_STATIC_DRAW)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 16, 0)
        glEnableVertexAttribArray(1)
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 16, 8)
        nisTable = nisTable()
        ewaTable = ewaTable()
        xbrzTable = xbrzTable()
        ready = true
    }

    /**
     * [picture] as [adjustment] has it, at [width] × [height]: RGBA rows from the top. A temporal deinterlacer shows
     * the picture before the newest (it needs the one after), so the result runs one picture behind.
     */
    fun process(
        picture: MediaReader.Picture,
        adjustment: PictureAdjustment,
        width: Int,
        height: Int,
    ): MediaReader.Picture =
        if (!ready) picture else thread.submit<MediaReader.Picture> { run(picture, adjustment, width, height) }.get()

    private fun run(picture: MediaReader.Picture, a: PictureAdjustment, outW: Int, outH: Int): MediaReader.Picture {
        val w = picture.width
        val h = picture.height
        if (picture !== lastPicture) {
            lastPicture = picture
            val slot = if (history.size >= 3) history.removeFirst() else null
            val target = slot?.takeIf { it.width == w && it.height == h } ?: target("history${history.size}$w", w, h)
            upload(target, picture)
            history.addLast(target)
        }
        var source = history.last()
        // 1. Deinterlacing.
        if (a.deinterlace.isTemporal && history.size >= 3) {
            val (prev, cur, next) = history.toList().takeLast(3)
            source = pass("temporal.frag", target("deinterlaced", w, h), cur.texture,
                listOf(prev.texture to "uPrev", next.texture to "uNext"), nearest = true) { p ->
                glUniform2f(glGetUniformLocation(p, "uSize"), w.toFloat(), h.toFloat())
                glUniform1i(glGetUniformLocation(p, "uSecond"), 0)
                glUniform1f(glGetUniformLocation(p, "uParity"), Deinterlacer.rebuiltParity(false, true).toFloat())
                val bwdif = a.deinterlace == PictureAdjustment.Deinterlace.BWDIF
                glUniform1i(glGetUniformLocation(p, "uBwdif"), if (bwdif) 1 else 0)
            }
        } else if (a.deinterlace != PictureAdjustment.Deinterlace.OFF && !a.deinterlace.isTemporal &&
            a.deinterlace != PictureAdjustment.Deinterlace.FILM
        ) {
            source = pass("deinterlace.frag", target("deinterlaced", w, h), source.texture) { p ->
                glUniform2f(glGetUniformLocation(p, "uTexel"), 1f / w, 1f / h)
                glUniform1i(glGetUniformLocation(p, "uMode"), a.deinterlace.ordinal)
                glUniform1f(glGetUniformLocation(p, "uHeight"), h.toFloat())
                glUniform1f(glGetUniformLocation(p, "uParity"), Deinterlacer.rebuiltParity(false, true).toFloat())
            }
        }
        // 2. Deband, denoise and sharpen at the picture's own size.
        a.debandParams()?.let { d ->
            debandFrame = (debandFrame + 1) and 0xFF
            source = pass("deband-es3.frag", target("debanded", w, h), source.texture) { p ->
                glUniform2f(glGetUniformLocation(p, "uSize"), w.toFloat(), h.toFloat())
                // The picture's top row is where v is 1 (see upload); libplacebo counts rows from the top.
                glUniform2f(glGetUniformLocation(p, "uRows"), h.toFloat(), -1f)
                glUniform1f(glGetUniformLocation(p, "uFrame"), debandFrame.toFloat())
                glUniform1i(glGetUniformLocation(p, "uIterations"), d.iterations)
                glUniform1f(glGetUniformLocation(p, "uThreshold"), d.threshold / 1000f)
                glUniform1f(glGetUniformLocation(p, "uRadius"), d.radius)
                glUniform1f(glGetUniformLocation(p, "uDebandGrain"), d.grain / 1000f)
            }
        }
        a.denoiseKernel()?.let { k ->
            source = pass("denoise.frag", target("denoised", w, h), source.texture) { p ->
                glUniform2f(glGetUniformLocation(p, "uTexel"), 1f / w, 1f / h)
                glUniform1f(glGetUniformLocation(p, "uRadius"), k.radius)
                glUniform1f(glGetUniformLocation(p, "uSigmaS2"), 2f * k.radius * k.radius)
                glUniform1f(glGetUniformLocation(p, "uSigmaR2"), 2f * k.similarity * k.similarity)
            }
        }
        if (a.sharpness > 0f || a.clarity > 0f) {
            source = pass("sharpen.frag", target("sharpened", w, h), source.texture) { p ->
                glUniform2f(glGetUniformLocation(p, "uTexel"), 1f / w, 1f / h)
                glUniform1f(glGetUniformLocation(p, "uSharpness"), a.sharpness)
                glUniform1f(glGetUniformLocation(p, "uClarity"), a.clarity)
            }
        }
        // 3. Enlarging to the shown size (at most twice where the upscaler is made for that, the rest bilinear).
        val upscaling = a.upscaler != PictureAdjustment.Upscaler.STANDARD && (outW > w || outH > h)
        if (upscaling) source = upscale(a.upscaler, source, outW, outH)
        // 4. To the shown size, colour-corrected.
        val shown = target("shown", outW, outH)
        val u = a.shaderUniforms()
        source = pass("adjust.frag", shown, source.texture) { p ->
            glUniform1f(glGetUniformLocation(p, "uBrightness"), u.brightness)
            glUniform1f(glGetUniformLocation(p, "uContrast"), u.contrast)
            glUniform1f(glGetUniformLocation(p, "uSaturation"), u.saturation)
            glUniform1f(glGetUniformLocation(p, "uInverseGamma"), u.inverseGamma)
        }
        return download(shown, picture.ptsMs)
    }

    private fun upscale(upscaler: PictureAdjustment.Upscaler, from: Target, outW: Int, outH: Int): Target {
        val w = from.width
        val h = from.height
        val inSize: (Int) -> Unit = { p -> glUniform2f(glGetUniformLocation(p, "uInSize"), w.toFloat(), h.toFloat()) }
        val midW = minOf(outW, w * 2)
        val midH = minOf(outH, h * 2)
        return when (upscaler) {
            PictureAdjustment.Upscaler.LANCZOS -> pass("lanczos.frag", target("up", outW, outH), from.texture) { p ->
                inSize(p)
                glUniform1f(glGetUniformLocation(p, "uAntiring"), ANTIRING)
            }
            PictureAdjustment.Upscaler.GSR -> pass("sgsr.frag", target("up", outW, outH), from.texture, body = inSize)
            PictureAdjustment.Upscaler.FSR -> {
                val easu = pass("fsr-easu-es3.frag", target("easu", outW, outH), from.texture, body = inSize)
                pass("fsr-rcas-es3.frag", target("up", outW, outH), easu.texture) { p ->
                    glUniform2f(glGetUniformLocation(p, "uTexel"), 1f / outW, 1f / outH)
                    glUniform1f(glGetUniformLocation(p, "uSharp"), 2f.pow(-0.2f))
                }
            }
            PictureAdjustment.Upscaler.EWA -> pass("ewa.frag", target("up", midW, midH), from.texture,
                listOf(ewaTable to "uTable")) { p ->
                inSize(p)
                glUniform1f(glGetUniformLocation(p, "uRadius"), Ewa.RADIUS.toFloat())
                glUniform1f(glGetUniformLocation(p, "uAntiring"), ANTIRING)
            }
            PictureAdjustment.Upscaler.NIS -> pass("nis.frag", target("up", midW, midH), from.texture,
                listOf(nisTable to "uCoef")) { p ->
                val c = Nis.Config(NIS_SHARPNESS)
                inSize(p)
                glUniform2f(glGetUniformLocation(p, "uOutSize"), midW.toFloat(), midH.toFloat())
                glUniform2f(glGetUniformLocation(p, "uScale"), w.toFloat() / midW, h.toFloat() / midH)
                fun set(name: String, v: Float) = glUniform1f(glGetUniformLocation(p, name), v)
                set("uDetectRatio", c.detectRatio)
                set("uDetectThres", c.detectThres)
                set("uMinContrastRatio", c.minContrastRatio)
                set("uRatioNorm", c.ratioNorm)
                set("uSharpStartY", c.sharpStartY)
                set("uSharpScaleY", c.sharpScaleY)
                set("uSharpStrengthMin", c.sharpStrengthMin)
                set("uSharpStrengthScale", c.sharpStrengthScale)
                set("uSharpLimitMin", c.sharpLimitMin)
                set("uSharpLimitScale", c.sharpLimitScale)
            }
            PictureAdjustment.Upscaler.ANIME4K -> anime4k(from)
            PictureAdjustment.Upscaler.ACNET -> acnet(from)
            PictureAdjustment.Upscaler.XBRZ -> xbrz(from, outW, outH)
            else -> from
        }
    }

    // Anime4K: four convolutions at the picture's size into half-float textures taking turns, then twice the size —
    // the picture's bilinear doubling plus the detail the network adds. As VideoFilterStage runs it.
    private fun anime4k(from: Target): Target {
        val w = from.width
        val h = from.height
        val texel: (Int) -> Unit = { p -> glUniform2f(glGetUniformLocation(p, "uTexel"), 1f / w, 1f / h) }
        var input = from
        ANIME4K.forEachIndexed { i, shader ->
            input = pass(shader, target(if (i % 2 == 0) "netA" else "netB", w, h, half = true), input.texture,
                body = texel)
        }
        return pass("anime4k/depth-to-space.frag", target("up", w * 2, h * 2), from.texture,
            listOf(input.texture to "uLast")) { p ->
            glUniform2f(glGetUniformLocation(p, "uLastSize"), w.toFloat(), h.toFloat())
        }
    }

    // ACNet: eleven convolutions over eight channels — a layer is two half-float textures, one pass each, the layers
    // taking turns between two pairs — then the network's luma put into the picture's bilinear doubling.
    private fun acnet(from: Target): Target {
        val w = from.width
        val h = from.height
        val pairs = arrayOf(arrayOf("netA", "netB"), arrayOf("netC", "netD"))
        ACNET.forEachIndexed { i, shader ->
            // The head (0) into the first pair, each body block (1 to 4) from one pair into the other, the last
            // convolution (5) into the second; every layer's two halves read the whole layer before.
            val layer = if (i < 2) 0 else (i - 2) / 2 + 1
            val into = pairs[layer % 2]
            val prior = pairs[1 - layer % 2]
            val input = if (layer == 0) from.texture else target(prior[0], w, h, half = true).texture
            val extra = buildList {
                if (layer > 0) add(target(prior[1], w, h, half = true).texture to "uTex1")
                if (i == ACNET.lastIndex) add(from.texture to "uPicture")
            }
            pass(shader, target(into[i % 2], w, h, half = true), input, extra) { p ->
                glUniform2f(glGetUniformLocation(p, "uTexel"), 1f / w, 1f / h)
            }
        }
        return pass("acnet/pixel-shuffle.frag", target("up", w * 2, h * 2), from.texture,
            listOf(target(pairs[1][0], w, h, half = true).texture to "uLast")) { p ->
            glUniform2f(glGetUniformLocation(p, "uLastSize"), w.toFloat(), h.toFloat())
        }
    }

    // xBRZ by the whole factor that reaches the shown size, 2 to 6: the corners where pixels meet, what each pixel
    // draws in its corners, then the enlarged picture — every texel read as it is.
    private fun xbrz(from: Target, outW: Int, outH: Int): Target {
        val w = from.width
        val h = from.height
        val factor = ceil(minOf(outW.toFloat() / w, outH.toFloat() / h)).toInt().coerceIn(Xbrz.FACTORS)
        val size: (Int) -> Unit = { p -> glUniform2f(glGetUniformLocation(p, "uSize"), w.toFloat(), h.toFloat()) }
        val corners = pass("xbrz/corners.frag", target("xbrzCorners", w + 1, h + 1), from.texture, nearest = true,
            body = size)
        val decisions = pass("xbrz/decisions.frag", target("xbrzDecisions", w, h), from.texture,
            listOf(corners.texture to "uCorners"), nearest = true, body = size)
        return pass("xbrz/scale.frag", target("up", w * factor, h * factor), from.texture,
            listOf(decisions.texture to "uDecisions", xbrzTable to "uTable"), nearest = true) { p ->
            size(p)
            glUniform1f(glGetUniformLocation(p, "uFactor"), factor.toFloat())
        }
    }

    private fun pass(
        shader: String,
        into: Target,
        input: Int,
        extra: List<Pair<Int, String>> = emptyList(),
        nearest: Boolean = false,
        body: (Int) -> Unit = {},
    ): Target {
        val p = program(shader)
        glBindFramebuffer(GL_FRAMEBUFFER, into.framebuffer)
        glViewport(0, 0, into.width, into.height)
        glUseProgram(p)
        val bound = listOf(input to "uTex") + extra
        bound.forEachIndexed { unit, (texture, name) ->
            glActiveTexture(GL_TEXTURE0 + unit)
            glBindTexture(GL_TEXTURE_2D, texture)
            if (unit == 0 || nearest) {
                val filter = if (nearest) GL_NEAREST else GL_LINEAR
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter)
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter)
            }
            glUniform1i(glGetUniformLocation(p, name), unit)
        }
        glUniformMatrix4fv(glGetUniformLocation(p, "uTexMatrix"), false, IDENTITY)
        glUniform1f(glGetUniformLocation(p, "uGrain"), 0f)
        body(p)
        glBindVertexArray(vao)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        if (nearest) bound.forEach { (texture, _) ->
            glBindTexture(GL_TEXTURE_2D, texture)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        }
        return into
    }

    private fun program(shader: String): Int = programs.getOrPut(shader) {
        val fragment = SHADERS.getValue(shader)
        val es3 = fragment.startsWith("#version 300 es")
        val vertex = if (es3) Shaders.VIDEO_VERTEX_ES3 else Shaders.VIDEO_VERTEX
        fun compile(type: Int, source: String): Int = glCreateShader(type).also {
            glShaderSource(it, if (source.startsWith("#version")) source else "#version 100\n$source")
            glCompileShader(it)
            check(glGetShaderi(it, GL_COMPILE_STATUS) != 0) { "$shader: " + glGetShaderInfoLog(it) }
        }
        glCreateProgram().also {
            glAttachShader(it, compile(GL_VERTEX_SHADER, vertex))
            glAttachShader(it, compile(GL_FRAGMENT_SHADER, fragment))
            glBindAttribLocation(it, 0, "aPosition")
            glBindAttribLocation(it, 1, "aTexCoord")
            glLinkProgram(it)
            check(glGetProgrami(it, GL_LINK_STATUS) != 0) { "$shader: " + glGetProgramInfoLog(it) }
        }
    }

    // [half]: 16-bit floats, for a network's layers, whose values reach past 0..1; [deep]: 16-bit values, for HDR's.
    private fun target(name: String, w: Int, h: Int, half: Boolean = false, deep: Boolean = false): Target {
        targets[name]?.let { if (it.width == w && it.height == h) return it else free(it) }
        val texture = glGenTextures()
        glBindTexture(GL_TEXTURE_2D, texture)
        if (half) {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, w, h, 0, GL_RGBA, GL_HALF_FLOAT, null as ByteBuffer?)
        } else if (deep) {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16, w, h, 0, GL_RGBA, GL_UNSIGNED_SHORT, null as ByteBuffer?)
        } else {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, null as ByteBuffer?)
        }
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        val framebuffer = glGenFramebuffers()
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
        return Target(texture, framebuffer, w, h).also { targets[name] = it }
    }

    private fun free(t: Target) {
        glDeleteFramebuffers(t.framebuffer)
        glDeleteTextures(t.texture)
    }

    // The stage holds its frames with the picture's top row where v is 1, as on Android: rows uploaded bottom first.
    // An HDR picture goes into a 16-bit frame of its own, its peak is measured, and it is tone-mapped into [into].
    private fun upload(into: Target, picture: MediaReader.Picture) {
        val w = picture.width
        val h = picture.height
        val transfer = picture.hdr
        val bytes = if (transfer == null) 4 else 8
        val buffer = MemoryUtil.memAlloc(w * h * bytes)
        for (y in 0 until h) buffer.put(picture.rgba, (h - 1 - y) * w * bytes, w * bytes)
        buffer.flip()
        val coded = if (transfer == null) into else target("hdr-coded", w, h, deep = true)
        glBindTexture(GL_TEXTURE_2D, coded.texture)
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        if (transfer == null) {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buffer)
        } else {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA, GL_UNSIGNED_SHORT, buffer.asShortBuffer())
        }
        MemoryUtil.memFree(buffer)
        if (transfer == null) return
        if (transfer != peakTransfer) {
            peakDetect.reset()
            peakTransfer = transfer
        }
        val side = PeakDetect.BLOCK
        val blocks = target("hdr-blocks", (w + side - 1) / side, (h + side - 1) / side)
        pass("peak.frag", blocks, coded.texture) { p ->
            toneUniforms(p, PeakDetect.pass(transfer))
            glUniform2f(glGetUniformLocation(p, "uSize"), w.toFloat(), h.toFloat())
        }
        val count = blocks.width * blocks.height
        val read = MemoryUtil.memAlloc(count * 4)
        glPixelStorei(GL_PACK_ALIGNMENT, 1)
        glReadPixels(0, 0, blocks.width, blocks.height, GL_RGBA, GL_UNSIGNED_BYTE, read)
        peakDetect.update(PeakDetect.unpack(ByteArray(count * 4).also { read.get(it) }, count))
        MemoryUtil.memFree(read)
        val tone = ToneMap.pass(transfer, peakDetect.peak)
        pass("tonemap.frag", into, coded.texture) { p -> toneUniforms(p, tone) }
    }

    /** Hands [pass]'s uniforms (ToneMap.Pass.uniforms) to [program]. */
    private fun toneUniforms(program: Int, pass: ToneMap.Pass) = pass.uniforms { name, v ->
        val at = glGetUniformLocation(program, name)
        when (v.size) {
            1 -> glUniform1f(at, v[0])
            2 -> glUniform2f(at, v[0], v[1])
            3 -> glUniform3f(at, v[0], v[1], v[2])
            4 -> glUniform4f(at, v[0], v[1], v[2], v[3])
            else -> glUniformMatrix3fv(at, false, v)
        }
    }

    private fun download(from: Target, ptsMs: Long): MediaReader.Picture {
        val w = from.width
        val h = from.height
        val buffer = out?.takeIf { it.capacity() >= w * h * 4 } ?: MemoryUtil.memAlloc(w * h * 4).also {
            out?.let(MemoryUtil::memFree)
            out = it
        }
        buffer.clear()
        glBindFramebuffer(GL_FRAMEBUFFER, from.framebuffer)
        glPixelStorei(GL_PACK_ALIGNMENT, 1)
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buffer)
        val rgba = ByteArray(w * h * 4)
        for (y in 0 until h) {
            buffer.position((h - 1 - y) * w * 4)
            buffer.get(rgba, y * w * 4, w * 4)
        }
        return MediaReader.Picture(ptsMs, w, h, rgba)
    }

    private fun nisTable(): Int {
        val bytes = MemoryUtil.memAlloc(6 * Nis.PHASES * 4)
        fun put(c: Float) {
            val v = (((c + 1f) / 2.5f) * 65535f + 0.5f).toInt().coerceIn(0, 65535)
            bytes.put((v shr 8).toByte()).put((v and 0xFF).toByte())
        }
        for (phase in 0 until Nis.PHASES) for (tap in 0 until 6) {
            put(Nis.scaleCoefficient(phase, tap))
            put(Nis.sharpenCoefficient(phase, tap))
        }
        bytes.flip()
        return table(6, Nis.PHASES, bytes)
    }

    private fun ewaTable(): Int {
        val size = 512
        val bytes = MemoryUtil.memAlloc(size * 4)
        for (w in Ewa.table(size)) {
            val v = (((w + 0.25f) / 1.25f) * 65535f).toInt().coerceIn(0, 65535)
            bytes.put((v shr 8).toByte()).put((v and 0xFF).toByte()).put(0).put(0)
        }
        bytes.flip()
        return table(size, 1, bytes)
    }

    // xBRZ's patterns (Xbrz.weights) as a XBRZ_TABLE_WIDTH × 25 texture: a row per factor from 2 and pattern, a texel
    // per cell of the block, its share of the neighbour's colour as m in red and n in green.
    private fun xbrzTable(): Int {
        val rows = Xbrz.FACTORS.count() * Xbrz.Pattern.entries.size
        val bytes = MemoryUtil.memAlloc(XBRZ_TABLE_WIDTH * rows * 4)
        for (factor in Xbrz.FACTORS) for (pattern in Xbrz.Pattern.entries) {
            val weights = Xbrz.weights(factor, pattern)
            for (cell in 0 until XBRZ_TABLE_WIDTH) {
                val m = if (cell < factor * factor) weights[cell * 2] else 0
                val n = if (cell < factor * factor) weights[cell * 2 + 1] else 1
                bytes.put(m.toByte()).put(n.toByte()).put(0).put(0)
            }
        }
        bytes.flip()
        return table(XBRZ_TABLE_WIDTH, rows, bytes)
    }

    private fun table(w: Int, h: Int, bytes: ByteBuffer): Int {
        val texture = glGenTextures()
        glBindTexture(GL_TEXTURE_2D, texture)
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, bytes)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        MemoryUtil.memFree(bytes)
        return texture
    }

    override fun close() {
        thread.submit {
            if (window != 0L) GLFW.glfwDestroyWindow(window)
            out?.let(MemoryUtil::memFree)
        }.get()
        thread.shutdown()
    }

    private companion object {
        val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        const val ANTIRING = 0.5f
        const val NIS_SHARPNESS = 0.5f
        const val XBRZ_TABLE_WIDTH = 36
        val ANIME4K = (1..4).map { "anime4k/conv-$it.frag" }
        val ACNET = (1..11).map { "acnet/conv-$it.frag" }
        val SHADERS = mapOf(
            "temporal.frag" to Shaders.VIDEO_TEMPORAL,
            "deinterlace.frag" to Shaders.VIDEO_DEINTERLACE,
            "deband-es3.frag" to Shaders.VIDEO_DEBAND_ES3,
            "tonemap.frag" to Shaders.VIDEO_TONEMAP,
            "peak.frag" to Shaders.VIDEO_PEAK,
            "denoise.frag" to Shaders.VIDEO_DENOISE,
            "sharpen.frag" to Shaders.VIDEO_SHARPEN,
            "adjust.frag" to Shaders.VIDEO_ADJUST,
            "lanczos.frag" to Shaders.VIDEO_LANCZOS,
            "sgsr.frag" to Shaders.VIDEO_SGSR,
            "fsr-easu-es3.frag" to Shaders.VIDEO_FSR_EASU_ES3,
            "fsr-rcas-es3.frag" to Shaders.VIDEO_FSR_RCAS_ES3,
            "ewa.frag" to Shaders.VIDEO_EWA,
            "nis.frag" to Shaders.VIDEO_NIS,
            "anime4k/conv-1.frag" to Shaders.ANIME4K_CONV_1,
            "anime4k/conv-2.frag" to Shaders.ANIME4K_CONV_2,
            "anime4k/conv-3.frag" to Shaders.ANIME4K_CONV_3,
            "anime4k/conv-4.frag" to Shaders.ANIME4K_CONV_4,
            "anime4k/depth-to-space.frag" to Shaders.ANIME4K_DEPTH_TO_SPACE,
            "acnet/conv-1.frag" to Shaders.ACNET_CONV_1,
            "acnet/conv-2.frag" to Shaders.ACNET_CONV_2,
            "acnet/conv-3.frag" to Shaders.ACNET_CONV_3,
            "acnet/conv-4.frag" to Shaders.ACNET_CONV_4,
            "acnet/conv-5.frag" to Shaders.ACNET_CONV_5,
            "acnet/conv-6.frag" to Shaders.ACNET_CONV_6,
            "acnet/conv-7.frag" to Shaders.ACNET_CONV_7,
            "acnet/conv-8.frag" to Shaders.ACNET_CONV_8,
            "acnet/conv-9.frag" to Shaders.ACNET_CONV_9,
            "acnet/conv-10.frag" to Shaders.ACNET_CONV_10,
            "acnet/conv-11.frag" to Shaders.ACNET_CONV_11,
            "acnet/pixel-shuffle.frag" to Shaders.ACNET_PIXEL_SHUFFLE,
            "xbrz/corners.frag" to Shaders.XBRZ_CORNERS,
            "xbrz/decisions.frag" to Shaders.XBRZ_DECISIONS,
            "xbrz/scale.frag" to Shaders.XBRZ_SCALE,
        )
    }
}
