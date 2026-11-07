package com.hawkynt.mediadroid.engine.desktop

import com.hawkynt.mediadroid.player.Deblock
import com.hawkynt.mediadroid.player.Deinterlacer
import com.hawkynt.mediadroid.player.DotCrawl
import com.hawkynt.mediadroid.player.Dither
import com.hawkynt.mediadroid.player.MotionAdaptive
import com.hawkynt.mediadroid.player.MotionInterpolation
import com.hawkynt.mediadroid.player.MpvShader
import com.hawkynt.mediadroid.player.MpvShaders
import com.hawkynt.mediadroid.player.NeuralEnlarging
import com.hawkynt.mediadroid.player.NeuralInterpolation
import com.hawkynt.mediadroid.player.OldGame
import com.hawkynt.mediadroid.player.PeakDetect
import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.player.PictureCrop
import com.hawkynt.mediadroid.player.ScreenWarp
import com.hawkynt.mediadroid.player.TemporalDenoise
import com.hawkynt.mediadroid.player.ToneMap
import com.hawkynt.mediadroid.shaders.Shaders
import com.hawkynt.mediadroid.stage.Colouring
import com.hawkynt.mediadroid.stage.Conversion
import com.hawkynt.mediadroid.stage.Deinterlacing
import com.hawkynt.mediadroid.stage.DrawTarget
import com.hawkynt.mediadroid.stage.Finishing
import com.hawkynt.mediadroid.stage.Gles
import com.hawkynt.mediadroid.stage.MpvHooks
import com.hawkynt.mediadroid.stage.Passes
import com.hawkynt.mediadroid.stage.Restoration
import com.hawkynt.mediadroid.stage.SampledTexture
import com.hawkynt.mediadroid.stage.StageFrame
import com.hawkynt.mediadroid.stage.StageTables
import com.hawkynt.mediadroid.stage.Upscaling
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL43.*
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** The stage's frames: :stage's (StageFrame), each by its name. */
private typealias Target = StageFrame

/**
 * The picture stage on the desktop: the Android stage's shaders (:stage) on OpenGL 4.3 — which compiles GLSL ES 1.00
 * and 3.00 as they are — in a hidden window's context on a thread of its own. A moment of playback goes in (Moment: the
 * picture on screen, its neighbours, how far the clock is to the next), the picture as the household set it comes out
 * at the size it is shown: its black bars cropped as it comes in (AutoCrop finds them) and the 3D eye cut out of what
 * is left, so every pass after sees the picture alone; an HDR one tone-mapped to SDR as it comes in (ToneMap, its peak
 * measured as it plays: PeakDetect), deinterlaced (Auto, Blend, Bob, YADIF, BWDIF, Greedy HM, Greedy 2-Frame,
 * TomsMoComp, Adaptive with MotionAdaptive, Old console with OldGame, every field at field rate; Film is the engine's,
 * see Film), debanded, denoised, sharpened, blended into the next picture by the clock (Low, Medium, High) or along the
 * motion between them (Motion, with MotionSearch), enlarged (Lanczos, FSR, EWA, NIS, GSR, Anime4K, ACNet, xBRZ; the
 * neural ones plain for now) at the strength chosen, colour-corrected, and dithered to 8 bits where it holds more
 * (Dither). Each picture is filtered once and kept while it is on screen or next. The passes and their uniforms are the
 * Android stage's (VideoFilterStage), the shaders those tools/shader-check holds to their originals. The picture is
 * read back for the window as [presentation] says (Presentation); with a [shareWith] window, the stage's context shares
 * its textures with that window's, for [processTexture].
 */
