package com.hawkynt.mediadroid.engine.desktop

import com.hawkynt.mediadroid.imaging.Xbrz
import com.hawkynt.mediadroid.player.Colorizing
import com.hawkynt.mediadroid.player.Deblock
import com.hawkynt.mediadroid.player.Deinterlacer
import com.hawkynt.mediadroid.player.DotCrawl
import com.hawkynt.mediadroid.player.Dither
import com.hawkynt.mediadroid.player.Ewa
import com.hawkynt.mediadroid.player.MotionAdaptive
import com.hawkynt.mediadroid.player.MotionInterpolation
import com.hawkynt.mediadroid.player.MpvShader
import com.hawkynt.mediadroid.player.MpvShaders
import com.hawkynt.mediadroid.player.NeuralEnlarging
import com.hawkynt.mediadroid.player.NeuralInterpolation
import com.hawkynt.mediadroid.player.OldGame
import com.hawkynt.mediadroid.player.Nis
import com.hawkynt.mediadroid.player.PeakDetect
import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.player.PictureCrop
import com.hawkynt.mediadroid.player.ScreenWarp
import com.hawkynt.mediadroid.player.TemporalDenoise
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
    private val programs = HashMap<String, Int>()
    private val targets = HashMap<String, Target>()
    private var vao = 0
    private var nisTable = 0
    private var ewaTable = 0
    private var xbrzTable = 0
    private var obmcTable = 0
    private var noiseTable = 0

    // Whether this moment's passes draw into half floats for the last pass to dither (see run).
    private var deep = false

    // Debanding's frame index, which seeds its random numbers: libplacebo's 8-bit counter.
    private var debandFrame = 0

    // HDR's peak as measured over the pictures so far, and the transfer it was measured for.
    private val peakDetect = PeakDetect()
    private var peakTransfer: ToneMap.Transfer? = null
    private var out: ByteBuffer? = null

    // The pixel-buffer objects pictures are read back through (Presentation.PBO, PBO_LAGGED): each one's buffer, the
    // bytes it holds, its fence and what is being read into it; the picture handed out last.
    private val readbackRing = ReadbackRing(
        PBO_BUFFERS,
        if (presentation == Presentation.PBO_LAGGED) 1 else 0,
    )
    private val pbos = IntArray(PBO_BUFFERS)
    private val pboBytes = IntArray(PBO_BUFFERS)
    private val pboFences = LongArray(PBO_BUFFERS)
    private val pboPictures = arrayOfNulls<LongArray>(PBO_BUFFERS)
    private var handedOut: MediaReader.Picture? = null

    // The textures processTexture hands to the window in turn, and each one's size.
    private val shared = IntArray(SHARED_TEXTURES)
    private val sharedSizes = arrayOfNulls<IntArray>(SHARED_TEXTURES)
    private var sharedNext = 0

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

    /** A texture with its framebuffer; [format] its internal format (GL_RGBA8, GL_RGBA16F, GL_RGBA16). */
    private class Target(val texture: Int, val framebuffer: Int, val width: Int, val height: Int, val format: Int)

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
        nisTable = nisTable()
        ewaTable = ewaTable()
        xbrzTable = xbrzTable()
        obmcTable = obmcTable()
        noiseTable = noiseTable()
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
    val behind: Boolean get() = presentation == Presentation.PBO_LAGGED && readbackRing.inFlight > 0

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
     * ([shareWith]): one of [SHARED_TEXTURES] textures in turn, RGBA8, its bottom row the picture's top, and the fence
     * the window waits on (glWaitSync) before drawing it.
     */
    fun processTexture(moment: Moment, adjustment: PictureAdjustment, width: Int, height: Int): SharedPicture? =
        if (!ready || shareWith == 0L) {
            null
        } else {
            thread.submit<SharedPicture> { share(run(moment, adjustment, width, height, null, ScreenWarp.None)) }.get()
        }

    /** A texture the stage drew into for a sharing window ([processTexture]); [slot] which of them. */
    class SharedPicture(val slot: Int, val texture: Int, val width: Int, val height: Int, val fence: Long)

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

    // The mpv user shader whose CHROMA hooks the pictures are uploaded with (from their planes), and the size shown,
    // which its passes' sizes may read.
    private var chromaShader: MpvShader? = null
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
        val chromaHooked = user?.takeIf { it.hooksChroma }
        // Where the picture holds more than the 8 bits it is read back in — HDR's tone-mapped floats, the colour
        // correction's or debanding's results — every pass draws into half floats and the last one dithers (Dither);
        // a plain 8-bit picture keeps its 8-bit frames, untouched.
        deep = Dither.applies(if (m.shown.hdr != null) 16 else 8, SCREEN_BITS, a)
        // A change to the filters, to the part shown, or to the frames' depth makes every kept picture stale.
        val look = listOf(
            a.deinterlace, a.stereo, a.deband, a.noise, a.sharpness, a.clarity, crop, deep, a.chroma, a.colorize,
            a.logo, a.deblock, a.dering,
            a.temporalDenoise, a.dotCrawl, chromaHooked,
        )
        // Another chroma upscaler, or another shader hooking the chroma, makes every picture uploaded from its planes
        // stale.
        if (a.chroma != chroma || chromaHooked !== chromaShader) {
            chroma = a.chroma
            chromaShader = chromaHooked
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
        var source = filtered(m.shown, m.previous, next, a, second, m.after, m.earlier)
        if (blending && next != null) {
            val later = filtered(next, m.shown, m.after, a, false, earlier = m.previous)
            source = mixed(m, next, source, later, a)
        }
        // The mpv user shader's hooks before the picture is enlarged (its CHROMA ones ran as it was uploaded).
        if (user != null) {
            source = userHooks(user, source, MpvShader.BEFORE_SCALING, outW, outH, m.shown.width, m.shown.height)
        }
        // Enlarging to the shown size (at most twice where the upscaler is made for that, the rest bilinear), at the
        // strength chosen: below full strength the plain picture laid over it by that share.
        if (a.upscales && (outW > source.width || outH > source.height)) {
            val plain = source
            source = upscale(a, source, outW, outH)
            if (a.plainShare > 0f) {
                val upscaled = source
                source = pass("blend.frag", target("strength", outW, outH, half = deep), plain.texture,
                    listOf(upscaled.texture to "uPrevious")) { p ->
                    glUniform1f(glGetUniformLocation(p, "uWeight"), a.plainShare)
                }
            }
        }
        // The user shader's hooks on the enlarged picture, at the shown size.
        if (user != null && user.passes.any { p -> p.hooks.any { it in MpvShader.AFTER_SCALING } }) {
            if (source.width != outW || source.height != outH) {
                val scaled = target("mpvScaled", outW, outH, half = true)
                source = pass("blend.frag", scaled, source.texture, listOf(source.texture to "uPrevious")) { p ->
                    glUniform1f(glGetUniformLocation(p, "uWeight"), 1f)
                }
            }
            source = userHooks(user, source, MpvShader.AFTER_SCALING, outW, outH, m.shown.width, m.shown.height)
        }
        // To the shown size, colour-corrected, its geometry bent (ScreenWarp).
        val shown = target("shown", outW, outH, half = deep)
        val u = a.shaderUniforms()
        pass("adjust.frag", shown, source.texture) { p ->
            glUniform1f(glGetUniformLocation(p, "uBrightness"), u.brightness)
            glUniform1f(glGetUniformLocation(p, "uContrast"), u.contrast)
            glUniform1f(glGetUniformLocation(p, "uSaturation"), u.saturation)
            glUniform1f(glGetUniformLocation(p, "uInverseGamma"), u.inverseGamma)
            glUniform1f(glGetUniformLocation(p, "uPanorama"), warp.panorama)
            glUniformMatrix3fv(glGetUniformLocation(p, "uWarp"), false, warp.matrix)
            glUniform1f(glGetUniformLocation(p, "uWarped"), 1f)
        }
        if (!deep) return shown
        // Dithered to 8 bits, the last pass before the picture is read back: libplacebo's blue noise
        // (glsl/video/dither.glsl).
        val dithered = pass(
            "dither-es3.frag", target("dithered", outW, outH), shown.texture, listOf(noiseTable to "uNoise"),
        ) { p ->
            // The frame's top row is where v is 1 (see upload); libplacebo counts rows from the top.
            glUniform2f(glGetUniformLocation(p, "uRows"), outH.toFloat(), -1f)
        }
        return dithered
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
        val half = deep
        fun bwdif(into: String, a: Target, b: Target, c: Target, s: Boolean) =
            pass("temporal.frag", target(into, w, h, half = half), b.texture,
                listOf(a.texture to "uPrev", c.texture to "uNext"), nearest = true) { p ->
                glUniform2f(glGetUniformLocation(p, "uSize"), w.toFloat(), h.toFloat())
                glUniform1i(glGetUniformLocation(p, "uSecond"), if (s) 1 else 0)
                glUniform1f(glGetUniformLocation(p, "uParity"), Deinterlacer.rebuiltParity(s, true).toFloat())
                glUniform1i(glGetUniformLocation(p, "uMethod"), Deinterlacer.shaderMethod(Deinterlacer.Method.BWDIF))
            }
        val earlier = if (second) {
            bwdif("mc-earlier", prev, cur, next, false)
        } else {
            bwdif("mc-earlier", prev, prev, cur, true)
        }
        val later = if (second) {
            bwdif("mc-later", cur, next, further, false)
        } else {
            bwdif("mc-later", prev, cur, next, true)
        }
        val smoothed = pass("mc-smooth.frag", target("mc-smoothed", w, h, half = half), base.texture,
            listOf(earlier.texture to "uEarlier", later.texture to "uLater"), nearest = true) { p ->
            glUniform2f(glGetUniformLocation(p, "uSize"), w.toFloat(), h.toFloat())
        }
        return pass("mc-sharpen.frag", target("mc-sharpened", w, h, half = half), smoothed.texture,
            listOf(base.texture to "uBase", cur.texture to "uSource"), nearest = true) { p ->
            glUniform2f(glGetUniformLocation(p, "uSize"), w.toFloat(), h.toFloat())
            glUniform1f(glGetUniformLocation(p, "uParity"), Deinterlacer.rebuiltParity(second, true).toFloat())
        }
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
     * before [before].
     */
    private fun filtered(
        picture: MediaReader.Picture,
        before: MediaReader.Picture?,
        after: MediaReader.Picture?,
        a: PictureAdjustment,
        second: Boolean,
        beyond: MediaReader.Picture? = null,
        earlier: MediaReader.Picture? = null,
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
        // after it, as decoded.
        // A PAL capture (a picture every 40 ms) is combed with the picture two before as well.
        val combed = DotCrawl.threshold(a.dotCrawl)?.let { threshold ->
            val prev = neighbour(before ?: picture).takeIf { it.width == w && it.height == h } ?: raw
            val next = neighbour(after ?: picture).takeIf { it.width == w && it.height == h } ?: raw
            val intervalMs = before?.let { picture.ptsMs - it.ptsMs } ?: after?.let { it.ptsMs - picture.ptsMs } ?: 0L
            val pal = DotCrawl.Standard.forInterval(intervalMs.toFloat()) == DotCrawl.Standard.PAL
            val sides = if (pal) {
                val two = neighbour(earlier ?: picture).takeIf { it.width == w && it.height == h } ?: raw
                listOf(two.texture to "uEarlier", prev.texture to "uPrev", next.texture to "uNext")
            } else {
                listOf(prev.texture to "uPrev", next.texture to "uNext")
            }
            pass(if (pal) "dot-crawl-pal.frag" else "dot-crawl.frag", target("combed", w, h, half = deep), raw.texture,
                sides, nearest = true) { p ->
                glUniform2f(glGetUniformLocation(p, "uTexel"), 1f / w, 1f / h)
                glUniform1f(glGetUniformLocation(p, "uThreshold"), threshold)
            }
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
            source = pass("temporal.frag", target("deinterlaced", w, h, half = deep), shown.texture,
                listOf(prev.texture to "uPrev", next.texture to "uNext"), nearest = true) { p ->
                glUniform2f(glGetUniformLocation(p, "uSize"), w.toFloat(), h.toFloat())
                glUniform1i(glGetUniformLocation(p, "uSecond"), if (second) 1 else 0)
                glUniform1f(glGetUniformLocation(p, "uParity"), Deinterlacer.rebuiltParity(second, true).toFloat())
                val method = when {
                    a.deinterlace == PictureAdjustment.Deinterlace.OLD_CONSOLE -> Deinterlacer.OLD_CONSOLE
                    a.deinterlace == PictureAdjustment.Deinterlace.ADAPTIVE && motionAdaptive.weave ->
                        Deinterlacer.WEAVE
                    else -> Deinterlacer.shaderMethod(a.deinterlace.method ?: Deinterlacer.Method.YADIF)
                }
                glUniform1i(glGetUniformLocation(p, "uMethod"), method)
                val average = method == Deinterlacer.OLD_CONSOLE && consoleAverages(picture, before, second)
                glUniform1i(glGetUniformLocation(p, "uAverage"), if (average) 1 else 0)
            }
            if (a.deinterlace == PictureAdjustment.Deinterlace.SMOOTH_MC) {
                val further = neighbour(beyond ?: after ?: picture).takeIf { it.width == w && it.height == h } ?: next
                source = smoothAlongMotion(source, prev, shown, next, further, second)
            }
        } else if (a.deinterlace != PictureAdjustment.Deinterlace.OFF &&
            a.deinterlace != PictureAdjustment.Deinterlace.FILM
        ) {
            source = pass("deinterlace.frag", target("deinterlaced", w, h, half = deep), shown.texture) { p ->
                glUniform2f(glGetUniformLocation(p, "uTexel"), 1f / w, 1f / h)
                glUniform1i(glGetUniformLocation(p, "uMode"), a.deinterlace.ordinal)
                glUniform1f(glGetUniformLocation(p, "uHeight"), h.toFloat())
                glUniform1f(glGetUniformLocation(p, "uParity"), Deinterlacer.rebuiltParity(false, true).toFloat())
            }
        }
        // 1b. Deblocking and deringing, on the decoded picture's own block grid, which the part shown (its bars, its
        // 3D eye) may have cut into — before anything else changes the picture.
        val part = region(picture, crop, a.stereo)
        val (originX, originY) = Deblock.gridOrigin(part.left, part.top)
        a.deblockParams()?.let { d ->
            for (step in 0..2) {
                source = pass("deblock-es3.frag", target("deblocked$step", w, h, half = deep), source.texture) { p ->
                    glUniform2i(glGetUniformLocation(p, "uSize"), w, h)
                    glUniform2i(glGetUniformLocation(p, "uOrigin"), originX, originY)
                    glUniform1i(glGetUniformLocation(p, "uPass"), step)
                    glUniform1i(glGetUniformLocation(p, "uStrong"), if (d.filter == Deblock.Filter.STRONG) 1 else 0)
                    glUniform4i(
                        glGetUniformLocation(p, "uThresholds"),
                        Deblock.threshold(d.alpha), Deblock.threshold(d.beta),
                        Deblock.threshold(d.gamma), Deblock.threshold(d.delta),
                    )
                }
            }
        }
        val blocksWide = (w - originX) / 8
        val blocksHigh = (h - originY) / 8
        a.deringParams()?.takeIf { blocksWide > 0 && blocksHigh > 0 }?.let { d ->
            val blockTarget = target("cdefBlocks", blocksWide, blocksHigh)
            val blocks = pass("cdef-direction-es3.frag", blockTarget, source.texture) { p ->
                glUniform2i(glGetUniformLocation(p, "uSize"), w, h)
                glUniform2i(glGetUniformLocation(p, "uOrigin"), originX, originY)
                glUniform1i(glGetUniformLocation(p, "uPrimary"), d.primary)
            }
            source = pass("cdef-es3.frag", target("deringed", w, h, half = deep), source.texture,
                listOf(blocks.texture to "uDirections")) { p ->
                glUniform2i(glGetUniformLocation(p, "uSize"), w, h)
                glUniform2i(glGetUniformLocation(p, "uOrigin"), originX, originY)
                glUniform2i(glGetUniformLocation(p, "uBlocks"), blocksWide, blocksHigh)
                glUniform1i(glGetUniformLocation(p, "uSecondary"), d.secondary)
                glUniform1i(glGetUniformLocation(p, "uDamping"), d.damping)
            }
        }
        // 1c. Colour for a black-and-white picture, by the network whose test passed (see colored).
        if (a.colorize != PictureAdjustment.Colorize.OFF) source = colored(source)
        // 2. The station logo hidden, then deband, denoise and sharpen at the picture's own size.
        a.logo?.let { logo ->
            val rect = logo.rectangle(w, h)
            if (rect.left + rect.width < w && rect.top + rect.height < h) {
                val masked = target("logoMasked", w, h, half = deep)
                source = pass("logo-mask.frag", masked, source.texture, nearest = true) { p ->
                    glUniform2f(glGetUniformLocation(p, "uSize"), w.toFloat(), h.toFloat())
                    glUniform4f(
                        glGetUniformLocation(p, "uRect"),
                        rect.left.toFloat(), rect.top.toFloat(), rect.width.toFloat(), rect.height.toFloat(),
                    )
                    glUniform1i(glGetUniformLocation(p, "uMode"), logo.mode.ordinal)
                }
            }
        }
        a.debandParams()?.let { d ->
            debandFrame = (debandFrame + 1) and 0xFF
            source = pass("deband-es3.frag", target("debanded", w, h, half = deep), source.texture) { p ->
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
            source = pass("denoise.frag", target("denoised", w, h, half = deep), source.texture) { p ->
                glUniform2f(glGetUniformLocation(p, "uTexel"), 1f / w, 1f / h)
                glUniform1f(glGetUniformLocation(p, "uRadius"), k.radius)
                glUniform1f(glGetUniformLocation(p, "uSigmaS2"), 2f * k.radius * k.radius)
                glUniform1f(glGetUniformLocation(p, "uSigmaR2"), 2f * k.similarity * k.similarity)
            }
        }
        val slot = claim(kept, picture, region(picture, crop, a.stereo), second)
        slot.unsettled = unsettled
        val into = target(slot.name, w, h, half = deep)
        if (a.sharpness > 0f || a.clarity > 0f) {
            pass("sharpen.frag", into, source.texture) { p ->
                glUniform2f(glGetUniformLocation(p, "uTexel"), 1f / w, 1f / h)
                glUniform1f(glGetUniformLocation(p, "uSharpness"), a.sharpness)
                glUniform1f(glGetUniformLocation(p, "uClarity"), a.clarity)
            }
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
        val w = raw.width
        val h = raw.height
        fun mode(side: Pair<Target, MotionSearch.Vectors?>?) = when {
            side?.second == null -> 0f
            side.second!!.cut -> 2f
            else -> 1f
        }
        val prevVectors = prev?.second?.let(::vectorTexture) ?: obmcTable
        val nextVectors = next?.second?.let(::vectorTexture) ?: obmcTable
        val textures = listOf(
            (prev?.first ?: raw).texture to "uPrev",
            (next?.first ?: raw).texture to "uNext",
            prevVectors to "uPrevVectors",
            nextVectors to "uNextVectors",
            obmcTable to "uObmc",
        )
        val into = pass("temporal-denoise.frag", target("temporallyDenoised", w, h, half = deep), raw.texture,
            textures, nearest = true) { p ->
            glUniform1f(glGetUniformLocation(p, "uA"), TemporalDenoise.threshold(params.a).toFloat())
            glUniform1f(glGetUniformLocation(p, "uB"), TemporalDenoise.threshold(params.b).toFloat())
            glUniform2f(glGetUniformLocation(p, "uSize"), w.toFloat(), h.toFloat())
            glUniform1f(glGetUniformLocation(p, "uPrevMode"), mode(prev))
            glUniform1f(glGetUniformLocation(p, "uNextMode"), mode(next))
            for ((side, name) in listOf(prev to "Prev", next to "Next")) {
                val v = side?.second ?: continue
                glUniform2f(glGetUniformLocation(p, "u${name}Grid"), v.gridWidth.toFloat(), v.gridHeight.toFloat())
                glUniform1f(glGetUniformLocation(p, "u${name}Scale"), v.scale.toFloat())
            }
        }
        // The pass leaves what it read nearest at linear; the tables are read texel by texel.
        for (table in listOf(prevVectors, nextVectors, obmcTable)) {
            glBindTexture(GL_TEXTURE_2D, table)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        }
        return into
    }

    // [vectors] as a texture, kept for the few pictures around the one shown.
    private fun vectorTexture(vectors: MotionSearch.Vectors): Int = denoiseVectors.getOrPut(vectors) {
        val bytes = MemoryUtil.memAlloc(vectors.bytes.size).put(vectors.bytes).flip()
        table(vectors.gridWidth, vectors.gridHeight, bytes)
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
                return pass("blend.frag", target("mixed", earlier.width, earlier.height, half = deep), to.texture,
                    listOf(from.texture to "uPrevious")) {
                    glUniform1f(glGetUniformLocation(it, "uWeight"), mix.weight)
                }
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
        val along = vectors ?: return pass("blend.frag", into, later.texture, listOf(earlier.texture to "uPrevious")) {
            glUniform1f(glGetUniformLocation(it, "uWeight"), weight)
        }
        if (along !== vectorsShown) {
            if (vectorTexture != 0) glDeleteTextures(vectorTexture)
            val bytes = MemoryUtil.memAlloc(along.bytes.size).put(along.bytes).flip()
            vectorTexture = table(along.gridWidth, along.gridHeight, bytes)
            vectorsShown = along
        }
        val textures = listOf(earlier.texture to "uPrevious", vectorTexture to "uVectors", obmcTable to "uObmc")
        return pass("motion.frag", into, later.texture, textures) { p ->
            glUniform1f(glGetUniformLocation(p, "uAlpha"), (weight * MotionInterpolation.ALPHA_MAX).toInt().toFloat())
            glUniform2f(glGetUniformLocation(p, "uSize"), w.toFloat(), h.toFloat())
            glUniform2f(glGetUniformLocation(p, "uGrid"), along.gridWidth.toFloat(), along.gridHeight.toFloat())
            glUniform2f(glGetUniformLocation(p, "uSearch"), (w / along.scale).toFloat(), (h / along.scale).toFloat())
            glUniform1f(glGetUniformLocation(p, "uScale"), along.scale.toFloat())
        }
    }

    // The mpv user shaders' programs, by their source; and the frame counter and random number their passes are given.
    private val userPrograms = HashMap<String, Int>()
    private var hookFrame = 0
    private val hookRandom = java.util.Random(0)

    /**
     * [from] through [shader]'s passes hooking [stages], in mpv's order — the textures held the way mpv holds them, the
     * picture's top row first (turned round on the way in and out), in half floats; `LUMA` the picture's Y, put back
     * with its colour once its passes have run (glsl/video/mpv-luma.frag, mpv-recombine.frag). [outW] × [outH] is the
     * size shown (`OUTPUT`, `target_size`), [inputW] × [inputH] the video's (`input_size`). A pass whose `WHEN` does
     * not hold, or that reads a texture there is not, is left out; a pass that will not compile ends the shader there.
     */
    private fun userHooks(
        shader: MpvShader,
        from: Target,
        stages: List<String>,
        outW: Int,
        outH: Int,
        inputW: Int,
        inputH: Int,
    ): Target {
        if (stages.none { shader.planned(it).isNotEmpty() }) return from
        val turn = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)
        // A copy is a blend wholly of the one picture, the program's other input pointed at it too (see copy).
        fun turned(t: Target, name: String) = pass(
            "blend.frag", target(name, t.width, t.height, half = true), t.texture, listOf(t.texture to "uPrevious"),
        ) {
            glUniform1f(glGetUniformLocation(it, "uWeight"), 1f)
            glUniformMatrix4fv(glGetUniformLocation(it, "uTexMatrix"), false, turn)
        }
        val textures = HashMap<String, Target>()
        val writes = HashMap<String, Int>()
        // Every hook point but LUMA stands for the picture itself.
        val pictureNames = (MpvShader.BEFORE_SCALING + MpvShader.AFTER_SCALING) - "LUMA"
        fun picture(t: Target) = pictureNames.forEach { textures[it] = t }
        picture(turned(from, "mpvIn"))
        hookFrame++
        val sizes = HookSizes(outW, outH, inputW, inputH)
        fun runPass(stage: String, planned: MpvShader.Planned): Boolean =
            hookPass(stage, planned, textures, writes, sizes) { save, into ->
                if (save == stage && stage in pictureNames) picture(into) else textures[save] = into
            }
        run stages@{
            for (stage in stages) {
                val passes = shader.planned(stage)
                if (passes.isEmpty()) continue
                if (stage == "LUMA") {
                    val before = textures.getValue("MAIN")
                    val lumaTarget = target("mpvLuma", before.width, before.height, half = true)
                    textures["LUMA"] = pass("mpv-luma.frag", lumaTarget, before.texture)
                    if (!passes.all { runPass(stage, it) }) return@stages
                    val luma = textures.getValue("LUMA")
                    picture(pass("mpv-recombine.frag", target("mpvRecombined", luma.width, luma.height, half = true),
                        before.texture, listOf(luma.texture to "uLuma")))
                } else if (!passes.all { runPass(stage, it) }) {
                    return@stages
                }
            }
        }
        return turned(textures.getValue("MAIN"), "mpvOut")
    }

    /** The sizes an mpv user shader's passes are given: the size shown and the video's. */
    private class HookSizes(val outW: Int, val outH: Int, val inputW: Int, val inputH: Int)

    /**
     * One pass of an mpv user shader, [planned] on [stage], over [textures] by name (mpv's: the hooked one and those
     * saved), into a half-float frame of its own handed to [saved] with the name it saves under: a fragment pass drawn,
     * a compute pass dispatched in its blocks (as libplacebo's hook_hook dispatches it) with the frame as its
     * `out_image`. The shader's embedded textures are bound beside them under their own names. A pass whose `WHEN`
     * does not hold, or that reads a texture there is not, is left out (true); one that will not compile ends the
     * shader (false).
     */
    private fun hookPass(
        stage: String,
        planned: MpvShader.Planned,
        textures: Map<String, Target>,
        writes: MutableMap<String, Int>,
        sizes: HookSizes,
        saved: (String, Target) -> Unit,
    ): Boolean {
        val hooked = textures.getValue(stage)
        val known = textures.mapValues { MpvShader.Size(it.value.width, it.value.height) } +
            ("OUTPUT" to MpvShader.Size(sizes.outW, sizes.outH)) +
            ("HOOKED" to MpvShader.Size(hooked.width, hooked.height))
        if (!planned.runs(known)) return true
        val names = planned.textures(stage)
        if (names.any { it !in textures }) return true
        val size = planned.size(known)
        val compute = planned.pass.compute
        val program = runCatching {
            val source = planned.source(stage)
            if (compute != null) computeProgram(source) else userProgram(source)
        }.getOrNull() ?: return false
        val save = planned.pass.save?.takeIf { it != "HOOKED" } ?: stage
        val count = writes.merge(save, 1, Int::plus)!!
        val into = target("mpv:$save:${count % 2}", size.width, size.height, half = true)
        glUseProgram(program)
        names.forEachIndexed { unit, name ->
            val t = textures.getValue(name)
            glActiveTexture(GL_TEXTURE0 + unit)
            glBindTexture(GL_TEXTURE_2D, t.texture)
            // Read as libplacebo reads a hook's textures: linearly, clamped at the edges.
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glUniform1i(glGetUniformLocation(program, "${name}_raw"), unit)
            glUniform2f(glGetUniformLocation(program, "${name}_size"), t.width.toFloat(), t.height.toFloat())
        }
        val embedded = planned.userTextures()
        embedded.forEachIndexed { k, texture ->
            val unit = names.size + k
            glActiveTexture(GL_TEXTURE0 + unit)
            glBindTexture(if (texture.depth > 0) GL_TEXTURE_3D else GL_TEXTURE_2D, userTexture(planned.shader, texture))
            glUniform1i(glGetUniformLocation(program, texture.name), unit)
        }
        for (unit in names.size + embedded.size until 8) {
            glActiveTexture(GL_TEXTURE0 + unit)
            glBindTexture(GL_TEXTURE_2D, 0)
        }
        glActiveTexture(GL_TEXTURE0)
        glUniform2f(glGetUniformLocation(program, "mpvTarget"), into.width.toFloat(), into.height.toFloat())
        glUniform1i(glGetUniformLocation(program, "frame"), hookFrame)
        glUniform1f(glGetUniformLocation(program, "random"), hookRandom.nextFloat())
        glUniform2f(glGetUniformLocation(program, "input_size"), sizes.inputW.toFloat(), sizes.inputH.toFloat())
        glUniform2f(glGetUniformLocation(program, "target_size"), sizes.outW.toFloat(), sizes.outH.toFloat())
        glUniform2f(glGetUniformLocation(program, "tex_offset"), 0f, 0f)
        if (compute == null) {
            glBindFramebuffer(GL_FRAMEBUFFER, into.framebuffer)
            glViewport(0, 0, into.width, into.height)
            glBindVertexArray(vao)
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        } else {
            // As many blocks as cover the output, rounded up; what they store is read by the passes after.
            glBindImageTexture(0, into.texture, 0, false, 0, GL_WRITE_ONLY, GL_RGBA16F)
            glDispatchCompute(
                (into.width + compute.blockWidth - 1) / compute.blockWidth,
                (into.height + compute.blockHeight - 1) / compute.blockHeight,
                1,
            )
            glMemoryBarrier(
                GL_TEXTURE_FETCH_BARRIER_BIT or GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GL_FRAMEBUFFER_BARRIER_BIT,
            )
        }
        saved(save, into)
        return true
    }

    // The embedded textures of the shader last run, uploaded once (see userTexture).
    private val userTextures = HashMap<MpvShader.Texture, Int>()
    private var userTexturesOf: MpvShader? = null

    /**
     * [texture] of [shader] on the graphics card, uploaded the first time it is read: its texels as declared (8-bit
     * normalised, half or full floats; two or three dimensions), filtered and bordered as it says. Another shader's
     * make the last one's go.
     */
    private fun userTexture(shader: MpvShader, texture: MpvShader.Texture): Int {
        if (shader !== userTexturesOf) {
            userTextures.values.forEach { glDeleteTextures(it) }
            userTextures.clear()
            userTexturesOf = shader
        }
        return userTextures.getOrPut(texture) {
            val kind = if (texture.depth > 0) GL_TEXTURE_3D else GL_TEXTURE_2D
            val f = texture.format
            val layout = intArrayOf(GL_RED, GL_RG, GL_RGB, GL_RGBA)[f.components - 1]
            val internal = when {
                !f.float -> intArrayOf(GL_R8, GL_RG8, GL_RGB8, GL_RGBA8)
                f.heldBits == 16 -> intArrayOf(GL_R16F, GL_RG16F, GL_RGB16F, GL_RGBA16F)
                else -> intArrayOf(GL_R32F, GL_RG32F, GL_RGB32F, GL_RGBA32F)
            }[f.components - 1]
            val type = if (!f.float) GL_UNSIGNED_BYTE else if (f.bits == 16) GL_HALF_FLOAT else GL_FLOAT
            val id = glGenTextures()
            glBindTexture(kind, id)
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
            val bytes = MemoryUtil.memAlloc(texture.data.size).put(texture.data).flip()
            if (texture.depth > 0) {
                glTexImage3D(kind, 0, internal, texture.width, texture.height, texture.depth, 0, layout, type, bytes)
            } else {
                glTexImage2D(kind, 0, internal, texture.width, texture.height, 0, layout, type, bytes)
            }
            MemoryUtil.memFree(bytes)
            val filter = if (texture.linear) GL_LINEAR else GL_NEAREST
            glTexParameteri(kind, GL_TEXTURE_MIN_FILTER, filter)
            glTexParameteri(kind, GL_TEXTURE_MAG_FILTER, filter)
            val wrap = when (texture.border) {
                MpvShader.Border.CLAMP -> GL_CLAMP_TO_EDGE
                MpvShader.Border.REPEAT -> GL_REPEAT
                MpvShader.Border.MIRROR -> GL_MIRRORED_REPEAT
            }
            listOf(GL_TEXTURE_WRAP_S, GL_TEXTURE_WRAP_T, GL_TEXTURE_WRAP_R).forEach { glTexParameteri(kind, it, wrap) }
            glBindTexture(kind, 0)
            id
        }
    }

    /** A user shader's pass as a program, built once: its fragment shader with the stage's vertex shader beside it. */
    private fun userProgram(fragment: String): Int = userPrograms.getOrPut(fragment) {
        val version = fragment.substringBefore('\n')
        val vertex = version + "\n" + Shaders.VIDEO_VERTEX_ES3.substringAfter('\n')
        glCreateProgram().also {
            glAttachShader(it, compiled(GL_VERTEX_SHADER, vertex))
            glAttachShader(it, compiled(GL_FRAGMENT_SHADER, fragment))
            glBindAttribLocation(it, 0, "aPosition")
            glBindAttribLocation(it, 1, "aTexCoord")
            glLinkProgram(it)
            check(glGetProgrami(it, GL_LINK_STATUS) != 0) { "mpv shader: " + glGetProgramInfoLog(it) }
        }
    }

    /** A user shader's compute pass as a program, built once. */
    private fun computeProgram(source: String): Int = userPrograms.getOrPut(source) {
        glCreateProgram().also {
            glAttachShader(it, compiled(GL_COMPUTE_SHADER, source))
            glLinkProgram(it)
            check(glGetProgrami(it, GL_LINK_STATUS) != 0) { "mpv shader: " + glGetProgramInfoLog(it) }
        }
    }

    private fun compiled(type: Int, source: String): Int = glCreateShader(type).also {
        glShaderSource(it, source)
        glCompileShader(it)
        check(glGetShaderi(it, GL_COMPILE_STATUS) != 0) { "mpv shader: " + glGetShaderInfoLog(it) }
    }

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
        val into = target(slot.name, region.width, region.height, half = half)
        upload(into, picture, region)
        return into
    }

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
        pass("blend.frag", into, from.texture, listOf(from.texture to "uPrevious")) { p ->
            glUniform1f(glGetUniformLocation(p, "uWeight"), 1f)
        }
    }

    private fun upscale(picture: PictureAdjustment, from: Target, outW: Int, outH: Int): Target {
        val upscaler = picture.upscaler
        val w = from.width
        val h = from.height
        val inSize: (Int) -> Unit = { p -> glUniform2f(glGetUniformLocation(p, "uInSize"), w.toFloat(), h.toFloat()) }
        val midW = minOf(outW, w * 2)
        val midH = minOf(outH, h * 2)
        return when (upscaler) {
            PictureAdjustment.Upscaler.LANCZOS -> pass("lanczos.frag", up(outW, outH), from.texture) { p ->
                inSize(p)
                glUniform1f(glGetUniformLocation(p, "uAntiring"), ANTIRING)
            }
            PictureAdjustment.Upscaler.GSR -> pass("sgsr.frag", up(outW, outH), from.texture, body = inSize)
            PictureAdjustment.Upscaler.FSR -> {
                val easu = pass("fsr-easu-es3.frag", target("easu", outW, outH, half = deep), from.texture,
                    body = inSize)
                pass("fsr-rcas-es3.frag", up(outW, outH), easu.texture) { p ->
                    glUniform2f(glGetUniformLocation(p, "uTexel"), 1f / outW, 1f / outH)
                    glUniform1f(glGetUniformLocation(p, "uSharp"), 2f.pow(-0.2f))
                }
            }
            PictureAdjustment.Upscaler.EWA -> pass("ewa.frag", up(midW, midH), from.texture,
                listOf(ewaTable to "uTable")) { p ->
                inSize(p)
                glUniform1f(glGetUniformLocation(p, "uRadius"), Ewa.RADIUS.toFloat())
                glUniform1f(glGetUniformLocation(p, "uAntiring"), ANTIRING)
            }
            PictureAdjustment.Upscaler.NIS -> pass("nis.frag", up(midW, midH), from.texture,
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
            else -> NeuralEnlarging.choice(picture)?.takeIf { DesktopNeural.onNcnn(upscaler) }
                ?.let { enlarged(it, from) } ?: from
        }
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
        val into = target("inBetween", size.width, size.height)
        if (betweenFor?.first === shown && betweenFor?.second === next) return into
        val a = read(scaled(earlier, "netEarlier", size))
        val b = read(scaled(later, "netLater", size))
        val rgb = net.run(a, b, size.width, size.height, idOf(shown), idOf(next))
        MemoryUtil.memFree(a)
        MemoryUtil.memFree(b)
        rgb ?: return null
        uploadRgb(into, rgb)
        betweenFor = shown to next
        return into
    }

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

    // Colouring (Colorizing): DDColor runs on a thread of its own, on the latest picture at 256 x 256 whenever it is
    // free, so playback never waits on it; its results are smoothed over time and laid under every picture, a cut
    // dropping the colour until the new scene's has come.
    private val colorThread = Executors.newSingleThreadExecutor { r ->
        Thread(r, "colorize").apply { isDaemon = true }
    }
    private var colorJob: java.util.concurrent.Future<*>? = null
    private var colorizerOpen: DesktopNeural.Colorizer? = null
    private var colorizerFor: Pair<Neural, Boolean>? = null
    private var colorLast: FloatArray? = null
    private var colorCutAt = 0L
    private var colorRuns = 0L
    private val colorSmoother = Colorizing.Smoother()

    // A finished run's colour and the cut count it was started at, handed from the colour thread.
    @Volatile private var colorDone: Pair<FloatArray, Long>? = null

    /** [from] in colour where the colouriser's test passed and a result is in; [from] itself otherwise. */
    private fun colored(from: Target): Target {
        val n = neural ?: return from
        val gpu = n.colorizer?.unit?.let { it == "GPU" } ?: return from
        val size = Colorizing.SIZE
        val small = target("colorSmall", size, size)
        copy(from, small)
        val rgba = read(small)
        // Read back bottom row first, as the stage holds pictures; the network wants the top first.
        val top = ByteBuffer.allocateDirect(size * size * 4)
        for (y in 0 until size) {
            rgba.limit((size - y) * size * 4).position((size - 1 - y) * size * 4)
            top.put(rgba)
        }
        MemoryUtil.memFree(rgba)
        top.flip()
        val grey = Colorizing.input(top, size, size)
        if (Colorizing.cut(colorLast, grey)) {
            colorCutAt = ++colorRuns
            colorSmoother.clear()
        }
        colorLast = grey
        colorDone?.let { (ab, cutAt) ->
            colorDone = null
            // A result from before the last cut belongs to the scene before.
            if (cutAt == colorCutAt) {
                colorSmoother.add(ab, cut = colorSmoother.current == null)
                uploadAb(colorSmoother.current!!)
            }
        }
        if (colorJob?.isDone != false) {
            val key = n to gpu
            val cutAt = colorCutAt
            colorJob = colorThread.submit {
                if (colorizerFor != key) {
                    colorizerOpen?.close()
                    colorizerOpen = n.networks.colorizer(gpu)
                    colorizerFor = key
                }
                colorizerOpen?.run(grey)?.let { colorDone = it to cutAt }
            }
        }
        if (colorSmoother.current == null) return from
        return pass("colorize.frag", target("colored", from.width, from.height, half = deep), from.texture,
            listOf(target("colorAb", size, size, half = true).texture to "uAb"))
    }

    // The smoothed colour into the a-and-b texture: its rows turned back to the stage's, bottom first.
    private fun uploadAb(ab: FloatArray) {
        val size = Colorizing.SIZE
        val plane = size * size
        val texels = MemoryUtil.memAllocFloat(plane * 4)
        for (y in 0 until size) for (x in 0 until size) {
            val at = (size - 1 - y) * size + x
            texels.put(ab[at]).put(ab[plane + at]).put(0f).put(1f)
        }
        texels.flip()
        glBindTexture(GL_TEXTURE_2D, target("colorAb", size, size, half = true).texture)
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, size, size, GL_RGBA, GL_FLOAT, texels)
        MemoryUtil.memFree(texels)
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
        return pass("anime4k/depth-to-space.frag", up(w * 2, h * 2), from.texture,
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
        return pass("acnet/pixel-shuffle.frag", up(w * 2, h * 2), from.texture,
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
        return pass("xbrz/scale.frag", up(w * factor, h * factor), from.texture,
            listOf(decisions.texture to "uDecisions", xbrzTable to "uTable"), nearest = true) { p ->
            size(p)
            glUniform1f(glGetUniformLocation(p, "uFactor"), factor.toFloat())
        }
    }

    // The enlarged picture, in half floats where this moment is dithered.
    private fun up(w: Int, h: Int): Target = target("up", w, h, half = deep)

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

    // [half]: 16-bit floats, for a network's layers, whose values reach past 0..1, and for a picture to be dithered;
    // [deep]: 16-bit values, for HDR's.
    private fun target(name: String, w: Int, h: Int, half: Boolean = false, deep: Boolean = false): Target {
        val format = if (half) GL_RGBA16F else if (deep) GL_RGBA16 else GL_RGBA8
        targets[name]?.let { if (it.width == w && it.height == h && it.format == format) return it else free(it) }
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
        return Target(texture, framebuffer, w, h, format).also { targets[name] = it }
    }

    private fun free(t: Target) {
        glDeleteFramebuffers(t.framebuffer)
        glDeleteTextures(t.texture)
    }

    // The stage holds its frames with the picture's top row where v is 1, as on Android: rows uploaded bottom first,
    // only those of [region] (inside the black bars, the 3D eye), which [into] is the size of — so the peak below is
    // measured on the picture alone too. An HDR picture goes into a 16-bit frame of its own, its peak is measured,
    // and it is tone-mapped into [into].
    private fun upload(into: Target, picture: MediaReader.Picture, region: PictureCrop) {
        val w = into.width
        val h = into.height
        val left = region.left
        val top = region.top
        val transfer = picture.hdr
        val planes = picture.planes
        val fromPlanes = chroma != PictureAdjustment.Chroma.BILINEAR || chromaShader != null
        if (transfer == null && planes != null && fromPlanes) {
            // The whole picture from its planes, then the region out of it (its top row where v is 1).
            val whole = chromaUpscaled(picture, planes)
            val pw = picture.width.toFloat()
            val ph = picture.height.toFloat()
            val within = floatArrayOf(
                w / pw, 0f, 0f, 0f,
                0f, h / ph, 0f, 0f,
                0f, 0f, 1f, 0f,
                left / pw, 1f - (top + h) / ph, 0f, 1f,
            )
            // A copy: the blend's other input is the same picture (see copy).
            pass("blend.frag", into, whole.texture, listOf(whole.texture to "uPrevious")) { p ->
                glUniform1f(glGetUniformLocation(p, "uWeight"), 1f)
                glUniformMatrix4fv(glGetUniformLocation(p, "uTexMatrix"), false, within)
            }
            return
        }
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
        val chromaPlanes = listOf(planeTextures[1] to "uU", planeTextures[2] to "uV")
        val common: (Int) -> Unit = { p ->
            glUniformMatrix4fv(glGetUniformLocation(p, "uTexMatrix"), false, flip)
            glUniformMatrix3fv(glGetUniformLocation(p, "uColor"), false, colour)
            glUniform1f(glGetUniformLocation(p, "uFoot"), if (planes.fullRange) 0f else 16f / 255f)
        }
        val whole = target("chroma-whole", w, h)
        chromaShader?.let { return chromaHooked(it, planes, whole, flip, colour) }
        if (chroma == PictureAdjustment.Chroma.SPLINE36) {
            return pass("yuv-spline.frag", whole, planeTextures[0], chromaPlanes) { p ->
                common(p)
                glUniform1f(glGetUniformLocation(p, "uChroma"), 1f)
                glUniform2f(glGetUniformLocation(p, "uChromaSize"), cw.toFloat(), ch.toFloat())
                glUniform1f(glGetUniformLocation(p, "uChromaWidth"), cw.toFloat())
            }
        }
        val first = pass("chroma-krig-1.frag", target("chroma-low", w, ch, half = true), planeTextures[0]) { p ->
            glUniformMatrix4fv(glGetUniformLocation(p, "uTexMatrix"), false, flip)
            glUniform2f(glGetUniformLocation(p, "uLumaSize"), w.toFloat(), h.toFloat())
            glUniform1f(glGetUniformLocation(p, "uChromaHeight"), ch.toFloat())
        }
        val second = pass("chroma-krig-2.frag", target("chroma-lower", cw, ch, half = true), first.texture) { p ->
            glUniform1f(glGetUniformLocation(p, "uLumaWidth"), w.toFloat())
            glUniform1f(glGetUniformLocation(p, "uChromaWidth"), cw.toFloat())
        }
        return pass("chroma-krig-3.frag", whole, planeTextures[0], chromaPlanes + (second.texture to "uLow")) { p ->
            common(p)
            glUniform1f(glGetUniformLocation(p, "uLumaStride"), w.toFloat())
            glUniform2f(glGetUniformLocation(p, "uLumaSize"), w.toFloat(), h.toFloat())
            glUniform2f(glGetUniformLocation(p, "uChromaSize"), cw.toFloat(), ch.toFloat())
        }
    }

    /**
     * The planes just uploaded through [shader]'s CHROMA hooks, as mpv runs them: `CHROMA` the Cb and Cr planes as
     * one texture at their own size, `LUMA` the Y plane, both rows from the top as mpv holds them; then the Y plane
     * and the hooked chroma — read bilinearly where the passes left it smaller than the picture — to RGB by [colour]
     * into [whole], its top row where v is 1 ([flip]) as uploaded pictures are held.
     */
    private fun chromaHooked(
        shader: MpvShader,
        planes: MediaReader.Planes,
        whole: Target,
        flip: FloatArray,
        colour: FloatArray,
    ): Target {
        val w = whole.width
        val h = whole.height
        val textures = HashMap<String, Target>()
        textures["LUMA"] = Target(planeTextures[0], 0, w, h, GL_R8)
        textures[MpvShader.CHROMA] = pass(
            "mpv-chroma.frag", target("mpvChroma", (w + 1) / 2, (h + 1) / 2, half = true), planeTextures[1],
            listOf(planeTextures[2] to "uV"),
        )
        hookFrame++
        val writes = HashMap<String, Int>()
        val sizes = HookSizes(hookOutW, hookOutH, w, h)
        for (planned in shader.planned(MpvShader.CHROMA)) {
            val ran = hookPass(MpvShader.CHROMA, planned, textures, writes, sizes) { save, into ->
                textures[save] = into
            }
            if (!ran) break
        }
        val chroma = textures.getValue(MpvShader.CHROMA)
        return pass("mpv-chroma-rgb.frag", whole, planeTextures[0], listOf(chroma.texture to "uChroma")) { p ->
            glUniformMatrix4fv(glGetUniformLocation(p, "uTexMatrix"), false, flip)
            glUniformMatrix3fv(glGetUniformLocation(p, "uColor"), false, colour)
            glUniform1f(glGetUniformLocation(p, "uFoot"), if (planes.fullRange) 0f else 16f / 255f)
        }
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

    /** [from] read back for the window as [presentation] says, timed into [presentNanos]. */
    private fun readBack(from: Target, ptsMs: Long): MediaReader.Picture {
        val began = System.nanoTime()
        val picture = when (presentation) {
            Presentation.SYNC -> download(from, ptsMs)
            Presentation.PBO, Presentation.PBO_LAGGED -> throughBuffers(from, ptsMs)
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

    /** [from] copied into the next of the textures shared with the window, fenced. */
    private fun share(from: Target): SharedPicture {
        val slot = sharedNext
        sharedNext = (sharedNext + 1) % SHARED_TEXTURES
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
        return SharedPicture(slot, shared[slot], from.width, from.height, fence)
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

    // The overlapped blocks' weights (MotionInterpolation.obmcWeight) as a 32 × 32 texture, in red.
    private fun obmcTable(): Int {
        val bytes = MemoryUtil.memAlloc(32 * 32 * 4)
        for (i in 0 until 32 * 32) bytes.put(MotionInterpolation.obmcWeight(i).toByte()).put(0).put(0).put(0)
        bytes.flip()
        return table(32, 32, bytes)
    }

    // libplacebo's blue-noise matrix for the dither pass, its ranks over red and green (Dither.noiseRgba).
    private fun noiseTable(): Int {
        val rgba = Dither.noiseRgba()
        val bytes = MemoryUtil.memAlloc(rgba.size)
        bytes.put(rgba).flip()
        return table(Dither.SIZE, Dither.SIZE, bytes)
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
            interpolatorOpen?.close()
            enlargerOpen?.close()
            colorThread.submit { colorizerOpen?.close() }
            // The buffers and shared textures outlive this context where a window shares its objects.
            if (ready) {
                pbos.filter { it != 0 }.forEach(::glDeleteBuffers)
                shared.filter { it != 0 }.forEach(::glDeleteTextures)
            }
            if (window != 0L) GLFW.glfwDestroyWindow(window)
            out?.let(MemoryUtil::memFree)
        }.get()
        thread.shutdown()
        colorThread.shutdown()
        motion.close()
        denoiseMotion.close()
    }

    private companion object {
        val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        const val ANTIRING = 0.5f
        const val NIS_SHARPNESS = 0.5f
        const val XBRZ_TABLE_WIDTH = 36

        // The pixel-buffer objects pictures are read back through, and how long one wait on a fence lasts.
        const val PBO_BUFFERS = 2
        const val FENCE_WAIT_NANOS = 100_000_000L

        /** How many textures a sharing window is handed in turn: one drawn, one shown, one spare. */
        const val SHARED_TEXTURES = 3

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
        val ANIME4K = (1..4).map { "anime4k/conv-$it.frag" }
        val ACNET = (1..11).map { "acnet/conv-$it.frag" }
        val SHADERS = mapOf(
            "temporal.frag" to Shaders.VIDEO_TEMPORAL,
            "mc-smooth.frag" to Shaders.VIDEO_MC_SMOOTH,
            "mc-sharpen.frag" to Shaders.VIDEO_MC_SHARPEN,
            "colorize.frag" to Shaders.VIDEO_COLORIZE,
            "deinterlace.frag" to Shaders.VIDEO_DEINTERLACE,
            "deband-es3.frag" to Shaders.VIDEO_DEBAND_ES3,
            "logo-mask.frag" to Shaders.VIDEO_LOGO_MASK,
            "deblock-es3.frag" to Shaders.VIDEO_DEBLOCK_ES3,
            "cdef-direction-es3.frag" to Shaders.VIDEO_CDEF_DIRECTION_ES3,
            "cdef-es3.frag" to Shaders.VIDEO_CDEF_ES3,
            "mpv-luma.frag" to Shaders.VIDEO_MPV_LUMA,
            "mpv-recombine.frag" to Shaders.VIDEO_MPV_RECOMBINE,
            "mpv-chroma.frag" to Shaders.VIDEO_MPV_CHROMA,
            "mpv-chroma-rgb.frag" to Shaders.VIDEO_MPV_CHROMA_RGB,
            "temporal-denoise.frag" to Shaders.VIDEO_TEMPORAL_DENOISE,
            "dot-crawl.frag" to Shaders.VIDEO_DOT_CRAWL,
            "dot-crawl-pal.frag" to Shaders.VIDEO_DOT_CRAWL_PAL,
            "dither-es3.frag" to Shaders.VIDEO_DITHER_ES3,
            "yuv-spline.frag" to Shaders.VIDEO_YUV_SPLINE,
            "chroma-krig-1.frag" to Shaders.VIDEO_CHROMA_KRIG_1,
            "chroma-krig-2.frag" to Shaders.VIDEO_CHROMA_KRIG_2,
            "chroma-krig-3.frag" to Shaders.VIDEO_CHROMA_KRIG_3,
            "tonemap.frag" to Shaders.VIDEO_TONEMAP,
            "peak.frag" to Shaders.VIDEO_PEAK,
            "denoise.frag" to Shaders.VIDEO_DENOISE,
            "sharpen.frag" to Shaders.VIDEO_SHARPEN,
            "adjust.frag" to Shaders.VIDEO_ADJUST,
            "blend.frag" to Shaders.VIDEO_BLEND,
            "motion.frag" to Shaders.VIDEO_MOTION,
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
