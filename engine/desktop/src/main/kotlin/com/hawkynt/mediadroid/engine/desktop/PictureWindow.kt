package com.hawkynt.mediadroid.engine.desktop

import com.hawkynt.mediadroid.player.AssPicture
import com.hawkynt.mediadroid.shaders.Shaders
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWNativeWin32
import org.lwjgl.glfw.GLFWNativeX11
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL43C.*
import org.lwjgl.system.MemoryUtil
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The stage's picture shown by the stage itself (Presentation.NATIVE): a window of its own whose OpenGL context shares
 * the stage's textures, drawn and swapped at the screen's refresh on a thread of its own — nothing read back, nothing
 * copied through memory. The stage writes each picture into one of [SLOTS] textures (PresentQueue) and fences it; this
 * window waits on that fence on the graphics card, draws the newest at the [Scene]'s place with libass's subtitles over
 * it, and fences what it drew so the stage never writes into a texture still being read. Opened by GlStage.openWindow;
 * the desktop puts its [handle] (an X11 window, a Win32 HWND) inside the player's window and the controls over it.
 */
class PictureWindow internal constructor(shareWith: Long, private val vsync: Boolean) : AutoCloseable {

    /** What the window shows around the picture: where it goes, and the subtitles over it. */
    class Scene(
        /** The picture's rectangle in the window's pixels: left, top, width, height (PicturePlacement). */
        val place: IntArray,
        /** The size the subtitles were laid out for: the stage's picture. */
        val pictureWidth: Int,
        val pictureHeight: Int,
        /** libass's subtitles, each at its place in that picture. */
        val subtitles: List<AssPicture> = emptyList(),
    )

    private val thread = Executors.newSingleThreadExecutor {
        Thread(it, "mediadroid-picture").apply { isDaemon = true }
    }
    private var window = 0L
    private var program = 0
    private var vao = 0
    private var subtitleTexture = 0

    /** Which texture the window shows and which the stage writes next. */
    internal val queue = PresentQueue(SLOTS)

    // Per slot: the stage's fence for the picture in it (waited on before drawing), and the fence for this window's
    // last draw from it (waited on by the stage before writing into it again). Both swapped atomically between threads.
    private val written = AtomicLongArray(SLOTS)
    private val read = AtomicLongArray(SLOTS)
    private val pictures = arrayOfNulls<GlStage.SharedPicture>(SLOTS)

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var dirty = false
    private var scene: Scene? = null
    private var size: Pair<Int, Int>? = null
    private var showAsked = false

    @Volatile private var open = true

    /** The window system's handle: the X11 window on Linux, the HWND on Windows; 0 when no window came up. */
    @Volatile var handle = 0L
        private set

    /** Whether the window and its context came up. */
    @Volatile var ready = false
        private set

    /**
     * Called on the window's thread after each picture is swapped: the picture's time, what drawing it took in
     * nanoseconds, and System.nanoTime() once the swap returned (with vsync: when the screen took it).
     */
    @Volatile var onPresented: ((ptsMs: Long, drawNanos: Long, swappedAt: Long) -> Unit)? = null

    /** For the tests: what each draw left in the window before the swap — RGBA, rows from the bottom, its size. */
    @Volatile internal var onDrawn: ((rgba: ByteArray, width: Int, height: Int) -> Unit)? = null

    init {
        thread.submit { start(shareWith) }.get()
        if (ready) thread.submit(::loop)
    }

