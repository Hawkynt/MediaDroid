package com.hawkynt.mediadroid.desktop

import com.hawkynt.mediadroid.engine.desktop.FramePacing
import com.hawkynt.mediadroid.engine.desktop.GlStage
import com.hawkynt.mediadroid.engine.desktop.MediaReader
import com.hawkynt.mediadroid.engine.desktop.Moment
import com.hawkynt.mediadroid.engine.desktop.PictureWindow
import com.hawkynt.mediadroid.engine.desktop.Presentation
import com.hawkynt.mediadroid.player.PicturePlacement
import com.hawkynt.mediadroid.player.PictureAdjustment
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.BackendTexture
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Font
import org.jetbrains.skia.FramebufferFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWNativeWin32
import org.lwjgl.glfw.GLFWNativeX11
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL43C
import java.io.File

/**
 * How the stage's picture reaches a window, measured (#287; README "Platform support"): the stage draws a picture of
 * 1080p and of 4K as a 60 Hz clock asks for one — each a picture of its own, uploaded as the decoder's are — and a
 * candidate hands it to a window drawn by Skia on OpenGL, as Compose Desktop draws on Linux, with a control bar over
 * it, shown at the screen's refresh (swap interval 1). The candidates: the stage's read-backs (Presentation: SYNC, PBO,
 * PBO_LAGGED), each picture then put into a Skia bitmap and drawn as the player's PictureBitmap has Compose draw it;
 * SHARED, the stage's texture drawn by Skia straight from a context sharing the stage's (Image.adoptTextureFrom); and
 * NATIVE, the stage showing it in its own window (PictureWindow) put inside this one, a see-through window with the
 * control bar over both, as the player does (#378).
 * Per picture: what presenting cost — the stage's read-back or copy plus the window's taking and drawing it, flushed;
 * the stage's filters and the picture's upload are the same for all and left out — as mean, 95th percentile and worst;
 * and the pacing against the clock (FramePacing: dropped, doubled). A table on standard output and in
 * desktop/build/reports/presentation.md. `./gradlew :desktop:presentationBench [-Pframes=600]`. Not a test: it needs
 * a display (xvfb-run on a machine without), and what it measures is the machine it runs on. `-Poffscreen` keeps the
 * window hidden and draws into an off-screen Skia surface on the same graphics card, finished (glFinish) instead of
 * swapped and paced by the 60 Hz clock alone — for a display that presents nothing (a locked session, where a swap
 * waits for ever): the costs are the card's, the pacing then says nothing about the screen's refresh or tearing.
 */
fun main(args: Array<String>) {
    val frames = args.getOrNull(0)?.toIntOrNull() ?: 600
    val report = File(args.getOrNull(1) ?: "presentation.md")
    val offscreen = args.getOrNull(2) == "offscreen"
    if (!System.getProperty("os.name").startsWith("Windows")) {
        GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_PLATFORM_X11)
    }
    check(GLFW.glfwInit()) { "no display to open a window on" }
    val mode = checkNotNull(GLFW.glfwGetVideoMode(GLFW.glfwGetPrimaryMonitor()))
    GLFW.glfwDefaultWindowHints()
    GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4)
    GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3)
    GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE)
    GLFW.glfwWindowHint(GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE)
    GLFW.glfwWindowHint(GLFW.GLFW_STENCIL_BITS, 8)
    val window = GLFW.glfwCreateWindow(mode.width(), mode.height(), "MediaDroid presentation", 0, 0)
    check(window != 0L) { "no OpenGL 4.3 window" }
    GLFW.glfwMakeContextCurrent(window)
    GL.createCapabilities()
    GLFW.glfwSwapInterval(if (offscreen) 0 else 1)
    if (!offscreen) GLFW.glfwShowWindow(window)
    val renderer = GL43C.glGetString(GL43C.GL_RENDERER).orEmpty()
    val version = GL43C.glGetString(GL43C.GL_VERSION).orEmpty()
    val skia = DirectContext.makeGL()
    val bench = PresentationBench(window, skia, if (offscreen) mode.width() to mode.height() else null)
    val rows = mutableListOf<String>()
    for ((w, h) in listOf(1920 to 1080, 3840 to 2160)) {
        for (candidate in PresentationBench.Candidate.entries) {
            val row = bench.run(candidate, w, h, frames)
            println(row)
            rows += row
        }
    }
    bench.close()
    skia.close()
    GLFW.glfwDestroyWindow(window)
    GLFW.glfwTerminate()
    val table = buildString {
        val shownBy = if (offscreen) {
            "off screen (hidden window, glFinish, paced by the clock alone)"
        } else {
            "on screen at ${mode.refreshRate()} Hz, swap interval 1"
        }
        appendLine("Renderer: $renderer ($version); window ${mode.width()} × ${mode.height()} $shownBy;")
        appendLine("$frames pictures per run against a 60 Hz clock. Costs in ms per picture.")
        appendLine()
        appendLine("| Candidate | Picture | Mean | p95 | Max | Stage (all) mean | Dropped | Doubled |")
        appendLine("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |")
        rows.forEach { appendLine(it) }
    }
    report.absoluteFile.parentFile.mkdirs()
    report.writeText(table)
    println()
    print(table)
}