class GlStage(
    private val presentation: Presentation = Presentation.chosen(),
    private val shareWith: Long = 0L,
) : AutoCloseable {

    private val thread = Executors.newSingleThreadExecutor { Thread(it, "mediadroid-stage").apply { isDaemon = true } }
    private var window = 0L
    private val targets = HashMap<String, Target>()
    private var vao = 0

    // The passes as :stage runs them, on this context; the upscalers' among them.
    private val gl = LwjglGles { vao }
    private val passes = Passes(gl)
    private val upscaling = Upscaling(passes)
    private val colouring = Colouring(gl, passes)
    private val restoration = Restoration(gl, passes)
    private val deinterlacing = Deinterlacing(passes)
    private val conversion = Conversion(passes)
    private val finishing = Finishing(passes)

    // The mpv user shaders' runner, its frames the stage's own in half floats; OpenGL 4.3 runs compute passes.
    private val mpvHooks = MpvHooks(gl, passes, finishing, compute = { true }) { name, w, h ->
        target(name, w, h, half = true)
    }
    private var obmcTable = 0

    // Whether this moment's passes draw into half floats for the last pass to dither (see run).
    private var deep = false

    // HDR's peak as measured over the pictures so far, and the transfer it was measured for.
    private val peakDetect = PeakDetect()
    private var peakTransfer: ToneMap.Transfer? = null
    private var out: ByteBuffer? = null

    // The pixel-buffer objects pictures are read back through (Presentation.PBO, PBO_LAGGED): each one's buffer, the
    // bytes it holds, its fence and what is being read into it; the picture handed out last.
    private val readbackRing = ReadbackRing(
        PBO_BUFFERS,
        if (presentation.readBack == Presentation.PBO_LAGGED) 1 else 0,
    )
    private val pbos = IntArray(PBO_BUFFERS)
    private val pboBytes = IntArray(PBO_BUFFERS)
    private val pboFences = LongArray(PBO_BUFFERS)
    private val pboPictures = arrayOfNulls<LongArray>(PBO_BUFFERS)
    private var handedOut: MediaReader.Picture? = null

    // The textures processTexture hands to the window in turn, each one's size and picture's time; the one written
    // last; the framebuffer it is read back through when asked (readBackShown).
    private val shared = IntArray(SHARED_TEXTURES)
    private val sharedSizes = arrayOfNulls<IntArray>(SHARED_TEXTURES)
    private val sharedPts = LongArray(SHARED_TEXTURES)
    private var sharedNext = 0
    private var sharedLast = -1
    private var sharedRead = 0

    // The window the stage shows its pictures in itself (Presentation.NATIVE), once opened.
    @Volatile private var pictureWindow: PictureWindow? = null

    // The pictures uploaded (their 3D eye) and the pictures filtered at their own size, each by the picture it holds.
    private val uploaded = Array(UPLOADED) { Slot("raw$it") }
    private val kept = Array(KEPT) { Slot("kept$it") }
    private var keptLook: Any? = null

    // Adaptive (see MotionAdaptive): the last picture weighed, its luma, and whether the picture stands still.
    private val motionAdaptive = MotionAdaptive()
    private var adaptivePicture: MediaReader.Picture? = null
    private var adaptiveLuma: Deinterlacer.Plane? = null
    private var uses = 0L

    // Motion's vectors on the graphics card, and the search's answer they hold.
    private val motion = MotionSearch()
    private var vectorTexture = 0
    private var vectorsShown: MotionSearch.Vectors? = null

    // Temporal denoising's own search between each picture and its neighbours (as decoded, the whole region), the
    // vectors' textures by the search's answer, and the noise level the searches read.
    internal val denoiseMotion = MotionSearch()
    private val denoiseVectors = LinkedHashMap<MotionSearch.Vectors, Int>()
    internal val noiseLevel = TemporalDenoise.NoiseLevel()
    private var noiseCounted: MotionSearch.Vectors? = null

    /**
     * A picture held on the graphics card: which, its 3D eye or field, whether in half floats, and when it was last
     * used.
     */
    private class Slot(val name: String) {
        var picture: MediaReader.Picture? = null
        var region = PictureCrop(0, 0, 0, 0)
        var second = false
        var half = false
        var used = 0L

        // Filtered before the vectors temporal denoising waits for were in: filtered again once they are.
        var unsettled = false
    }

    /** Pairs of pictures Motion showed before their vectors were in, since the stage started. */
    val motionLate: Int get() = motion.late

    /** Whether the context came up; without it, pictures pass untouched. */
    @Volatile var ready = false
        private set

    /** What handing the last picture to the window took on the stage's thread, in nanoseconds (the read-back). */
    @Volatile var presentNanos = 0L
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
        window = GLFW.glfwCreateWindow(16, 16, "MediaDroid stage", 0, shareWith)
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
        obmcTable = passes.table(StageTables.OBMC_SIZE, StageTables.OBMC_SIZE, StageTables.obmc())
        // OpenGL 4.3 has compute shaders, which mpv user shaders' compute passes need.
        MpvShaders.compute = true
        ready = true
    }

    /**
     * [picture] alone as [adjustment] has it, at [width] × [height]: RGBA rows from the top — only its part [crop]
     * (the picture inside its black bars), when one is given; its geometry bent by [warp] (ScreenWarp: a projector's
     * mirror, flip and keystone, the panorama).
     */
    fun process(
        picture: MediaReader.Picture,
        adjustment: PictureAdjustment,
        width: Int,
        height: Int,
        crop: PictureCrop? = null,
        warp: ScreenWarp = ScreenWarp.None,
    ): MediaReader.Picture = process(Moment(picture), adjustment, width, height, crop, warp)

    /**
     * The picture [moment] shows as [adjustment] has it, at [width] × [height]: RGBA rows from the top. A temporal
     * deinterlacer reads the pictures either side of it; smooth motion mixes it into the next by the moment's phase.
     * Only the part [crop] of each picture (inside its black bars) is shown, when one is given; its geometry bent by
     * [warp] (ScreenWarp: a projector's mirror, flip and keystone, the panorama).
     */
    fun process(
        moment: Moment,
        adjustment: PictureAdjustment,
        width: Int,
        height: Int,
        crop: PictureCrop? = null,
        warp: ScreenWarp = ScreenWarp.None,
    ): MediaReader.Picture = if (!ready) {
        moment.shown
    } else {
        thread.submit<MediaReader.Picture> {
            readBack(run(moment, adjustment, width, height, crop, warp), moment.shown.ptsMs)
        }.get()
    }

    /** Whether a picture drawn is still being read back (Presentation.PBO_LAGGED): [latest] hands it out. */
    val behind: Boolean get() = presentation.readBack == Presentation.PBO_LAGGED && readbackRing.inFlight > 0

    /**
     * The newest picture read back: under Presentation.PBO_LAGGED the one the last [process] drew, collected now (for a
     * window that asks for no new picture — paused, a still); otherwise the one [process] handed out. Null before any.
     */
    fun latest(): MediaReader.Picture? = if (!ready) {
        null
    } else {
        thread.submit<MediaReader.Picture?> {
            while (readbackRing.inFlight > 0) collect(readbackRing.finish())
            handedOut
        }.get()
    }

    /**
     * A picture as [process] draws it, left on the graphics card for a window whose context shares the stage's
     * ([shareWith]) or for the stage's own [openWindow], which then shows it: one of [SHARED_TEXTURES] textures in
     * turn, RGBA8 with the picture's top in its last row (as OpenGL counts them), and the fence a sharing window waits
     * on (glWaitSync) before drawing it — the window opened waits on it itself.
     */
    fun processTexture(
        moment: Moment,
        adjustment: PictureAdjustment,
        width: Int,
        height: Int,
        crop: PictureCrop? = null,
        warp: ScreenWarp = ScreenWarp.None,
    ): SharedPicture? = if (!ready || (shareWith == 0L && pictureWindow == null)) {
        null
    } else {
        thread.submit<SharedPicture> {
            share(run(moment, adjustment, width, height, crop, warp), moment.shown.ptsMs)
        }.get()
    }

    /** [picture] alone through [processTexture]: a still, a test pattern. */
    fun processTexture(
        picture: MediaReader.Picture,
        adjustment: PictureAdjustment,
        width: Int,
        height: Int,
        crop: PictureCrop? = null,
        warp: ScreenWarp = ScreenWarp.None,
    ): SharedPicture? = processTexture(Moment(picture), adjustment, width, height, crop, warp)

    /** A texture the stage drew into for a sharing window ([processTexture]); [slot] which of them. */
    class SharedPicture(
        val slot: Int,
        val texture: Int,
        val width: Int,
        val height: Int,
        val fence: Long,
        /** The time of the picture in it. */
        val ptsMs: Long = 0L,
    )

    /**
     * A window of the stage's own showing each picture [processTexture] draws (PictureWindow, Presentation.NATIVE),
     * swapped at the screen's refresh when [vsync]; hidden until shown. Null where none comes up. One at a time:
     * opening another closes the one before.
     */
    fun openWindow(vsync: Boolean = true): PictureWindow? = if (!ready) {
        null
    } else {
        thread.submit<PictureWindow?> {
            pictureWindow?.close()
            pictureWindow = null
            // Made on a thread of its own, which Win32 wants pumping its messages; the stage's context let go
            // meanwhile, as sharing a context current on another thread is not allowed everywhere.
            GLFW.glfwMakeContextCurrent(0)
            val opened = runCatching { PictureWindow(window, vsync) }.getOrNull()
            GLFW.glfwMakeContextCurrent(window)
            if (opened?.ready == true) {
                sharedLast = -1
                pictureWindow = opened
                opened
            } else {
                opened?.close()
                null
            }
        }.get()
    }

    /** The window [openWindow] opened closed again: [processTexture] hands its pictures to nobody but a sharer. */
    fun closeWindow() {
        if (!ready || thread.isShutdown) return
        thread.submit {
            pictureWindow?.close()
            pictureWindow = null
        }.get()
    }

    /**
     * The picture [processTexture] drew last — on screen in the window the stage shows it in — read back now, rows from
     * the top: for a snapshot or the histogram, which want its pixels. Null before any.
     */
    fun readBackShown(): MediaReader.Picture? = if (!ready) {
        null
    } else {
        thread.submit<MediaReader.Picture?> {
            val slot = sharedLast
            val size = sharedSizes.getOrNull(slot)
            if (slot < 0 || shared[slot] == 0 || size == null) return@submit null
            if (sharedRead == 0) sharedRead = glGenFramebuffers()
            glBindFramebuffer(GL_FRAMEBUFFER, sharedRead)
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, shared[slot], 0)
            val read = object : DrawTarget {
                override val texture = shared[slot]
                override val framebuffer = sharedRead
                override val width = size[0]
                override val height = size[1]
            }
            download(read, sharedPts[slot])
        }.get()
    }

    // The part of [picture] the stage works on: inside the [crop] where it fits the picture, of that the [stereo] eye.
    private fun region(
        picture: MediaReader.Picture,
        crop: PictureCrop?,
        stereo: PictureAdjustment.Stereo,
    ): PictureCrop {
        val base = crop?.takeIf { it.left + it.width <= picture.width && it.top + it.height <= picture.height }
            ?: PictureCrop.whole(picture.width, picture.height)
        return PictureCrop(
            base.left + (base.width * stereo.x).toInt(),
            base.top + ((1f - stereo.y - stereo.height) * base.height).toInt(),
            maxOf(1, (base.width * stereo.width).toInt()),
            maxOf(1, (base.height * stereo.height).toInt()),
        )
    }

    private var crop: PictureCrop? = null

    // The chroma upscaler the pictures are uploaded with (from their planes, where they carry them), and the planes'
    // textures: luma, Cb, Cr.
    private var chroma = PictureAdjustment.Chroma.BILINEAR
    private val planeTextures = IntArray(3)

    // The mpv user shader whose plane hooks the pictures are uploaded with (from their planes: CHROMA, and LUMA where
    // [lumaOnPlanes]), and the size shown, which its passes' sizes may read.
    private var planeShader: MpvShader? = null
    private var lumaOnPlanes = false
    private var hookOutW = 0
    private var hookOutH = 0

    private fun run(
        m: Moment,
        a: PictureAdjustment,
        outW: Int,
        outH: Int,
        crop: PictureCrop?,
        warp: ScreenWarp,
    ): Target {
        this.crop = crop
        hookOutW = outW
        hookOutH = outH
        // The mpv user shader chosen, where the stage can run it (MpvShader).
        val user = a.shader?.let { MpvShaders.library[it] }?.takeIf { MpvShaders.runnable(it) }
        val planeHooked = user?.takeIf { it.hooksPlanes }
        // LUMA on the decoded Y plane, as mpv runs it, where the picture comes with its planes — but where the shader
        // enlarges the Y plane, only while nothing between the upload and the hooks needs the picture at its decoded
        // size (fields, codec blocks, the composite's dot pattern, motion searched on the decoded pictures); there, as
        // without planes, on the RGB picture's brightness (MpvHooks.run).
        val decoded = MpvShader.Size(m.shown.width, m.shown.height)
        val lumaPlanes = planeHooked != null && planeHooked.planned("LUMA").isNotEmpty() && (!atDecodedSize(a) ||
            mpvHooks.lumaSize(planeHooked, decoded.width, decoded.height, MpvHooks.Sizes(outW, outH, 0, 0)) == decoded)
        // Where the picture holds more than the 8 bits it is read back in — HDR's tone-mapped floats, the colour
        // correction's or debanding's results — every pass draws into half floats and the last one dithers (Dither);
        // a plain 8-bit picture keeps its 8-bit frames, untouched.
        deep = Dither.applies(if (m.shown.hdr != null) 16 else 8, SCREEN_BITS, a)
        // A change to the filters, to the part shown, or to the frames' depth makes every kept picture stale.
        val look = listOf(
            a.deinterlace, a.stereo, a.deband, a.noise, a.sharpness, a.clarity, crop, deep, a.chroma, a.colorize,
            a.logo, a.deblock, a.dering,
            a.temporalDenoise, a.dotCrawl, a.colourSystem, planeHooked, lumaPlanes,
        )
        // Another chroma upscaler, or another shader hooking the planes, makes every picture uploaded from its planes
        // stale.
        if (a.chroma != chroma || planeHooked !== planeShader || lumaPlanes != lumaOnPlanes) {
            chroma = a.chroma
            planeShader = planeHooked
            lumaOnPlanes = lumaPlanes
            uploaded.forEach { it.picture = null }
        }
        if (look != keptLook) {
            keptLook = look
            kept.forEach { it.picture = null }
        }
        val next = m.next?.takeIf { it.width == m.shown.width && it.height == m.shown.height }
        if (a.deinterlace == PictureAdjustment.Deinterlace.ADAPTIVE) adapt(m)
        // Temporal denoising's searches start as soon as the pictures are decoded, ahead of their being shown.
        if (a.temporalDenoise > 0f) {
            val pictures = listOfNotNull(m.previous, m.shown, next, m.after)
            pictures.zipWithNext { earlier, later ->
                if (earlier.width == later.width && earlier.height == later.height) {
                    denoiseMotion.request(earlier, later, region(later, crop, a.stereo))
                }
            }
        }
        // A neural network only where its self-test passed and it opens on the unit it passed on.
        val blending = a.interpolation != PictureAdjustment.Interpolation.OFF && next != null &&
            (!a.interpolation.isNeural || interpolator(a.interpolation) != null)
        // At field rate the second half of a picture's time shows its other field.
        val second = a.isFieldRate && !blending && next != null && m.phase >= 0.5f
        var source = filtered(m.shown, m.previous, next, a, second, m.after, m.earlier, m.older)
        if (blending && next != null) {
            val later = filtered(next, m.shown, m.after, a, false, earlier = m.previous)
            source = mixed(m, next, source, later, a)
        }
        // The mpv user shader's hooks before the picture is enlarged (its CHROMA ones, and LUMA ones where the picture
        // came with its planes, ran as it was uploaded).
        if (user != null) {
            val lumaRan = lumaOnPlanes && fromPlanes(m.shown)
            val stages = if (lumaRan) MpvShader.BEFORE_SCALING - "LUMA" else MpvShader.BEFORE_SCALING
            source = userHooks(user, source, stages, outW, outH, m.shown.width, m.shown.height)
        }
        // Enlarging to the shown size (at most twice where the upscaler is made for that, the rest bilinear), at the
        // strength chosen: below full strength the plain picture laid over it by that share.
        if (a.upscales && (outW > source.width || outH > source.height)) {
            val plain = source
            source = upscale(a, source, outW, outH)
            if (a.plainShare > 0f) {
                val upscaled = source
                source = finishing.blend(upscaled, plain, target("strength", outW, outH, half = deep), a.plainShare)
                    as Target
            }
        }
        // The user shader's hooks on the enlarged picture, at the shown size.
        if (user != null && user.passes.any { p -> p.hooks.any { it in MpvShader.AFTER_SCALING } }) {
            if (source.width != outW || source.height != outH) {
                val scaled = target("mpvScaled", outW, outH, half = true)
                source = finishing.copy(source, scaled) as Target
            }
            source = userHooks(user, source, MpvShader.AFTER_SCALING, outW, outH, m.shown.width, m.shown.height)
        }
        // To the shown size, colour-corrected, its geometry bent (ScreenWarp).
        val shown = target("shown", outW, outH, half = deep)
        finishing.adjust(source, shown, a, warp)
        if (!deep) return shown
        // Dithered to 8 bits, the last pass before the picture is read back: libplacebo's blue noise
        // (glsl/video/dither.glsl).
        // The frame's top row is where v is 1 (see upload).
        return finishing.dither(shown, target("dithered", outW, outH)) as Target
    }

    /**
     * Adaptive: a picture not weighed yet weighed against the one before it (when that was the last one weighed), from
     * their luma every fourth column; HDR pictures move.
     */
    private fun adapt(m: Moment) {
        val picture = m.shown
        if (picture === adaptivePicture) return
        val luma = if (picture.hdr == null) Film.luma(picture, ADAPTIVE_COLUMN_STEP) else null
        val before = adaptiveLuma.takeIf { m.previous != null && m.previous === adaptivePicture }
        if (luma == null) motionAdaptive.reset() else motionAdaptive.add(before, luma, ADAPTIVE_COLUMN_STEP)
        adaptivePicture = picture
        adaptiveLuma = luma
    }

    /**
     * MC Smooth (see TemporalSmoothing): [base], the shown field's BWDIF picture of [cur], averaged along the motion
     * with the BWDIF pictures of the fields before and after — from [prev], [next] and [further], the frame two before
     * stood in for by [prev] — contra-sharpened and repaired, and the camera's rows of [cur] put back.
     */
    private fun smoothAlongMotion(
        base: Target,
        prev: Target,
        cur: Target,
        next: Target,
        further: Target,
        second: Boolean,
    ): Target {
        val w = base.width
        val h = base.height
        return deinterlacing.smoothAlongMotion(
            base, prev, cur, next, further, second,
            target("mc-earlier", w, h, half = deep), target("mc-later", w, h, half = deep),
            target("mc-smoothed", w, h, half = deep), target("mc-sharpened", w, h, half = deep),
        ) as Target
    }

    /**
     * Old console: whether [picture]'s field shown ([second] or its first) is averaged with the field before (see
     * OldGame), from the pictures' luma every fourth column; never for HDR pictures.
     */
    private fun consoleAverages(picture: MediaReader.Picture, before: MediaReader.Picture?, second: Boolean): Boolean {
        if (picture.hdr != null) return false
        val prev = before?.takeIf { it.hdr == null && it.width == picture.width && it.height == picture.height }
        val step = ADAPTIVE_COLUMN_STEP
        return OldGame.averages(prev?.let { Film.luma(it, step) }, Film.luma(picture, step), second, true, step)
    }

    /**
     * [picture] (its 3D eye) deinterlaced — a temporal deinterlacer reading [before] and [after] where known, its
     * [second] field rebuilt at field rate (MC Smooth reads [beyond], the picture after [after], too) — debanded,
     * denoised and sharpened at its own size; kept while it is used. PAL's dot-crawl comb reads [earlier], the picture
     * before [before], and per field [older], the pictures before that, newest first.
     */
    private fun filtered(
        picture: MediaReader.Picture,
        before: MediaReader.Picture?,
        after: MediaReader.Picture?,
        a: PictureAdjustment,
        second: Boolean,
        beyond: MediaReader.Picture? = null,
        earlier: MediaReader.Picture? = null,
        older: List<MediaReader.Picture> = emptyList(),
    ): Target {
        kept.firstOrNull { it.picture === picture && it.second == second }?.let {
            if (!it.unsettled) return slotTarget(it)
            it.picture = null
        }
        var unsettled = false
        val raw = uploaded(picture, region(picture, crop, a.stereo))
        val w = raw.width
        val h = raw.height
        var source = raw
        fun neighbour(p: MediaReader.Picture) = uploaded(p, region(p, crop, a.stereo))
        // 0. Dot crawl and rainbows combed out, then temporal denoising: the picture against the pictures before and
        // after it, as decoded. The standard (DotCrawl.Standard) from the rate, the file's colour tags and the
        // household's choice: NTSC's comb reads the pictures either side; PAL's (and PAL-M's) the picture two before
        // as well; per field, for a capture bobbed to its fields, the same field's pictures two, four and six before
        // (NTSC's and PAL's alike, with the residues each pairs).
        val combed = DotCrawl.threshold(a.dotCrawl)?.let { threshold ->
            fun near(p: MediaReader.Picture?) = p?.let(::neighbour)?.takeIf { it.width == w && it.height == h } ?: raw
            val intervalMs = before?.let { picture.ptsMs - it.ptsMs } ?: after?.let { it.ptsMs - picture.ptsMs } ?: 0L
            val into = target("combed", w, h, half = deep)
            when (DotCrawl.Standard.of(intervalMs.toFloat(), a.colourSystem, picture.palColour)) {
                DotCrawl.Standard.NTSC -> restoration.dotCrawl(raw, near(before), near(after), null, into, threshold)
                DotCrawl.Standard.PAL ->
                    restoration.dotCrawl(raw, near(before), near(after), near(earlier), into, threshold)
                // DotCrawl.applyPalFields: PAL's comb with the six before for the frame after, the four before for
                // the two before.
                DotCrawl.Standard.PAL_FIELDS -> restoration.dotCrawl(
                    raw, near(earlier), near(older.getOrNull(3)), near(older.getOrNull(1)), into, threshold,
                )
                // DotCrawl.applyNtscFields: the same with the two before for the two before, the four before for the
                // picture before.
                DotCrawl.Standard.NTSC_FIELDS -> restoration.dotCrawl(
                    raw, near(older.getOrNull(1)), near(older.getOrNull(3)), near(earlier), into, threshold,
                )
            } as Target
        } ?: raw
        // The pictures either side moved onto it along the motion between them once the search has it — until then
        // read as they are, and filtered again.
        val shown = if (a.temporalDenoise > 0f) {
            val region = region(picture, crop, a.stereo)
            fun side(other: MediaReader.Picture?, earlier: Boolean): Pair<Target, MotionSearch.Vectors?>? {
                other ?: return null
                val target = neighbour(other).takeIf { it.width == w && it.height == h } ?: return null
                val (from, to) = if (earlier) other to picture else picture to other
                denoiseMotion.request(from, to, region)
                val vectors = denoiseMotion.vectors(from, to)
                if (vectors == null && !denoiseMotion.ended(from, to)) unsettled = true
                return target to vectors
            }
            val prev = side(before, true)
            val next = side(after, false)
            listOfNotNull(prev?.second, next?.second).firstOrNull()?.takeIf { it !== noiseCounted }?.let {
                noiseLevel.add(it.noise)
                noiseCounted = it
            }
            val params = requireNotNull(TemporalDenoise.params(a.temporalDenoise, noiseLevel.sigma))
            denoised(combed, prev, next, params)
        } else {
            combed
        }
        source = shown
        // 1. Deinterlacing.
        if (a.deinterlace.isTemporal) {
            val prev = neighbour(before ?: picture).takeIf { it.width == w && it.height == h } ?: raw
            val next = neighbour(after ?: picture).takeIf { it.width == w && it.height == h } ?: raw
            val method = when {
                a.deinterlace == PictureAdjustment.Deinterlace.OLD_CONSOLE -> Deinterlacer.OLD_CONSOLE
                a.deinterlace == PictureAdjustment.Deinterlace.ADAPTIVE && motionAdaptive.weave -> Deinterlacer.WEAVE
                else -> Deinterlacer.shaderMethod(a.deinterlace.method ?: Deinterlacer.Method.YADIF)
            }
            val average = method == Deinterlacer.OLD_CONSOLE && consoleAverages(picture, before, second)
            source = deinterlacing.field(prev, shown, next, target("deinterlaced", w, h, half = deep), second, method,
                average = average) as Target
            if (a.deinterlace == PictureAdjustment.Deinterlace.SMOOTH_MC) {
                val further = neighbour(beyond ?: after ?: picture).takeIf { it.width == w && it.height == h } ?: next
                source = smoothAlongMotion(source, prev, shown, next, further, second)
            }
        } else if (a.deinterlace != PictureAdjustment.Deinterlace.OFF &&
            a.deinterlace != PictureAdjustment.Deinterlace.FILM
        ) {
            source = deinterlacing.spatial(shown, target("deinterlaced", w, h, half = deep), a.deinterlace) as Target
        }
        // 1b. Deblocking and deringing, on the decoded picture's own block grid, which the part shown (its bars, its
        // 3D eye) may have cut into — before anything else changes the picture.
        val part = region(picture, crop, a.stereo)
        val (originX, originY) = Deblock.gridOrigin(part.left, part.top)
        a.deblockParams()?.let { d ->
            for (step in 0..2) {
                source = restoration.deblock(source, target("deblocked$step", w, h, half = deep), originX, originY,
                    step, d) as Target
            }
        }
        val blocksWide = (w - originX) / 8
        val blocksHigh = (h - originY) / 8
        a.deringParams()?.takeIf { blocksWide > 0 && blocksHigh > 0 }?.let { d ->
            source = restoration.dering(source, target("cdefBlocks", blocksWide, blocksHigh),
                target("deringed", w, h, half = deep), originX, originY, d) as Target
        }
        // 1c. Colour for a black-and-white picture, by the network whose test passed (see colored).
        if (a.colorize != PictureAdjustment.Colorize.OFF) source = colored(source)
        // 2. The station logo hidden, then deband, denoise and sharpen at the picture's own size.
        a.logo?.let { logo ->
            val rect = logo.rectangle(w, h)
            if (rect.left + rect.width < w && rect.top + rect.height < h) {
                source = restoration.logo(source, target("logoMasked", w, h, half = deep), logo, rect) as Target
            }
        }
        a.debandParams()?.let { d ->
            source = restoration.deband(source, target("debanded", w, h, half = deep), d) as Target
        }
        a.denoiseKernel()?.let { k ->
            source = restoration.denoise(source, target("denoised", w, h, half = deep), k) as Target
        }
        val slot = claim(kept, picture, region(picture, crop, a.stereo), second)
        slot.unsettled = unsettled
        val into = target(slot.name, w, h, half = deep)
        if (a.sharpness > 0f || a.clarity > 0f) {
            restoration.sharpen(source, into, a.sharpness, a.clarity)
        } else {
            copy(source, into)
        }
        return into
    }

    /**
     * [raw] temporally denoised (TemporalDenoise) against [prev] and [next] — each a picture with the vectors between
     * it and [raw] where the search has them: moved along them, or read as it is; a side behind a cut is not taken, a
     * missing one is [raw] itself.
     */
    private fun denoised(
        raw: Target,
        prev: Pair<Target, MotionSearch.Vectors?>?,
        next: Pair<Target, MotionSearch.Vectors?>?,
        params: TemporalDenoise.Params,
    ): Target {
        fun side(side: Pair<Target, MotionSearch.Vectors?>?): Restoration.Side {
            val v = side?.second ?: return Restoration.Side(side?.first ?: raw, obmcTable, 0f)
            return Restoration.Side(side.first, vectorTexture(v), if (v.cut) 2f else 1f, v.gridWidth, v.gridHeight,
                v.scale, grid = true)
        }
        return restoration.temporalDenoise(raw, side(prev), side(next), obmcTable, params,
            target("temporallyDenoised", raw.width, raw.height, half = deep)) as Target
    }

    // [vectors] as a texture, kept for the few pictures around the one shown.
    private fun vectorTexture(vectors: MotionSearch.Vectors): Int = denoiseVectors.getOrPut(vectors) {
        passes.table(vectors.gridWidth, vectors.gridHeight, vectors.bytes)
    }.also {
        while (denoiseVectors.size > DENOISE_VECTORS) {
            val oldest = denoiseVectors.keys.first()
            glDeleteTextures(denoiseVectors.remove(oldest)!!)
        }
    }

    /**
     * [earlier] and [later] (the filtered pictures of the moment's shown and [next]) mixed by the moment's phase:
     * blended by the strength's window (Low, Medium, High), or along the vectors Motion's search found between them —
     * a cut switching half-way, as FFmpeg's does, and the earlier picture staying until the later is due while the
     * vectors are not in yet.
     */
    private fun mixed(
        m: Moment,
        next: MediaReader.Picture,
        earlier: Target,
        later: Target,
        a: PictureAdjustment,
    ): Target {
        val phase = m.phase.coerceIn(0f, 1f)
        var weight = a.interpolation.weight(phase)
        // A network's in-between: the picture runs from the earlier through it to the later (NeuralInterpolation.mix).
        if (a.interpolation.isNeural) {
            val middle = inBetween(a.interpolation, m.shown, next, earlier, later)
            if (middle != null) {
                val mix = NeuralInterpolation.mix(phase)
                val (from, to) = if (mix.towardsMiddle) earlier to middle else middle to later
                return finishing.blend(from, to, target("mixed", earlier.width, earlier.height, half = deep),
                    mix.weight) as Target
            }
        }
        var vectors: MotionSearch.Vectors? = null
        if (a.interpolation.isMotionCompensated) {
            motion.request(m.shown, next, region(m.shown, crop, a.stereo))
            m.after?.let { motion.request(next, it, region(next, crop, a.stereo)) }
            vectors = motion.vectors(m.shown, next)
            val along = vectors != null && !vectors.cut && weight > 0f && weight < 1f
            if (!along) {
                val due = if (vectors?.cut == true) 0.5f else 1f
                weight = if (phase < due) 0f else 1f
                vectors = null
            }
        }
        if (weight <= 0f) return earlier
        if (weight >= 1f) return later
        val w = earlier.width
        val h = earlier.height
        val into = target("mixed", w, h, half = deep)
        val along = vectors ?: return finishing.blend(earlier, later, into, weight) as Target
        if (along !== vectorsShown) {
            if (vectorTexture != 0) glDeleteTextures(vectorTexture)
            vectorTexture = passes.table(along.gridWidth, along.gridHeight, along.bytes)
            vectorsShown = along
        }
        return finishing.motion(earlier, later, vectorTexture, obmcTable, into, weight, along.gridWidth,
            along.gridHeight, along.scale) as Target
    }

    /**
     * [from] through [shader]'s passes hooking [stages] (MpvHooks): [outW] × [outH] the size shown, [inputW] × [inputH]
     * the video's.
     */
    private fun userHooks(
        shader: MpvShader,
        from: Target,
        stages: List<String>,
        outW: Int,
        outH: Int,
        inputW: Int,
        inputH: Int,
    ): Target = mpvHooks.run(shader, from, stages, MpvHooks.Sizes(outW, outH, inputW, inputH)) as Target

    /**
     * [picture]'s [region] on the graphics card, uploaded unless it is still there: an HDR picture's tone-mapped floats
     * in half floats where this moment is dithered; an 8-bit one has no more to keep.
     */
    private fun uploaded(picture: MediaReader.Picture, region: PictureCrop): Target {
        val half = deep && picture.hdr != null
        uploaded.firstOrNull { it.picture === picture && it.region == region && it.half == half }
            ?.let { return slotTarget(it) }
        val slot = claim(uploaded, picture, region, false)
        slot.half = half
        if (fromPlanes(picture)) {
            // The whole picture from its planes (larger where a LUMA hook enlarged its Y plane), then the region out
            // of it, at that scale.
            val whole = chromaUpscaled(picture, picture.planes!!)
            val sx = whole.width.toFloat() / picture.width
            val sy = whole.height.toFloat() / picture.height
            val into = target(slot.name, (region.width * sx).roundToInt(), (region.height * sy).roundToInt())
            copyRegion(whole, into, picture, region)
            return into
        }
        val into = target(slot.name, region.width, region.height, half = half)
        upload(into, picture, region)
        return into
    }

    /** Whether [picture] is uploaded from its planes: an SDR picture that has them, where the stage reads them. */
    private fun fromPlanes(picture: MediaReader.Picture): Boolean = picture.hdr == null && picture.planes != null &&
        (chroma != PictureAdjustment.Chroma.BILINEAR || planeShader?.hooksChroma == true || lumaOnPlanes)

    /**
     * Whether a pass of [a] between the upload and the user shader's hooks needs the picture at its decoded size:
     * fields (deinterlacing), codec blocks (deblocking, deringing), the composite's dot pattern, motion searched on the
     * decoded pictures (temporal denoising, interpolation).
     */
    private fun atDecodedSize(a: PictureAdjustment): Boolean =
        a.deinterlace != PictureAdjustment.Deinterlace.OFF || a.deblock > 0f || a.dering > 0f ||
            DotCrawl.threshold(a.dotCrawl) != null || a.temporalDenoise > 0f ||
            a.interpolation != PictureAdjustment.Interpolation.OFF

    /** The least recently used of [slots], now holding [picture]. */
    private fun claim(
        slots: Array<Slot>,
        picture: MediaReader.Picture,
        region: PictureCrop,
        second: Boolean,
    ): Slot = slots.minBy { if (it.picture == null) Long.MIN_VALUE else it.used }.also {
        it.picture = picture
        it.region = region
        it.second = second
        it.used = ++uses
    }

    private fun slotTarget(slot: Slot): Target {
        slot.used = ++uses
        return targets.getValue(slot.name)
    }

    /** [from] into [into] as it is (a blend wholly of the one picture). */
    private fun copy(from: Target, into: Target) {
        finishing.copy(from, into)
    }

    private fun upscale(picture: PictureAdjustment, from: Target, outW: Int, outH: Int): Target {
        val upscaler = picture.upscaler
        val w = from.width
        val h = from.height
        val mid by lazy { up(Upscaling.twice(w, outW), Upscaling.twice(h, outH)) }
        fun net(name: String) = target(name, w, h, half = true)
        return when (upscaler) {
            PictureAdjustment.Upscaler.LANCZOS -> upscaling.lanczos(from, up(outW, outH))
            PictureAdjustment.Upscaler.GSR -> upscaling.gsr(from, up(outW, outH))
            PictureAdjustment.Upscaler.FSR ->
                upscaling.rcas(upscaling.easu(from, target("easu", outW, outH, half = deep)), up(outW, outH))
            PictureAdjustment.Upscaler.EWA -> upscaling.ewa(from, mid)
            PictureAdjustment.Upscaler.NIS -> upscaling.nis(from, mid)
            PictureAdjustment.Upscaler.ANIME4K -> upscaling.anime4k(from, net("netA"), net("netB"), up(w * 2, h * 2))
            PictureAdjustment.Upscaler.ACNET ->
                upscaling.acnet(from, net("netA"), net("netB"), net("netC"), net("netD"), up(w * 2, h * 2))
            PictureAdjustment.Upscaler.XBRZ -> {
                val factor = Upscaling.xbrzFactor(w, h, outW, outH)
                upscaling.xbrz(from, target("xbrzCorners", w + 1, h + 1), target("xbrzDecisions", w, h),
                    up(w * factor, h * factor), factor)
            }
            else -> NeuralEnlarging.choice(picture)?.takeIf { DesktopNeural.onNcnn(upscaler) }
                ?.let { enlarged(it, from) } ?: from
        } as Target
    }

    /**
     * What the self-tests found, and the networks to run (DesktopNeural): a neural choice runs only where its test
     * passed, on the unit it passed on; null, none runs.
     */
    class Neural(
        val networks: DesktopNeural,
        val motion: Map<PictureAdjustment.Interpolation, NeuralInterpolation.Passed> = emptyMap(),
        val enlargers: Map<NeuralEnlarging.Choice, NeuralInterpolation.Passed> = emptyMap(),
        val colorizer: NeuralInterpolation.Passed? = null,
    )

    /** The neural networks the stage may run; set by the player, read on the stage's thread. */
    @Volatile var neural: Neural? = null

    // The networks open on the stage's thread, which they belong to: one interpolator, one upscaler.
    private var interpolatorOpen: DesktopNeural.Interpolator? = null
    private var enlargerOpen: DesktopNeural.Enlarger? = null
    private var tried = HashSet<Any>()
    private var betweenFor: Pair<MediaReader.Picture, MediaReader.Picture>? = null
    private val pictureIds = java.util.WeakHashMap<MediaReader.Picture, Long>()
    private var nextId = 0L

    /** [network] on the unit its test passed on, opened here once; null where it did not pass or does not open. */
    private fun interpolator(network: PictureAdjustment.Interpolation): DesktopNeural.Interpolator? {
        val n = neural ?: return null
        val gpu = n.motion[network]?.unit?.let { it == "GPU" } ?: return null
        interpolatorOpen?.takeIf { it.network == network && it.gpu == gpu }?.let { return it }
        interpolatorOpen?.close()
        interpolatorOpen = null
        betweenFor = null
        // A network that would not open is not tried again at every refresh.
        if (!tried.add(Triple(n, network, gpu))) return null
        interpolatorOpen = n.networks.interpolator(network, gpu)
        return interpolatorOpen
    }

    private fun enlarger(choice: NeuralEnlarging.Choice): DesktopNeural.Enlarger? {
        val n = neural ?: return null
        val gpu = n.enlargers[choice]?.unit?.let { it == "GPU" } ?: return null
        enlargerOpen?.takeIf { it.choice == choice && it.gpu == gpu }?.let { return it }
        enlargerOpen?.close()
        enlargerOpen = null
        if (!tried.add(Triple(n, choice, gpu))) return null
        enlargerOpen = n.networks.enlarger(choice, gpu)
        return enlargerOpen
    }

    /**
     * The in-between of [shown] and [next] (filtered: [earlier], [later]) made by [network] at the size its test
     * found the machine keeps up with (NeuralInterpolation.workingSize), once per pair; null where there is none.
     */
    private fun inBetween(
        network: PictureAdjustment.Interpolation,
        shown: MediaReader.Picture,
        next: MediaReader.Picture,
        earlier: Target,
        later: Target,
    ): Target? {
        val net = interpolator(network) ?: return null
        val times = neural?.motion?.get(network)?.msByHeight ?: return null
        val size = NeuralInterpolation.workingSize(earlier.width, earlier.height, times) ?: return null
        // A network handing back its flow: worked out at that size, the frames warped by it at their own.
        val flows = NeuralInterpolation.handsFlow(network)
        val into = if (flows) {
            target("inBetween", earlier.width, earlier.height)
        } else {
            target("inBetween", size.width, size.height)
        }
        if (betweenFor?.first === shown && betweenFor?.second === next) return into
        val a = read(scaled(earlier, "netEarlier", size))
        val b = read(scaled(later, "netLater", size))
        val made = if (flows) {
            net.flow(a, b, size.width, size.height)?.let { flow ->
                flowTexture = passes.floats(flowTexture, size.width, size.height, 4, flow.duplicate())
                val mask = flow.duplicate().apply { position(size.width * size.height * 4 * Float.SIZE_BYTES) }.slice()
                maskTexture = passes.floats(maskTexture, size.width, size.height, 1, mask)
                finishing.warp(earlier, later, flowTexture, maskTexture, into, size.width, size.height)
            }
        } else {
            net.run(a, b, size.width, size.height, idOf(shown), idOf(next))?.let { uploadRgb(into, it) }
        }
        MemoryUtil.memFree(a)
        MemoryUtil.memFree(b)
        made ?: return null
        betweenFor = shown to next
        return into
    }

    // RIFE's flow and mask as the warp reads them (Passes.floats), refilled pair by pair.
    private var flowTexture = 0
    private var maskTexture = 0

    /** [from] enlarged by [choice]'s network, two, three or four times its size; null where it does not run. */
    private fun enlarged(choice: NeuralEnlarging.Choice, from: Target): Target? {
        val net = enlarger(choice) ?: return null
        val picture = read(from)
        val rgb = net.run(picture, from.width, from.height)
        MemoryUtil.memFree(picture)
        rgb ?: return null
        val into = up(from.width * net.scale, from.height * net.scale)
        uploadRgb(into, rgb)
        return into
    }

    /**
     * [from] in colour where the colouriser's test passed and a result is in (Colouring, on the unit the test passed
     * on); [from] itself otherwise.
     */
    private fun colored(from: Target): Target {
        val n = neural ?: return from
        val gpu = n.colorizer?.unit?.let { it == "GPU" } ?: return from
        return colouring.colored(from, n to gpu, { n.networks.colorizer(gpu)?.let { Colouring.network(it) } }) {
            target("colored", from.width, from.height, half = deep)
        } as Target
    }

    private fun idOf(picture: MediaReader.Picture): Long = pictureIds.getOrPut(picture) { ++nextId }

    /** [from] at [size], plainly scaled; [from] itself where it has that size. */
    private fun scaled(from: Target, name: String, size: NeuralInterpolation.Size): Target {
        if (from.width == size.width && from.height == size.height) return from
        val into = target(name, size.width, size.height)
        copy(from, into)
        return into
    }

    /** [from]'s pixels as RGBA, rows as the stage holds them (bottom first); free it with MemoryUtil.memFree. */
    private fun read(from: Target): ByteBuffer {
        val buffer = MemoryUtil.memAlloc(from.width * from.height * 4)
        glBindFramebuffer(GL_FRAMEBUFFER, from.framebuffer)
        glPixelStorei(GL_PACK_ALIGNMENT, 1)
        glReadPixels(0, 0, from.width, from.height, GL_RGBA, GL_UNSIGNED_BYTE, buffer)
        return buffer
    }

    /** [rgb] (RGB bytes, rows as the stage holds them) into [into]. */
    private fun uploadRgb(into: Target, rgb: ByteBuffer) {
        glBindTexture(GL_TEXTURE_2D, into.texture)
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, into.width, into.height, GL_RGB, GL_UNSIGNED_BYTE, rgb)
    }

    // The enlarged picture, in half floats where this moment is dithered.
    private fun up(w: Int, h: Int): Target = target("up", w, h, half = deep)

    // [half]: 16-bit floats, for a network's layers, whose values reach past 0..1, and for a picture to be dithered;
    // [deep]: 16-bit values, for HDR's.
    private fun target(name: String, w: Int, h: Int, half: Boolean = false, deep: Boolean = false): Target =
        targets.getOrPut(name) { StageFrame(gl, Gles.RGBA8) }.ensure(w, h, half = half, deep = deep)

    // The stage holds its frames with the picture's top row where v is 1, as on Android: rows uploaded bottom first,
    // only those of [region] (inside the black bars, the 3D eye), which [into] is the size of — so the peak below is
    // measured on the picture alone too. An HDR picture goes into a 16-bit frame of its own, its peak is measured,
    // and it is tone-mapped into [into].
    // [region] of [picture] out of [whole], the picture drawn from its planes (its top row where v is 1), into [into].
    private fun copyRegion(whole: Target, into: Target, picture: MediaReader.Picture, region: PictureCrop) {
        val pw = picture.width.toFloat()
        val ph = picture.height.toFloat()
        val within = floatArrayOf(
            region.width / pw, 0f, 0f, 0f,
            0f, region.height / ph, 0f, 0f,
            0f, 0f, 1f, 0f,
            region.left / pw, 1f - (region.top + region.height) / ph, 0f, 1f,
        )
        // A copy: the blend's other input is the same picture (see copy).
        finishing.copy(whole, into, within)
    }

    private fun upload(into: Target, picture: MediaReader.Picture, region: PictureCrop) {
        val w = into.width
        val h = into.height
        val left = region.left
        val top = region.top
        val transfer = picture.hdr
        val bytes = if (transfer == null) 4 else 8
        val buffer = MemoryUtil.memAlloc(w * h * bytes)
        for (y in 0 until h) buffer.put(picture.rgba, ((top + h - 1 - y) * picture.width + left) * bytes, w * bytes)
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
        conversion.peak(coded, blocks, transfer)
        val count = blocks.width * blocks.height
        val read = MemoryUtil.memAlloc(count * 4)
        glPixelStorei(GL_PACK_ALIGNMENT, 1)
        glReadPixels(0, 0, blocks.width, blocks.height, GL_RGBA, GL_UNSIGNED_BYTE, read)
        peakDetect.update(PeakDetect.unpack(ByteArray(count * 4).also { read.get(it) }, count))
        MemoryUtil.memFree(read)
        val tone = ToneMap.pass(transfer, peakDetect.peak)
        conversion.toneMap(coded, into, tone)
    }

    /**
     * [picture] in RGB at its own size from its 4:2:0 [planes], the chroma enlarged by the [chroma] chosen (see
     * ChromaUpscaling) — spline36 in one pass, KrigBilateral in three — as the Android stage does with the FFmpeg
     * decoder's planes; the frame holds the picture's top row where v is 1, as uploaded pictures are held.
     */
    private fun chromaUpscaled(picture: MediaReader.Picture, planes: MediaReader.Planes): Target {
        val w = picture.width
        val h = picture.height
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        if (planeTextures[0] == 0) {
            glGenTextures(planeTextures)
            planeTextures.forEach {
                glBindTexture(GL_TEXTURE_2D, it)
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            }
        }
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        listOf(Triple(planes.y, w, h), Triple(planes.u, cw, ch), Triple(planes.v, cw, ch)).forEachIndexed { i, plane ->
            val bytes = MemoryUtil.memAlloc(plane.first.size).put(plane.first).flip()
            glBindTexture(GL_TEXTURE_2D, planeTextures[i])
            glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, plane.second, plane.third, 0, GL_RED, GL_UNSIGNED_BYTE, bytes)
            MemoryUtil.memFree(bytes)
        }
        // Rows from the top in the planes, the picture's top row where v is 1 in the frames: turned round.
        val flip = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)
        val colour = if (planes.fullRange) planes.matrix.toRgbFull() else planes.matrix.toRgb()
        val foot = if (planes.fullRange) 0f else 16f / 255f
        val yuv = Conversion.Planes(
            planeTextures[0], planeTextures[1], planeTextures[2], w, h, w, cw, colour, foot, flip,
        )
        val hooked = planeShader?.takeIf { it.hooksChroma || lumaOnPlanes }
        if (hooked != null) return planesHooked(hooked, w, h, flip, colour, foot)
        val whole = target("chroma-whole", w, h)
        if (chroma == PictureAdjustment.Chroma.SPLINE36) return conversion.spline(yuv, whole) as Target
        val low = target("chroma-low", w, ch, half = true)
        return conversion.krig(yuv, low, target("chroma-lower", cw, ch, half = true), whole) as Target
    }

    /**
     * The [w] × [h] planes just uploaded through [shader]'s plane hooks, as mpv runs them (MpvHooks.planes): `LUMA` on
     * the Y plane where [lumaOnPlanes], `CHROMA` on the Cb and Cr planes as one texture at their own size, both rows
     * from the top as mpv holds them; then the hooked Y and chroma (the chroma read bilinearly where the passes left
     * it smaller) to RGB by [colour] and [foot] at the hooked Y's size, its top row where v is 1 ([flip]) as uploaded
     * pictures are held. Where no `CHROMA` pass ran, the chroma planes are enlarged to the hooked Y's size by the
     * [chroma] upscaler chosen, as mpv's `--cscale` (KrigBilateral guided by the hooked Y).
     */
    private fun planesHooked(
        shader: MpvShader,
        w: Int,
        h: Int,
        flip: FloatArray,
        colour: FloatArray,
        foot: Float,
    ): Target {
        val luma = SampledTexture(planeTextures[0], w, h)
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val upscaler = if (chroma == PictureAdjustment.Chroma.BILINEAR) {
            null
        } else {
            { y: DrawTarget, whole: DrawTarget ->
                val yuv = Conversion.Planes(
                    y.texture, planeTextures[1], planeTextures[2], y.width, y.height, y.width, cw, colour, foot, flip,
                    chromaWidth = cw, chromaHeight = ch,
                )
                if (chroma == PictureAdjustment.Chroma.SPLINE36) {
                    conversion.spline(yuv, whole)
                } else {
                    val low = target("chroma-low", y.width, ch, half = true)
                    conversion.krig(yuv, low, target("chroma-lower", cw, ch, half = true), whole)
                }
            }
        }
        return mpvHooks.planes(
            shader, luma, planeTextures[1], planeTextures[2], Passes.IDENTITY, MpvHooks.Sizes(hookOutW, hookOutH, w, h),
            lumaOnPlanes, colour, foot, flip, upscaler,
        ) { width, height -> target("chroma-whole", width, height) } as Target
    }

    /** [from] read back for the window as [presentation] says, timed into [presentNanos]. */
    private fun readBack(from: Target, ptsMs: Long): MediaReader.Picture {
        val began = System.nanoTime()
        val picture = when (presentation) {
            Presentation.SYNC -> download(from, ptsMs)
            Presentation.PBO, Presentation.PBO_LAGGED, Presentation.NATIVE -> throughBuffers(from, ptsMs)
        }
        presentNanos = System.nanoTime() - began
        return picture
    }

    /**
     * [from] read back into the next pixel-buffer object, fenced; the read-backs [readbackRing] says are due collected —
     * this one at once under Presentation.PBO, the one before under PBO_LAGGED — and the newest handed out.
     */
    private fun throughBuffers(from: Target, ptsMs: Long): MediaReader.Picture {
        val w = from.width
        val h = from.height
        val slot = readbackRing.start()
        if (pbos[slot] == 0) pbos[slot] = glGenBuffers()
        glBindBuffer(GL_PIXEL_PACK_BUFFER, pbos[slot])
        if (pboBytes[slot] < w * h * 4) {
            glBufferData(GL_PIXEL_PACK_BUFFER, w.toLong() * h * 4, GL_STREAM_READ)
            pboBytes[slot] = w * h * 4
        }
        glBindFramebuffer(GL_FRAMEBUFFER, from.framebuffer)
        glPixelStorei(GL_PACK_ALIGNMENT, 1)
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, 0L)
        pboFences[slot] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        pboPictures[slot] = longArrayOf(w.toLong(), h.toLong(), ptsMs)
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0)
        glFlush()
        while (readbackRing.mustCollect) collect(readbackRing.finish())
        return checkNotNull(handedOut)
    }

    /** The read-back in pixel-buffer object [slot] waited for, mapped, its rows turned top first: [handedOut]. */
    private fun collect(slot: Int) {
        val fence = pboFences[slot]
        while (glClientWaitSync(fence, GL_SYNC_FLUSH_COMMANDS_BIT, FENCE_WAIT_NANOS) == GL_TIMEOUT_EXPIRED) Unit
        glDeleteSync(fence)
        val (w, h) = pboPictures[slot]!!.let { it[0].toInt() to it[1].toInt() }
        val ptsMs = pboPictures[slot]!![2]
        glBindBuffer(GL_PIXEL_PACK_BUFFER, pbos[slot])
        val mapped = checkNotNull(glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0, w.toLong() * h * 4, GL_MAP_READ_BIT))
        val rgba = ByteArray(w * h * 4)
        for (y in 0 until h) {
            mapped.position((h - 1 - y) * w * 4)
            mapped.get(rgba, y * w * 4, w * 4)
        }
        glUnmapBuffer(GL_PIXEL_PACK_BUFFER)
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0)
        handedOut = MediaReader.Picture(ptsMs, w, h, rgba)
    }

    /**
     * [from] copied into the next of the textures shared with the window, fenced; posted to the stage's own window
     * where one is open, once the graphics card is done with that window's last draw from the texture.
     */
    private fun share(from: Target, ptsMs: Long): SharedPicture {
        val shownIn = pictureWindow
        val slot = shownIn?.queue?.writeSlot() ?: sharedNext.also { sharedNext = (it + 1) % SHARED_TEXTURES }
        shownIn?.beforeWrite(slot)
        val size = sharedSizes[slot]
        if (shared[slot] == 0 || size == null || size[0] != from.width || size[1] != from.height) {
            if (shared[slot] != 0) glDeleteTextures(shared[slot])
            shared[slot] = glGenTextures()
            glBindTexture(GL_TEXTURE_2D, shared[slot])
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, from.width, from.height)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            sharedSizes[slot] = intArrayOf(from.width, from.height)
        }
        val began = System.nanoTime()
        glCopyImageSubData(
            from.texture, GL_TEXTURE_2D, 0, 0, 0, 0, shared[slot], GL_TEXTURE_2D, 0, 0, 0, 0, from.width, from.height, 1,
        )
        val fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        glFlush()
        presentNanos = System.nanoTime() - began
        sharedPts[slot] = ptsMs
        sharedLast = slot
        return SharedPicture(slot, shared[slot], from.width, from.height, fence, ptsMs).also { shownIn?.post(it) }
    }

    private fun download(from: DrawTarget, ptsMs: Long): MediaReader.Picture {
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

    override fun close() {
        thread.submit {
            interpolatorOpen?.close()
            enlargerOpen?.close()
            colouring.close()
            // The window showing the shared textures goes first; they outlive this context where a window shares its
            // objects.
            pictureWindow?.close()
            pictureWindow = null
            if (ready) {
                pbos.filter { it != 0 }.forEach(::glDeleteBuffers)
                shared.filter { it != 0 }.forEach(::glDeleteTextures)
                if (sharedRead != 0) glDeleteFramebuffers(sharedRead)
            }
            if (window != 0L) GLFW.glfwDestroyWindow(window)
            out?.let(MemoryUtil::memFree)
        }.get()
        thread.shutdown()
        motion.close()
        denoiseMotion.close()
    }

    private companion object {
        val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

        // The pixel-buffer objects pictures are read back through, and how long one wait on a fence lasts.
        const val PBO_BUFFERS = 2
        const val FENCE_WAIT_NANOS = 100_000_000L

        /** How many textures a sharing window is handed in turn: one drawn, one shown, one spare. */
        const val SHARED_TEXTURES = PictureWindow.SLOTS

        /** Every how many columns Adaptive and Old console weigh a picture's luma, as on Android. */
        const val ADAPTIVE_COLUMN_STEP = 4
        // Temporal denoising's vector textures kept: the pairs either side of the shown picture and of the next.
        const val DENOISE_VECTORS = 6

        // What the stage hands on: 8-bit RGBA, read back for the screen.
        const val SCREEN_BITS = 8

        // The pictures held: uploaded — the one on screen, two before and two after, and one to spare — and
        // filtered — the one on screen and the next, both fields of one at field rate.
        const val UPLOADED = 6
        const val KEPT = 4
    }
}
