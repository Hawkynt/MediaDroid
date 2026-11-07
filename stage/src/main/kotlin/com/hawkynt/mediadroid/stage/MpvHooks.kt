package com.hawkynt.mediadroid.stage

import com.hawkynt.mediadroid.player.MpvShader
import com.hawkynt.mediadroid.shaders.Shaders
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The mpv user shaders' runner (MpvShader), as mpv runs them: their passes in mpv's order over the textures held the
 * way mpv holds them, the picture's top row first, in half floats. The platform hands over its frames by name
 * ([frame]: half floats, where [compute] passes run held as immutable storage, which an image a compute pass writes
 * needs) and says whether compute passes run at all.
 */
class MpvHooks(
    private val gl: Gles,
    private val passes: Passes,
    private val finishing: Finishing,
    private val compute: () -> Boolean,
    private val frame: (name: String, width: Int, height: Int) -> DrawTarget,
) {
    // The passes' programs by their source; the frame counter and random number they are given.
    private val programs = HashMap<String, Int>()
    private var hookFrame = 0
    private val random = java.util.Random(0)

    /**
     * The sizes a shader's passes are given: [outWidth] × [outHeight] the size shown (`OUTPUT`, `target_size`),
     * [inputWidth] × [inputHeight] the video's (`input_size`).
     */
    class Sizes(val outWidth: Int, val outHeight: Int, val inputWidth: Int, val inputHeight: Int)

    /**
     * [from] through [shader]'s passes hooking [stages], in mpv's order — turned round on the way in and out; `LUMA`
     * the picture's Y, put back with its colour once its passes have run (glsl/video/mpv-luma.frag,
     * mpv-recombine.frag): the fallback where the stage has no planes to run it on ([planes]), as a hardware decoder's
     * RGB. A pass whose `WHEN` does not hold, or that reads a texture there is not, is left out; a pass that will not
     * build ends the shader there.
     */
    fun run(shader: MpvShader, from: DrawTarget, stages: List<String>, sizes: Sizes): DrawTarget {
        if (stages.none { shader.planned(it).isNotEmpty() }) return from
        fun turned(t: DrawTarget, name: String) = finishing.copy(t, frame(name, t.width, t.height), TURN)
        val textures = HashMap<String, DrawTarget>()
        val writes = HashMap<String, Int>()
        // Every hook point but LUMA stands for the picture itself.
        val pictureNames = (MpvShader.BEFORE_SCALING + MpvShader.AFTER_SCALING) - "LUMA"
        fun picture(t: DrawTarget) = pictureNames.forEach { textures[it] = t }
        picture(turned(from, "mpvIn"))
        hookFrame++
        fun runPass(stage: String, planned: MpvShader.Planned): Boolean =
            pass(stage, planned, textures, writes, sizes) { save, into ->
                if (save == stage && stage in pictureNames) picture(into) else textures[save] = into
            }
        run stages@{
            for (stage in stages) {
                val planned = shader.planned(stage)
                if (planned.isEmpty()) continue
                if (stage == "LUMA") {
                    val before = textures.getValue("MAIN")
                    textures["LUMA"] = passes.draw(
                        Shaders.VIDEO_MPV_LUMA, frame("mpvLuma", before.width, before.height), before.texture,
                    )
                    if (!planned.all { runPass(stage, it) }) return@stages
                    val luma = textures.getValue("LUMA")
                    picture(passes.draw(Shaders.VIDEO_MPV_RECOMBINE, frame("mpvRecombined", luma.width, luma.height),
                        before.texture, listOf(luma.texture to "uLuma")))
                } else if (!planned.all { runPass(stage, it) }) {
                    return@stages
                }
            }
        }
        return turned(textures.getValue("MAIN"), "mpvOut")
    }

    /**
     * A 4:2:0 picture's planes through [shader]'s plane hooks ([MpvShader.PLANES]), as libplacebo's renderer runs
     * them on a YCbCr frame (pass_read_image): `LUMA` on the decoded Y plane [luma] where [lumaHooks] says so, then
     * `CHROMA` on the Cb and Cr planes ([cb], [cr], read through [chromaMatrix]) as one texture at their own size, its
     * passes binding the `LUMA` hooked before them; both rows from the top as mpv holds them. Then the hooked Y and
     * chroma to RGB by [colour] and its [foot] at the hooked Y's size (the chroma read bilinearly where the passes
     * left it smaller) into the frame [into] hands over for that size, read through [matrix].
     */
    fun planes(
        shader: MpvShader,
        luma: DrawTarget,
        cb: Int,
        cr: Int,
        chromaMatrix: FloatArray,
        sizes: Sizes,
        lumaHooks: Boolean,
        colour: FloatArray,
        foot: Float,
        matrix: FloatArray,
        into: (width: Int, height: Int) -> DrawTarget,
    ): DrawTarget {
        val textures = HashMap<String, DrawTarget>()
        textures["LUMA"] = luma
        textures[MpvShader.CHROMA] = passes.draw(
            Shaders.VIDEO_MPV_CHROMA, frame("mpvChroma", (luma.width + 1) / 2, (luma.height + 1) / 2), cb,
            listOf(cr to "uV"), matrix = chromaMatrix,
        )
        hookFrame++
        val writes = HashMap<String, Int>()
        for (stage in MpvShader.PLANES) {
            if (stage == "LUMA" && !lumaHooks) continue
            for (planned in shader.planned(stage)) {
                if (!pass(stage, planned, textures, writes, sizes) { save, frame -> textures[save] = frame }) break
            }
        }
        val y = textures.getValue("LUMA")
        val chroma = textures.getValue(MpvShader.CHROMA)
        return passes.draw(Shaders.VIDEO_MPV_CHROMA_RGB, into(y.width, y.height), y.texture,
            listOf(chroma.texture to "uChroma"), matrix = matrix) {
            matrix3("uColor", colour)
            set("uFoot", foot)
        }
    }

    /**
     * The size [shader]'s `LUMA` passes leave a [width] × [height] Y plane at, worked out as they would run with
     * [sizes] (each pass's `WHEN` and `WIDTH` / `HEIGHT` over the textures saved before it) — for a stage whose frames
     * keep the picture's size, which runs them on the planes only where that size stays.
     */
    fun lumaSize(shader: MpvShader, width: Int, height: Int, sizes: Sizes): MpvShader.Size {
        val known = HashMap<String, MpvShader.Size>()
        known["LUMA"] = MpvShader.Size(width, height)
        known[MpvShader.CHROMA] = MpvShader.Size((width + 1) / 2, (height + 1) / 2)
        for (planned in shader.planned("LUMA")) {
            val now = known + ("OUTPUT" to MpvShader.Size(sizes.outWidth, sizes.outHeight)) +
                ("HOOKED" to known.getValue("LUMA"))
            if (!planned.runs(now) || planned.textures("LUMA").any { it !in known }) continue
            known[planned.pass.save?.takeIf { it != "HOOKED" } ?: "LUMA"] = planned.size(now)
        }
        return known.getValue("LUMA")
    }

    /**
     * One pass of an mpv user shader, [planned] on [stage], over [textures] by name (mpv's: the hooked one and those
     * saved), into a frame of its own handed to [saved] with the name it saves under: a fragment pass drawn, a compute
     * pass dispatched in its blocks (as libplacebo's hook_hook dispatches it) with the frame as its `out_image`. The
     * shader's embedded textures are bound beside them under their own names. A pass whose `WHEN` does not hold, or
     * that reads a texture there is not, is left out (true); one that will not build ends the shader (false).
     */
    private fun pass(
        stage: String,
        planned: MpvShader.Planned,
        textures: Map<String, DrawTarget>,
        writes: MutableMap<String, Int>,
        sizes: Sizes,
        saved: (String, DrawTarget) -> Unit,
    ): Boolean {
        val hooked = textures.getValue(stage)
        val known = textures.mapValues { MpvShader.Size(it.value.width, it.value.height) } +
            ("OUTPUT" to MpvShader.Size(sizes.outWidth, sizes.outHeight)) +
            ("HOOKED" to MpvShader.Size(hooked.width, hooked.height))
        if (!planned.runs(known)) return true
        val names = planned.textures(stage)
        if (names.any { it !in textures }) return true
        val size = planned.size(known)
        val dispatch = planned.pass.compute
        if (dispatch != null && !compute()) return false
        val program = runCatching {
            val source = planned.source(stage)
            programs.getOrPut(source) {
                if (dispatch != null) passes.linkCompute(source) else passes.link(vertexFor(source), source)
            }
        }.getOrNull() ?: return false
        val save = planned.pass.save?.takeIf { it != "HOOKED" } ?: stage
        val count = writes.merge(save, 1, Int::plus)!!
        val into = frame("mpv:$save:${count % 2}", size.width, size.height)
        gl.useProgram(program)
        names.forEachIndexed { unit, name ->
            val t = textures.getValue(name)
            gl.activeTexture(unit)
            gl.bindTexture(Gles.TEXTURE_2D, t.texture)
            // Read as libplacebo reads a hook's textures: linearly, clamped at the edges.
            gl.texParameteri(Gles.TEXTURE_2D, Gles.TEXTURE_MIN_FILTER, Gles.LINEAR)
            gl.texParameteri(Gles.TEXTURE_2D, Gles.TEXTURE_MAG_FILTER, Gles.LINEAR)
            gl.uniform1i(gl.uniformLocation(program, "${name}_raw"), unit)
            gl.uniform2f(gl.uniformLocation(program, "${name}_size"), t.width.toFloat(), t.height.toFloat())
        }
        val embedded = planned.userTextures()
        embedded.forEachIndexed { k, texture ->
            val unit = names.size + k
            gl.activeTexture(unit)
            val kind = if (texture.dimensions == 3) Gles.TEXTURE_3D else Gles.TEXTURE_2D
            gl.bindTexture(kind, texture(planned.shader, texture))
            gl.uniform1i(gl.uniformLocation(program, texture.name), unit)
        }
        // The buffers at the binding points the source declares them at: a uniform block's set here (GLSL ES 3.00
        // has no binding qualifier), a storage block's in the source.
        val bound = planned.buffers()
        bound.forEachIndexed { binding, buffer ->
            val id = buffer(planned.shader, buffer)
            if (buffer.storage) {
                gl.bindBufferBase(Gles.SHADER_STORAGE_BUFFER, binding, id)
            } else {
                gl.uniformBlockBinding(program, buffer.name, binding)
                gl.bindBufferBase(Gles.UNIFORM_BUFFER, binding, id)
            }
        }
        for (unit in names.size + embedded.size until UNITS) {
            gl.activeTexture(unit)
            gl.bindTexture(Gles.TEXTURE_2D, 0)
        }
        gl.activeTexture(0)
        fun vec2(name: String, x: Float, y: Float) = gl.uniform2f(gl.uniformLocation(program, name), x, y)
        vec2("mpvTarget", into.width.toFloat(), into.height.toFloat())
        gl.uniform1i(gl.uniformLocation(program, "frame"), hookFrame)
        gl.uniform1f(gl.uniformLocation(program, "random"), random.nextFloat())
        vec2("input_size", sizes.inputWidth.toFloat(), sizes.inputHeight.toFloat())
        vec2("target_size", sizes.outWidth.toFloat(), sizes.outHeight.toFloat())
        vec2("tex_offset", 0f, 0f)
        if (dispatch == null) {
            gl.bindFramebuffer(into.framebuffer)
            gl.viewport(0, 0, into.width, into.height)
            gl.drawQuad(program)
        } else {
            // As many blocks as cover the output, rounded up; what they store is read by the passes after.
            gl.bindImageTexture(0, into.texture, Gles.RGBA16F)
            gl.dispatchCompute(
                (into.width + dispatch.blockWidth - 1) / dispatch.blockWidth,
                (into.height + dispatch.blockHeight - 1) / dispatch.blockHeight,
                1,
            )
            gl.memoryBarrier(
                Gles.TEXTURE_FETCH_BARRIER_BIT or Gles.SHADER_IMAGE_ACCESS_BARRIER_BIT or Gles.FRAMEBUFFER_BARRIER_BIT,
            )
        }
        // What a pass stored into a storage buffer is read by the passes after, and by the next frame's.
        if (bound.any { it.storage }) {
            gl.memoryBarrier(Gles.SHADER_STORAGE_BARRIER_BIT or Gles.UNIFORM_BARRIER_BIT)
        }
        saved(save, into)
        return true
    }

    // The buffers of the shader last run, made once and kept from frame to frame (see buffer).
    private val buffers = HashMap<MpvShader.Buffer, Int>()
    private var buffersFor: MpvShader? = null

    /**
     * [buffer] of [shader] on the graphics card, made the first time a pass binds it with its initial data (zeros for
     * a storage block that gives none, which keeps what the passes store in it from then on); another shader's make
     * the last one's go. Its size rounded up to whole vec4s, which a uniform block's std140 size is.
     */
    private fun buffer(shader: MpvShader, buffer: MpvShader.Buffer): Int {
        if (shader !== buffersFor) {
            buffers.values.forEach(gl::deleteBuffer)
            buffers.clear()
            buffersFor = shader
        }
        return buffers.getOrPut(buffer) {
            val id = gl.genBuffer()
            val bytes = ByteBuffer.allocateDirect((buffer.size + 15) / 16 * 16).order(ByteOrder.nativeOrder())
            bytes.put(buffer.data).position(0)
            gl.bufferData(if (buffer.storage) Gles.SHADER_STORAGE_BUFFER else Gles.UNIFORM_BUFFER, id, bytes)
            id
        }
    }

    // The embedded textures of the shader last run, uploaded once (see texture).
    private val uploaded = HashMap<MpvShader.Texture, Int>()
    private var uploadedFor: MpvShader? = null

    /**
     * [texture] of [shader] on the graphics card, uploaded the first time it is read: its texels as declared (8-bit
     * normalised, 16-bit normalised as full floats, unsigned integers, half or full floats; one dimension as a row, two
     * or three), filtered and bordered as it says. Another shader's make the last one's go.
     */
    private fun texture(shader: MpvShader, texture: MpvShader.Texture): Int {
        if (shader !== uploadedFor) {
            uploaded.values.forEach(gl::deleteTexture)
            uploaded.clear()
            uploadedFor = shader
        }
        return uploaded.getOrPut(texture) {
            val kind = if (texture.dimensions == 3) Gles.TEXTURE_3D else Gles.TEXTURE_2D
            val f = texture.format
            val c = f.components - 1
            val layout = if (f.integer) {
                intArrayOf(Gles.RED_INTEGER, Gles.RG_INTEGER, Gles.RGB_INTEGER, Gles.RGBA_INTEGER)[c]
            } else {
                intArrayOf(Gles.RED, Gles.RG, Gles.RGB, Gles.RGBA)[c]
            }
            val internal = when {
                f.integer && f.bits == 8 -> intArrayOf(Gles.R8UI, Gles.RG8UI, Gles.RGB8UI, Gles.RGBA8UI)
                f.integer -> intArrayOf(Gles.R16UI, Gles.RG16UI, Gles.RGB16UI, Gles.RGBA16UI)
                !f.float && f.bits == 8 -> intArrayOf(Gles.R8, Gles.RG8, Gles.RGB8, Gles.RGBA8)
                f.heldBits == 16 -> intArrayOf(Gles.R16F, Gles.RG16F, Gles.RGB16F, Gles.RGBA16F)
                else -> intArrayOf(Gles.R32F, Gles.RG32F, Gles.RGB32F, Gles.RGBA32F)
            }[c]
            val unorm16 = !f.float && !f.integer && f.bits == 16
            val type = when {
                f.bits == 8 -> Gles.UNSIGNED_BYTE
                f.integer -> Gles.UNSIGNED_SHORT
                f.float && f.bits == 16 -> Gles.HALF_FLOAT
                else -> Gles.FLOAT
            }
            // 16-bit normalised texels as the floats they stand for (OpenGL ES has no 16-bit normalised textures).
            val data = if (!unorm16) {
                texture.data
            } else {
                val words = ByteBuffer.wrap(texture.data).order(ByteOrder.LITTLE_ENDIAN)
                val floats = ByteBuffer.allocate(texture.data.size * 2).order(ByteOrder.nativeOrder())
                while (words.hasRemaining()) floats.putFloat((words.short.toInt() and 0xFFFF) / 65535f)
                floats.array()
            }
            val id = gl.genTexture()
            gl.bindTexture(kind, id)
            gl.pixelStorei(Gles.UNPACK_ALIGNMENT, 1)
            val bytes = ByteBuffer.allocateDirect(data.size).order(ByteOrder.nativeOrder())
            bytes.put(data).position(0)
            if (texture.dimensions == 3) {
                gl.texImage3D(internal, texture.width, texture.height, texture.depth, layout, type, bytes)
            } else {
                gl.texImage2D(internal, texture.width, texture.height, layout, type, bytes)
            }
            val filter = if (texture.linear) Gles.LINEAR else Gles.NEAREST
            gl.texParameteri(kind, Gles.TEXTURE_MIN_FILTER, filter)
            gl.texParameteri(kind, Gles.TEXTURE_MAG_FILTER, filter)
            val wrap = when (texture.border) {
                MpvShader.Border.CLAMP -> Gles.CLAMP_TO_EDGE
                MpvShader.Border.REPEAT -> Gles.REPEAT
                MpvShader.Border.MIRROR -> Gles.MIRRORED_REPEAT
            }
            listOf(Gles.TEXTURE_WRAP_S, Gles.TEXTURE_WRAP_T, Gles.TEXTURE_WRAP_R).forEach {
                gl.texParameteri(kind, it, wrap)
            }
            gl.bindTexture(kind, 0)
            id
        }
    }

    /** Deletes the programs built, the textures uploaded and the buffers made, before the context goes. */
    fun release() {
        programs.values.forEach(gl::deleteProgram)
        programs.clear()
        uploaded.values.forEach(gl::deleteTexture)
        uploaded.clear()
        uploadedFor = null
        buffers.values.forEach(gl::deleteBuffer)
        buffers.clear()
        buffersFor = null
    }

    companion object {
        /** Rows from the top turned round to rows from the bottom, and back. */
        val TURN = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)

        // The texture units a pass may have bound: those after its own are unbound.
        private const val UNITS = 8

        // A user shader's fragment beside the stage's GLSL ES 3.00 vertex shader, at the fragment's own version.
        private fun vertexFor(fragment: String) =
            fragment.substringBefore('\n') + "\n" + Shaders.VIDEO_VERTEX_ES3.substringAfter('\n')
    }
}