    private fun start(shareWith: Long) {
        GLFW.glfwDefaultWindowHints()
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE)
        GLFW.glfwWindowHint(GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE)
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE)
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4)
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3)
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE)
        window = GLFW.glfwCreateWindow(16, 16, "MediaDroid picture", 0, shareWith)
        if (window == 0L) return
        GLFW.glfwMakeContextCurrent(window)
        GL.createCapabilities()
        GLFW.glfwSwapInterval(if (vsync) 1 else 0)
        program = program()
        vao = glGenVertexArrays()
        handle = if (WIN32) {
            GLFWNativeWin32.glfwGetWin32Window(window)
        } else {
            GLFWNativeX11.glfwGetX11Window(window)
        }
        ready = true
    }

    /** The window [width] × [height] pixels from now on. */
    fun resize(width: Int, height: Int) = change { size = width to height }

    /** The window mapped: shown inside whatever it was put into. */
    fun show() = change { showAsked = true }

    /** Where the picture goes and the subtitles over it, from now on; null draws black. */
    fun scene(scene: Scene?) = change { this.scene = scene }

    private fun change(what: () -> Unit) = lock.withLock {
        what()
        dirty = true
        changed.signal()
    }

    /** On the stage's thread, before it writes into [slot]: the graphics card waits until this window drew from it. */
    internal fun beforeWrite(slot: Int) {
        val fence = read.getAndSet(slot, 0L)
        if (fence != 0L) {
            glWaitSync(fence, 0, GL_TIMEOUT_IGNORED)
            glDeleteSync(fence)
        }
    }

    /** On the stage's thread: [picture] written into its slot and fenced, the picture this window shows next. */
    internal fun post(picture: GlStage.SharedPicture) {
        pictures[picture.slot] = picture
        val older = written.getAndSet(picture.slot, picture.fence)
        if (older != 0L) glDeleteSync(older)
        queue.post(picture.slot)
        change { }
    }

    private fun loop() {
        while (open) {
            // Win32 wants the thread that made a window to pump its messages, a child of another thread's window most
            // of all. X11 does not, and GLFW's event processing is not to run beside another thread making a window
            // (a dry run's stage): the window, under the controls, gets no input, its few events wait in the queue.
            if (WIN32) GLFW.glfwPollEvents()
            var resizeTo: Pair<Int, Int>? = null
            var mapIt = false
            var drawing: Scene? = null
            val news = lock.withLock {
                if (!dirty) changed.await(WAIT_MS, TimeUnit.MILLISECONDS)
                resizeTo = size
                mapIt = showAsked
                drawing = scene
                size = null
                showAsked = false
                dirty.also { dirty = false }
            }
            if (!open) break
            if (!news) continue
            resizeTo?.let { (w, h) -> GLFW.glfwSetWindowSize(window, w.coerceAtLeast(1), h.coerceAtLeast(1)) }
            if (mapIt) GLFW.glfwShowWindow(window)
            draw(queue.take() ?: queue.shown, drawing)
        }
    }

    private fun draw(slot: Int?, scene: Scene?) {
        val began = System.nanoTime()
        val fw = IntArray(1)
        val fh = IntArray(1)
        GLFW.glfwGetFramebufferSize(window, fw, fh)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, fw[0], fh[0])
        glDisable(GL_BLEND)
        glClearColor(0f, 0f, 0f, 1f)
        glClear(GL_COLOR_BUFFER_BIT)
        val picture = slot?.let { pictures[it] }
        if (picture != null && scene != null) {
            val fence = written.getAndSet(picture.slot, 0L)
            if (fence != 0L) {
                glWaitSync(fence, 0, GL_TIMEOUT_IGNORED)
                glDeleteSync(fence)
            }
            glUseProgram(program)
            glBindVertexArray(vao)
            glActiveTexture(GL_TEXTURE0)
            glUniform1i(glGetUniformLocation(program, "uPicture"), 0)
            // The stage's texture holds the picture's top in its last row, as OpenGL counts them.
            glUniform1f(glGetUniformLocation(program, "uFromTop"), 0f)
            glBindTexture(GL_TEXTURE_2D, picture.texture)
            val v = viewport(scene.place, fh[0])
            glViewport(v[0], v[1], v[2], v[3])
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
            val after = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
            // Flushed, so the stage's context can wait on it.
            glFlush()
            read.getAndSet(picture.slot, after).takeIf { it != 0L }?.let(::glDeleteSync)
            drawSubtitles(scene, fh[0])
        }
        val drawn = System.nanoTime() - began
        onDrawn?.let { look ->
            val pixels = MemoryUtil.memAlloc(fw[0] * fh[0] * 4)
            glPixelStorei(GL_PACK_ALIGNMENT, 1)
            glReadPixels(0, 0, fw[0], fh[0], GL_RGBA, GL_UNSIGNED_BYTE, pixels)
            val rgba = ByteArray(pixels.remaining()).also { pixels.get(it) }
            MemoryUtil.memFree(pixels)
            look(rgba, fw[0], fh[0])
        }
        GLFW.glfwSwapBuffers(window)
        val swapped = System.nanoTime()
        if (picture != null) onPresented?.invoke(picture.ptsMs, drawn, swapped)
    }

    // libass's pictures over the picture, each scaled from the picture's size to its place on screen, blended by its
    // own alpha (AssPicture: colour not premultiplied, rows from the top).
    private fun drawSubtitles(scene: Scene, windowHeight: Int) {
        if (scene.subtitles.none { !it.isEmpty } || scene.pictureWidth <= 0 || scene.pictureHeight <= 0) return
        if (subtitleTexture == 0) {
            subtitleTexture = glGenTextures()
            glBindTexture(GL_TEXTURE_2D, subtitleTexture)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        }
        glEnable(GL_BLEND)
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
        glUniform1f(glGetUniformLocation(program, "uFromTop"), 1f)
        glBindTexture(GL_TEXTURE_2D, subtitleTexture)
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4)
        val sx = scene.place[2].toFloat() / scene.pictureWidth
        val sy = scene.place[3].toFloat() / scene.pictureHeight
        for (sign in scene.subtitles) {
            if (sign.isEmpty) continue
            glTexImage2D(
                GL_TEXTURE_2D, 0, GL_RGBA8, sign.width, sign.height, 0, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV,
                sign.pixels,
            )
            val at = intArrayOf(
                scene.place[0] + (sign.x * sx).toInt(),
                scene.place[1] + (sign.y * sy).toInt(),
                (sign.width * sx).toInt().coerceAtLeast(1),
                (sign.height * sy).toInt().coerceAtLeast(1),
            )
            val v = viewport(at, windowHeight)
            glViewport(v[0], v[1], v[2], v[3])
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        }
        glDisable(GL_BLEND)
    }

    private fun program(): Int {
        fun shader(type: Int, source: String): Int = glCreateShader(type).also {
            glShaderSource(it, source)
            glCompileShader(it)
            check(glGetShaderi(it, GL_COMPILE_STATUS) == GL_TRUE) { glGetShaderInfoLog(it) }
        }
        val vertex = shader(GL_VERTEX_SHADER, Shaders.PRESENT_VERTEX_ES3)
        val fragment = shader(GL_FRAGMENT_SHADER, Shaders.PRESENT_PICTURE_ES3)
        return glCreateProgram().also {
            glAttachShader(it, vertex)
            glAttachShader(it, fragment)
            glLinkProgram(it)
            check(glGetProgrami(it, GL_LINK_STATUS) == GL_TRUE) { glGetProgramInfoLog(it) }
            glDeleteShader(vertex)
            glDeleteShader(fragment)
        }
    }

    /**
     * The window gone, once its thread is out of its last swap. A swap at the screen's refresh can wait as long as the
     * window is not on screen (a locked or hidden one under a compositor): then this waits [CLOSE_WAIT_MS] and returns,
     * the window going whenever that swap does — so closing the player never hangs on it.
     */
    override fun close() {
        if (!open) return
        lock.withLock {
            open = false
            changed.signal()
        }
        val gone = thread.submit {
            if (window != 0L) {
                for (i in 0 until SLOTS) {
                    written.getAndSet(i, 0L).takeIf { it != 0L }?.let(::glDeleteSync)
                    read.getAndSet(i, 0L).takeIf { it != 0L }?.let(::glDeleteSync)
                }
                if (subtitleTexture != 0) glDeleteTextures(subtitleTexture)
                if (program != 0) glDeleteProgram(program)
                if (vao != 0) glDeleteVertexArrays(vao)
                GLFW.glfwMakeContextCurrent(0)
                GLFW.glfwDestroyWindow(window)
                window = 0L
            }
        }
        thread.shutdown()
        runCatching { gone.get(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS) }
    }

    companion object {
        /** The textures the stage hands over in turn: one shown, one waiting, one written. */
        const val SLOTS = 3

        // How long the window's thread sleeps without news before pumping the window's messages again.
        private const val WAIT_MS = 15L

        private val WIN32 = System.getProperty("os.name").startsWith("Windows")

        // How long closing waits for the window's thread to leave a swap that does not return.
        private const val CLOSE_WAIT_MS = 1_000L

        /**
         * OpenGL's viewport (left, bottom, width, height) for a rectangle [rect] (left, top, width, height) counted
         * from the top of a window [windowHeight] pixels high.
         */
        fun viewport(rect: IntArray, windowHeight: Int): IntArray =
            intArrayOf(rect[0], windowHeight - rect[1] - rect[3], rect[2], rect[3])
    }
}
