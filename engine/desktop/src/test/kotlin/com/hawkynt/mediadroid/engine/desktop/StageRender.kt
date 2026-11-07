package com.hawkynt.mediadroid.engine.desktop

import com.hawkynt.mediadroid.player.Cdef
import com.hawkynt.mediadroid.player.Deband
import com.hawkynt.mediadroid.player.Deblock
import com.hawkynt.mediadroid.player.LogoMask
import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.player.PictureCrop
import com.hawkynt.mediadroid.player.TemporalDenoise
import com.hawkynt.mediadroid.stage.Conversion
import com.hawkynt.mediadroid.stage.Deinterlacing
import com.hawkynt.mediadroid.stage.Gles
import com.hawkynt.mediadroid.stage.Passes
import com.hawkynt.mediadroid.stage.Restoration
import com.hawkynt.mediadroid.stage.StageFrame
import com.hawkynt.mediadroid.stage.Upscaling
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL43.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.exitProcess

/**
 * The real picture stage (:stage's passes through LwjglGles, desktop OpenGL 4.3) as tools/shader-check runs the app's
 * shaders: oracle.sh hands it the commands that draw with the stage's own passes, and compares what it writes with the
 * originals. The commands and their arguments are shader_check's (tools/shader-check/check.cpp), which keeps the
 * test picture, the comparisons and what runs other people's shaders or works its uniforms out with libplacebo.
 * Pictures are 8-bit P6 PPMs; the stage holds them with the top row where v is 1, as both apps do.
 */
fun main(args: Array<String>) {
    val a = args.toList()
    StageRender().use { r ->
        val out = r.render(a) ?: run {
            System.err.println("usage: see tools/shader-check/check.cpp")
            exitProcess(2)
        }
        r.writePpm(File(out.first), out.second)
    }
}

/** One context with the stage's passes on it; [render] runs a command and hands back where its picture goes. */
private class StageRender : AutoCloseable {
    private val window: Long
    private var vao = 0
    private val gl = LwjglGles { vao }
    private val passes = Passes(gl)
    private val upscaling = Upscaling(passes)
    private val deinterlacing = Deinterlacing(passes)
    private val restoration = Restoration(gl, passes)
    private val conversion = Conversion(passes)

    /** A picture: its size and RGB bytes, rows from the top. */
    class Picture(val width: Int, val height: Int, val rgb: ByteArray)