/** The window's side of the benchmark: its Skia surface, the pictures fed, a run per candidate. */
class PresentationBench(
    private val window: Long,
    private val skia: DirectContext,
    private val offscreen: Pair<Int, Int>?,
) : AutoCloseable {

    /**
     * How a candidate hands the stage's picture over: by a read-back, as the stage's own texture drawn by Skia, or
     * shown by the stage in a window of its own (NATIVE: PictureWindow inside this window, a see-through window with
     * the control bar over it, as the player does it — NativePicture).
     */
    enum class Candidate(val presentation: Presentation?) {
        SYNC(Presentation.SYNC),
        PBO(Presentation.PBO),
        PBO_LAGGED(Presentation.PBO_LAGGED),
        SHARED(null),
        NATIVE(Presentation.NATIVE),
    }

    private val period = 1_000_000_000L / 60
    private val bar = Paint().apply { color = 0xB0202020.toInt() }
    private val label = Paint().apply { color = 0xFFFFFFFF.toInt() }
    private val font = Font(null, 28f)

    /** One row of the table: [candidate] presenting [frames] pictures of [w] × [h], after a second's warm-up. */
    fun run(candidate: Candidate, w: Int, h: Int, frames: Int): String {
        if (candidate == Candidate.NATIVE) return runNative(w, h, frames)
        val sources = List(4) { pictureOf(w, h, it) }
        val stage = GlStage(candidate.presentation ?: Presentation.SYNC, shareWith = window)
        check(stage.ready) { "no stage" }
        val adopted = HashMap<Int, Image>()
        val bitmap = Bitmap()
        bitmap.allocPixels(ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL))
        val warm = 60
        val costs = LongArray(frames)
        val stageNanos = LongArray(frames)
        val content = IntArray(frames)
        val shown = LongArray(frames)
        var shownImage: Image? = null
        var last = -1
        var measured = 0
        val start = System.nanoTime()
        while (measured < frames) {
            val due = ((System.nanoTime() - start) / period).toInt()
            var cost = 0L
            var stageTook = 0L
            val fresh = due > last
            if (fresh) {
                last = due
                val raw = sources[due % sources.size]
                // A picture of its own each time, so the stage uploads it as it does a decoded one.
                val moment = Moment(MediaReader.Picture(due * 1000L / 60, w, h, raw))
                val began = System.nanoTime()
                if (candidate == Candidate.SHARED) {
                    val texture = checkNotNull(stage.processTexture(moment, PictureAdjustment.Neutral, w, h))
                    stageTook = System.nanoTime() - began
                    val drawing = System.nanoTime()
                    GL43C.glWaitSync(texture.fence, 0, GL43C.GL_TIMEOUT_IGNORED)
                    GL43C.glDeleteSync(texture.fence)
                    shownImage = adopted.getOrPut(texture.texture) {
                        Image.adoptTextureFrom(
                            skia,
                            BackendTexture.makeGL(w, h, false, texture.texture, GL43C.GL_TEXTURE_2D, GL43C.GL_RGBA8),
                            SurfaceOrigin.BOTTOM_LEFT,
                            ColorType.RGBA_8888,
                        )
                    }
                    draw(shownImage)
                    cost = stage.presentNanos + System.nanoTime() - drawing
                } else {
                    val picture = stage.process(moment, PictureAdjustment.Neutral, w, h)
                    stageTook = System.nanoTime() - began
                    val drawing = System.nanoTime()
                    // As PictureBitmap and Compose do: the bytes into the bitmap, the bitmap drawn as an image.
                    bitmap.installPixels(picture.rgba)
                    shownImage?.takeIf { candidate != Candidate.SHARED }?.close()
                    shownImage = Image.makeFromBitmap(bitmap)
                    draw(shownImage)
                    cost = stage.presentNanos + System.nanoTime() - drawing
                }
            } else {
                draw(shownImage)
            }
            if (offscreen == null) {
                GLFW.glfwSwapBuffers(window)
            } else {
                GL43C.glFinish()
            }
            GLFW.glfwPollEvents()
            val now = System.nanoTime()
            if (fresh && due >= warm) {
                costs[measured] = cost
                stageNanos[measured] = stageTook
                content[measured] = due
                shown[measured] = now
                measured++
            }
            if (offscreen != null) {
                // No refresh to wait for: on to the clock's next picture.
                val next = start + (((now - start) / period) + 1) * period
                val waitNanos = next - System.nanoTime()
                if (waitNanos > 0) Thread.sleep(waitNanos / 1_000_000, (waitNanos % 1_000_000).toInt())
            }
        }
        if (candidate != Candidate.SHARED) shownImage?.close()
        adopted.values.forEach { it.close() }
        bitmap.close()
        stage.close()
        val c = FramePacing.Costs.of(costs)
        val s = FramePacing.Costs.of(stageNanos)
        val pacing = FramePacing.of(content, shown, period)
        return "| ${candidate.name.lowercase().replace('_', '-')} | ${w}×$h | %.2f | %.2f | %.2f | %.2f | %d | %d |"
            .format(c.meanMs, c.p95Ms, c.maxMs, s.meanMs, pacing.dropped, pacing.doubled)
    }

    /**
     * NATIVE's row: the stage fed at the clock as for the others; its window — inside this one and under a see-through
     * window with the control bar, on screen; hidden and unsynchronised off screen — swaps on its own thread, which
     * reports each picture it showed. Cost: the stage's copy into the shared texture plus the window's drawing it, as
     * the CPU issued them (as SHARED's); pacing: the pictures the window swapped in, against the clock.
     */
    private fun runNative(w: Int, h: Int, frames: Int): String {
        val sources = List(4) { pictureOf(w, h, it) }
        val fw = IntArray(1)
        val fh = IntArray(1)
        if (offscreen != null) {
            fw[0] = offscreen.first
            fh[0] = offscreen.second
        } else {
            GLFW.glfwGetFramebufferSize(window, fw, fh)
        }
        // The see-through window first, while no other thread works with GLFW.
        val overlay = if (offscreen == null) overlayWindow(fw[0], fh[0]) else 0L
        val stage = GlStage(Presentation.NATIVE, shareWith = window)
        check(stage.ready) { "no stage" }
        val shown = checkNotNull(stage.openWindow(vsync = offscreen == null)) { "no picture window" }
        if (offscreen == null) {
            val parent = if (System.getProperty("os.name").startsWith("Windows")) {
                GLFWNativeWin32.glfwGetWin32Window(window)
            } else {
                GLFWNativeX11.glfwGetX11Window(window)
            }
            check(WindowEmbedding.embed(shown.handle, parent)) { "the picture window could not be embedded" }
        }
        shown.resize(fw[0], fh[0])
        if (offscreen == null) shown.show()
        val place = PicturePlacement.shown(fw[0], fh[0], 0, 0, w, h, 0, 0)
        shown.scene(PictureWindow.Scene(place, w, h))
        val warm = 60
        // Per picture fed: its time, what the stage took and what its copy took; per picture shown: its time, what
        // drawing it took, when its swap returned.
        val fed = java.util.concurrent.ConcurrentHashMap<Long, LongArray>()
        val presented = java.util.concurrent.ConcurrentLinkedQueue<LongArray>()
        shown.onPresented = { pts, drawNanos, at -> presented.add(longArrayOf(pts, drawNanos, at)) }
        var last = -1
        val start = System.nanoTime()
        while (last < warm + frames) {
            val due = ((System.nanoTime() - start) / period).toInt()
            if (due > last) {
                last = due
                val moment = Moment(MediaReader.Picture(due.toLong(), w, h, sources[due % sources.size]))
                val began = System.nanoTime()
                checkNotNull(stage.processTexture(moment, PictureAdjustment.Neutral, w, h))
                if (due >= warm) fed[due.toLong()] = longArrayOf(System.nanoTime() - began, stage.presentNanos)
            }
            val now = System.nanoTime()
            val next = start + (((now - start) / period) + 1) * period
            val waitNanos = next - System.nanoTime()
            if (waitNanos > 0) Thread.sleep(waitNanos / 1_000_000, (waitNanos % 1_000_000).toInt())
        }
        // The last pictures swapped in.
        Thread.sleep(200)
        shown.onPresented = null
        stage.close()
        if (overlay != 0L) GLFW.glfwDestroyWindow(overlay)
        val rows = presented.filter { fed.containsKey(it[0]) }
        val costs = rows.map { fed.getValue(it[0])[1] + it[1] }.toLongArray()
        val c = FramePacing.Costs.of(costs)
        val s = FramePacing.Costs.of(fed.values.map { it[0] }.toLongArray())
        val pacing = FramePacing.of(rows.map { it[0].toInt() }.toIntArray(), rows.map { it[2] }.toLongArray(), period)
        return "| native | ${w}×$h | %.2f | %.2f | %.2f | %.2f | %d | %d |"
            .format(c.meanMs, c.p95Ms, c.maxMs, s.meanMs, pacing.dropped, pacing.doubled)
    }

    /**
     * The see-through window over the picture, as the player's controls are: clear but for the least alpha and the
     * control bar, drawn once and left — what it costs is the compositor's blending, every refresh.
     */
    private fun overlayWindow(width: Int, height: Int): Long {
        val caps = GL.getCapabilities()
        GLFW.glfwDefaultWindowHints()
        GLFW.glfwWindowHint(GLFW.GLFW_TRANSPARENT_FRAMEBUFFER, GLFW.GLFW_TRUE)
        GLFW.glfwWindowHint(GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE)
        GLFW.glfwWindowHint(GLFW.GLFW_FLOATING, GLFW.GLFW_TRUE)
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE)
        val overlay = GLFW.glfwCreateWindow(width, height, "MediaDroid overlay", 0, 0)
        check(overlay != 0L) { "no see-through window" }
        val x = IntArray(1)
        val y = IntArray(1)
        GLFW.glfwGetWindowPos(window, x, y)
        GLFW.glfwSetWindowPos(overlay, x[0], y[0])
        GLFW.glfwMakeContextCurrent(overlay)
        GL.createCapabilities()
        GLFW.glfwSwapInterval(0)
        // Premultiplied, as compositors take it: black at alpha 1/255 everywhere, the bar at 0.69.
        GL43C.glClearColor(0f, 0f, 0f, 1f / 255)
        GL43C.glClear(GL43C.GL_COLOR_BUFFER_BIT)
        GL43C.glEnable(GL43C.GL_SCISSOR_TEST)
        GL43C.glScissor(24, 24, width - 48, 72)
        GL43C.glClearColor(0.086f, 0.086f, 0.086f, 0.69f)
        GL43C.glClear(GL43C.GL_COLOR_BUFFER_BIT)
        GL43C.glDisable(GL43C.GL_SCISSOR_TEST)
        GLFW.glfwShowWindow(overlay)
        GLFW.glfwSwapBuffers(overlay)
        GLFW.glfwMakeContextCurrent(window)
        GL.setCapabilities(caps)
        return overlay
    }

    private var surface: Surface? = null
    private var surfaceSize = 0 to 0

    /** [image] fitted into the window, a control bar with a time over it, flushed to the window's framebuffer. */
    private fun draw(image: Image?) {
        val fw = IntArray(1)
        val fh = IntArray(1)
        if (offscreen != null) {
            fw[0] = offscreen.first
            fh[0] = offscreen.second
        } else {
            GLFW.glfwGetFramebufferSize(window, fw, fh)
        }
        if (surface == null || surfaceSize != fw[0] to fh[0]) {
            surface?.close()
            surface = if (offscreen != null) {
                Surface.makeRenderTarget(skia, false, ImageInfo.makeN32Premul(fw[0], fh[0]))
            } else {
                val target = BackendRenderTarget.makeGL(fw[0], fh[0], 0, 8, 0, FramebufferFormat.GR_GL_RGBA8)
                Surface.makeFromBackendRenderTarget(
                    skia, target, SurfaceOrigin.BOTTOM_LEFT, SurfaceColorFormat.RGBA_8888, ColorSpace.sRGB,
                )
            }
            surfaceSize = fw[0] to fh[0]
        }
        val canvas = checkNotNull(surface).canvas
        canvas.clear(0xFF000000.toInt())
        if (image != null) {
            val scale = minOf(fw[0].toFloat() / image.width, fh[0].toFloat() / image.height)
            val dw = image.width * scale
            val dh = image.height * scale
            val left = (fw[0] - dw) / 2
            val top = (fh[0] - dh) / 2
            canvas.drawImageRect(image, Rect.makeXYWH(left, top, dw, dh))
        }
        val barTop = fh[0] - 96f
        canvas.drawRRect(RRect.makeXYWH(24f, barTop, fw[0] - 48f, 72f, 16f), bar)
        canvas.drawString("▶  00:12:34 / 01:45:00", 56f, barTop + 46f, font, label)
        skia.flush()
        skia.submit(false)
    }

    // A test picture: a gradient with a moving bar, different for each of the four, opaque.
    private fun pictureOf(w: Int, h: Int, k: Int): ByteArray {
        val rgba = ByteArray(w * h * 4)
        val barX = w * k / 4
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 4
            val inBar = x in barX until barX + w / 16
            rgba[i] = (if (inBar) 255 else x * 255 / w).toByte()
            rgba[i + 1] = (y * 255 / h).toByte()
            rgba[i + 2] = ((x + y + k * 64) and 0xFF).toByte()
            rgba[i + 3] = -1
        }
        return rgba
    }

    override fun close() {
        surface?.close()
        bar.close()
        label.close()
        font.close()
    }
}