    init {
        if (!System.getProperty("os.name").startsWith("Windows")) {
            GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_PLATFORM_X11)
        }
        check(GLFW.glfwInit()) { "no GLFW" }
        GLFW.glfwDefaultWindowHints()
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE)
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4)
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3)
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE)
        window = GLFW.glfwCreateWindow(16, 16, "shader check", 0, 0)
        check(window != 0L) { "no OpenGL 4.3 context" }
        GLFW.glfwMakeContextCurrent(window)
        GL.createCapabilities()
        vao = glGenVertexArrays()
        glBindVertexArray(vao)
        glBindBuffer(GL_ARRAY_BUFFER, glGenBuffers())
        glBufferData(GL_ARRAY_BUFFER, floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f),
            GL_STATIC_DRAW)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 16, 0)
        glEnableVertexAttribArray(1)
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 16, 8)
    }

    private fun frame(w: Int, h: Int, half: Boolean = false) = StageFrame(gl, Gles.RGBA8).ensure(w, h, half)

    // As the stage holds a picture: rows counted upwards, the top row where v is 1.
    private fun upload(p: Picture): StageFrame {
        val f = frame(p.width, p.height)
        val bytes = ByteBuffer.allocateDirect(p.width * p.height * 4).order(ByteOrder.nativeOrder())
        for (y in p.height - 1 downTo 0) for (x in 0 until p.width) {
            val i = (y * p.width + x) * 3
            bytes.put(p.rgb[i]).put(p.rgb[i + 1]).put(p.rgb[i + 2]).put(-1)
        }
        bytes.flip()
        glBindTexture(GL_TEXTURE_2D, f.texture)
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, p.width, p.height, GL_RGBA, GL_UNSIGNED_BYTE, bytes)
        return f
    }

    private fun download(f: StageFrame): Picture {
        val bytes = ByteBuffer.allocateDirect(f.width * f.height * 4).order(ByteOrder.nativeOrder())
        glBindFramebuffer(GL_FRAMEBUFFER, f.framebuffer)
        glPixelStorei(GL_PACK_ALIGNMENT, 1)
        glReadPixels(0, 0, f.width, f.height, GL_RGBA, GL_UNSIGNED_BYTE, bytes)
        val rgb = ByteArray(f.width * f.height * 3)
        for (y in 0 until f.height) for (x in 0 until f.width) {
            val from = ((f.height - 1 - y) * f.width + x) * 4
            val to = (y * f.width + x) * 3
            for (c in 0 until 3) rgb[to + c] = bytes.get(from + c)
        }
        return Picture(f.width, f.height, rgb)
    }

    /** What [a] (a shader_check command) draws and the file it goes to; null where it is no command of this tool's. */
    fun render(a: List<String>): Pair<String, Picture>? {
        fun i(k: Int) = a[k].toInt()
        fun f(k: Int) = a[k].toFloat()
        val es3 = System.getenv("SHADER_CHECK_FSR") == "es3"
        return when {
            a.size == 5 && a[0] == "gsr" -> a[2] to sized(a[1], i(3), i(4)) { s, o -> upscaling.gsr(s, o) }
            a.size == 5 && a[0] == "fsr" -> a[2] to sized(a[1], i(3), i(4)) { s, o ->
                upscaling.rcas(upscaling.easu(s, frame(o.width, o.height), es3), o, es3)
            }
            a.size == 5 && a[0] == "fsr-easu" -> a[2] to sized(a[1], i(3), i(4)) { s, o -> upscaling.easu(s, o, es3) }
            a.size == 3 && a[0] == "fsr-rcas" -> a[2] to same(a[1]) { s, o -> upscaling.rcas(s, o, es3) }
            a.size == 5 && a[0] == "nis" -> a[2] to sized(a[1], i(3), i(4)) { s, o -> upscaling.nis(s, o) }
            a.size == 6 && a[0] == "ewa" -> a[2] to sized(a[1], i(3), i(4)) { s, o -> upscaling.ewa(s, o, f(5)) }
            a.size == 6 && a[0] == "lanczos" ->
                a[2] to sized(a[1], i(3), i(4)) { s, o -> upscaling.lanczos(s, o, f(5)) }
            // The stage's own table of xBRZ's patterns (StageTables, from core/imaging's Xbrz), not the file's.
            a.size == 5 && a[0] == "xbrz" -> a[2] to one(a[1]) { s ->
                val k = i(3)
                val out = frame(s.width * k, s.height * k)
                upscaling.xbrz(s, frame(s.width + 1, s.height + 1), frame(s.width, s.height), out, k)
                out
            }
            a.size == 3 && a[0] == "anime4k" -> a[2] to one(a[1]) { s ->
                val out = frame(s.width * 2, s.height * 2)
                upscaling.anime4k(s, frame(s.width, s.height, true), frame(s.width, s.height, true), out)
                out
            }
            a.size == 3 && a[0] == "acnet" -> a[2] to one(a[1]) { s ->
                val out = frame(s.width * 2, s.height * 2)
                fun net() = frame(s.width, s.height, true)
                upscaling.acnet(s, net(), net(), net(), net(), out)
                out
            }
            a.size == 8 && a[0] == "temporal" -> a[4] to temporal(a)
            a.size == 5 && a[0] == "deinterlace" -> a[2] to same(a[1]) { s, o ->
                deinterlacing.spatial(s, o, PictureAdjustment.Deinterlace.entries[i(3)], topFirst = a[4] == "1")
            }
            a.size == 8 && a[0] == "logo" -> a[2] to same(a[1]) { s, o ->
                val mode = when (a[7]) {
                    "grey" -> LogoMask.Mode.GREY
                    "dim" -> LogoMask.Mode.DIM
                    else -> LogoMask.Mode.BLUR
                }
                restoration.logo(s, o, LogoMask(LogoMask.Corner.TOP_LEFT, 0.25f, mode), PictureCrop(i(3), i(4), i(5), i(6)))
            }
            a.size == 8 && a[0] == "deblock" -> a[2] to one(a[1]) { s ->
                val d = Deblock.Params(
                    if (a[3] == "strong") Deblock.Filter.STRONG else Deblock.Filter.WEAK, f(4), f(5), f(6), f(7),
                )
                var from = s
                for (step in 0..2) from = frame(s.width, s.height).also { restoration.deblock(from, it, 0, 0, step, d) }
                from
            }
            a.size == 6 && a[0] == "cdef" -> a[2] to same(a[1]) { s, o ->
                restoration.dering(s, frame(s.width / 8, s.height / 8), o, 0, 0, Cdef.Params(i(3), i(4), i(5)))
            }
            a.size == 7 && a[0] == "temporal-denoise" -> a[4] to temporalDenoise(a)
            a.size == 8 && a[0] == "deband" && System.getenv("SHADER_CHECK_DEBAND") != "top" -> a[2] to same(a[1]) { s, o ->
                val d = Deband.Params(i(3), f(4), f(5), f(6))
                restoration.deband(s, o, d, es3 = System.getenv("SHADER_CHECK_DEBAND") != "es1", frame = i(7))
            }
            a.size == 6 && a[0] == "chroma" -> a[5] to chroma(a[1], i(2), i(3), a[4])
            else -> null
        }
    }

    private fun one(path: String, run: (StageFrame) -> StageFrame) = download(run(upload(readPpm(File(path)))))

    private fun same(path: String, run: (StageFrame, StageFrame) -> Any) = one(path) { s ->
        frame(s.width, s.height).also { run(s, it) }
    }

    private fun sized(path: String, w: Int, h: Int, run: (StageFrame, StageFrame) -> Any) = one(path) { s ->
        frame(w, h).also { run(s, it) }
    }

    // The stage reads these exact pixels nearest; Deinterlacing.field does.
    private fun temporal(a: List<String>): Picture {
        val methods = listOf("yadif", "bwdif", "greedyh", "greedy2frame", "weave", "oldconsole", "tomsmocomp")
        val average = a[5] == "oldconsole-average"
        val method = methods.indexOf(if (average) "oldconsole" else a[5])
        require(method >= 0) { "temporal: no such method" }
        val (p, c, n) = (1..3).map { upload(readPpm(File(a[it]))) }
        val out = frame(c.width, c.height)
        deinterlacing.field(p, c, n, out, second = a[7] == "1", method = method, topFirst = a[6] == "1",
            average = average)
        return download(out)
    }

    // Read as decoded, no motion along: each side read as it is.
    private fun temporalDenoise(a: List<String>): Picture {
        val (p, c, n) = (1..3).map { upload(readPpm(File(a[it]))) }
        val out = frame(c.width, c.height)
        restoration.temporalDenoise(c, Restoration.Side(p, 0, 0f), Restoration.Side(n, 0, 0f), 0,
            TemporalDenoise.Params(a[5].toFloat(), a[6].toFloat()), out)
        return download(out)
    }

    // A full-range BT.709 yuv420p frame to RGB, its planes uploaded with a padded stride as a decoder's come (garbage
    // past the picture's width, which no pass may read).
    private fun chroma(path: String, w: Int, h: Int, filter: String): Picture {
        val data = File(path).readBytes()
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        require(data.size == w * h + 2 * cw * ch) { "not a yuv420p frame of that size: $path" }
        val pad = 16
        val strides = intArrayOf(w + pad, cw + pad, cw + pad)
        val widths = intArrayOf(w, cw, cw)
        val heights = intArrayOf(h, ch, ch)
        val offsets = intArrayOf(0, w * h, w * h + cw * ch)
        val tex = IntArray(3) { k ->
            val plane = ByteBuffer.allocateDirect(strides[k] * heights[k])
            for (y in 0 until heights[k]) for (x in 0 until strides[k]) {
                plane.put(if (x < widths[k]) data[offsets[k] + y * widths[k] + x] else -1)
            }
            plane.flip()
            glGenTextures().also {
                glBindTexture(GL_TEXTURE_2D, it)
                glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
                glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, strides[k], heights[k], 0, GL_RED, GL_UNSIGNED_BYTE, plane)
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            }
        }
        val kr = 0.2126
        val kb = 0.0722
        val kg = 1 - kr - kb
        val matrix = floatArrayOf(
            1f, 1f, 1f,
            0f, (-2 * (1 - kb) * kb / kg).toFloat(), (2 * (1 - kb)).toFloat(),
            (2 * (1 - kr)).toFloat(), (-2 * (1 - kr) * kr / kg).toFloat(), 0f,
        )
        val lumaPart = w.toFloat() / strides[0]
        val flip = floatArrayOf(lumaPart, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)
        val chromaScale = cw.toFloat() / strides[1] / lumaPart
        val planes = Conversion.Planes(tex[0], tex[1], tex[2], w, h, strides[0], strides[1], matrix, 0f, flip,
            chromaScale)
        val out = frame(w, h)
        when (filter) {
            "spline36" -> conversion.spline(planes, out)
            "krig" -> conversion.krig(planes, frame(w, ch, true), frame(cw, ch, true), out)
            else -> error("no such chroma filter: $filter")
        }
        return download(out)
    }

    fun readPpm(file: File): Picture {
        val bytes = file.readBytes()
        var at = 0
        fun token(): String {
            while (bytes[at].toInt().toChar().isWhitespace() || bytes[at] == '#'.code.toByte()) {
                if (bytes[at] == '#'.code.toByte()) while (bytes[at] != '\n'.code.toByte()) at++ else at++
            }
            val start = at
            while (!bytes[at].toInt().toChar().isWhitespace()) at++
            return String(bytes, start, at - start)
        }
        require(token() == "P6") { "not a P6 picture: $file" }
        val w = token().toInt()
        val h = token().toInt()
        require(token() == "255") { "not an 8-bit picture: $file" }
        at++
        return Picture(w, h, bytes.copyOfRange(at, at + w * h * 3))
    }

    fun writePpm(file: File, p: Picture) = file.writeBytes("P6\n${p.width} ${p.height}\n255\n".toByteArray() + p.rgb)

    override fun close() {
        GLFW.glfwDestroyWindow(window)
        GLFW.glfwTerminate()
    }
}
