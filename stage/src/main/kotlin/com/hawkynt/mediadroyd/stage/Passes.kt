package com.hawkynt.mediadroyd.stage

import com.hawkynt.mediadroyd.shaders.Shaders
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Where a pass draws: a [texture]'s [framebuffer], or the window's (framebuffer 0, no texture of its own). */
interface DrawTarget {
    val texture: Int
    val framebuffer: Int
    val width: Int
    val height: Int
}

/** The window's own framebuffer at [width] × [height], as a pass's target. */
class WindowTarget(override val width: Int, override val height: Int) : DrawTarget {
    override val texture: Int get() = 0
    override val framebuffer: Int get() = 0
}

/**
 * A texture of [width] × [height] made elsewhere (a network's output, a frame of the platform's), as a pass reads it;
 * never drawn into.
 */
class SampledTexture(override val texture: Int, override val width: Int, override val height: Int) : DrawTarget {
    override val framebuffer: Int get() = 0
}

/**
 * The picture stage's way of running a pass, on any platform's [gl]: a fragment shader of :stage's (Shaders) drawn
 * over the whole of a target, reading textures. Each fragment shader's program is built once, beside the stage's
 * vertex shader of its GLSL version. Every pass is handed the film grain [grain] and its [seed] (glsl/video's
 * `uGrain`, `uSeed`: drawn on the screen's last pass, 0 elsewhere).
 */
class Passes(
    private val gl: Gles,
    private val grain: () -> Float = { 0f },
    private val seed: () -> Float = { 0f },
) {
    private val programs = HashMap<String, Int>()
    private val tables = ArrayList<Int>()

    /**
     * The program of [fragment] beside :stage's vertex shader of its version (GLSL ES 3.00's for one that starts
     * `#version 300 es`, else GLSL ES 1.00's), built on first use; an exception where it does not compile or link.
     */
    fun program(fragment: String): Int = programs[fragment] ?: build(fragment).also { programs[fragment] = it }

    private fun build(fragment: String): Int {
        val vertex = if (fragment.startsWith("#version 300 es")) Shaders.VIDEO_VERTEX_ES3 else Shaders.VIDEO_VERTEX
        return link(vertex, fragment)
    }

    /**
     * [vertex] and [fragment] compiled and linked as a program, its quad's attributes at 0 (`aPosition`) and 1
     * (`aTexCoord`); an exception where either does not compile or the two do not link.
     */
    fun link(vertex: String, fragment: String): Int {
        fun compile(type: Int, source: String): Int = gl.createShader(type).also {
            gl.shaderSource(it, source)
            gl.compileShader(it)
            check(gl.shaderCompiled(it)) { "Shader: " + gl.shaderInfoLog(it) }
        }
        return gl.createProgram().also {
            gl.attachShader(it, compile(Gles.VERTEX_SHADER, vertex))
            gl.attachShader(it, compile(Gles.FRAGMENT_SHADER, fragment))
            gl.bindAttribLocation(it, 0, "aPosition")
            gl.bindAttribLocation(it, 1, "aTexCoord")
            gl.linkProgram(it)
            check(gl.programLinked(it)) { "Program: " + gl.programInfoLog(it) }
        }
    }

    /** [source] compiled and linked as a compute program (OpenGL ES 3.1); an exception where it does not build. */
    fun linkCompute(source: String): Int {
        val shader = gl.createShader(Gles.COMPUTE_SHADER)
        gl.shaderSource(shader, source)
        gl.compileShader(shader)
        check(gl.shaderCompiled(shader)) { "Shader: " + gl.shaderInfoLog(shader) }
        return gl.createProgram().also {
            gl.attachShader(it, shader)
            gl.linkProgram(it)
            check(gl.programLinked(it)) { "Program: " + gl.programInfoLog(it) }
        }
    }

    /**
     * [fragment] drawn over the whole of [into], reading [input] as `uTex` on unit 0 and each of [extra] (a texture
     * and its sampler's name) on the units after it — linearly, or with [nearest] every one of them texel by texel (for
     * the passes that read exact pixels; linear again afterwards). The vertex shader maps the quad's texture
     * coordinates through [matrix]; [uniforms] sets the pass's own.
     */
    fun draw(
        fragment: String,
        into: DrawTarget,
        input: Int,
        extra: List<Pair<Int, String>> = emptyList(),
        nearest: Boolean = false,
        matrix: FloatArray = IDENTITY,
        uniforms: Uniforms.() -> Unit = {},
    ): DrawTarget {
        val p = program(fragment)
        gl.bindFramebuffer(into.framebuffer)
        gl.viewport(0, 0, into.width, into.height)
        gl.useProgram(p)
        val bound = listOf(input to "uTex") + extra
        bound.forEachIndexed { unit, (texture, name) ->
            gl.activeTexture(unit)
            gl.bindTexture(Gles.TEXTURE_2D, texture)
            if (unit == 0 || nearest) sampling(if (nearest) Gles.NEAREST else Gles.LINEAR)
            gl.uniform1i(gl.uniformLocation(p, name), unit)
        }
        val u = Uniforms(gl, p)
        u.matrix4("uTexMatrix", matrix)
        u.set("uGrain", grain())
        u.set("uSeed", seed())
        u.uniforms()
        gl.drawQuad(p)
        if (nearest) {
            bound.forEachIndexed { unit, (texture, _) ->
                gl.activeTexture(unit)
                gl.bindTexture(Gles.TEXTURE_2D, texture)
                sampling(Gles.LINEAR)
            }
        }
        gl.activeTexture(0)
        return into
    }

    private fun sampling(filter: Int) {
        gl.texParameteri(Gles.TEXTURE_2D, Gles.TEXTURE_MIN_FILTER, filter)
        gl.texParameteri(Gles.TEXTURE_2D, Gles.TEXTURE_MAG_FILTER, filter)
    }

    /** A table of [width] × [height] RGBA bytes ([rgba], rows from the bottom) as a texture read texel by texel. */
    fun table(width: Int, height: Int, rgba: ByteArray): Int {
        val texture = gl.genTexture()
        gl.bindTexture(Gles.TEXTURE_2D, texture)
        gl.pixelStorei(Gles.UNPACK_ALIGNMENT, 1)
        val bytes = ByteBuffer.allocateDirect(rgba.size).order(ByteOrder.nativeOrder()).put(rgba)
        bytes.position(0)
        gl.texImage2D(Gles.RGBA, width, height, Gles.RGBA, Gles.UNSIGNED_BYTE, bytes)
        sampling(Gles.NEAREST)
        gl.texParameteri(Gles.TEXTURE_2D, Gles.TEXTURE_WRAP_S, Gles.CLAMP_TO_EDGE)
        gl.texParameteri(Gles.TEXTURE_2D, Gles.TEXTURE_WRAP_T, Gles.CLAMP_TO_EDGE)
        tables += texture
        return texture
    }

    // The float tables' sizes and channels, by texture: refilled in place while they stay.
    private val floatShapes = HashMap<Int, Triple<Int, Int, Int>>()

    /**
     * A table of [width] × [height] floats with [channels] (1 or 4) a texel — [data]: native-order floats, rows from
     * the bottom — as a half-float texture read linearly, clamped at its edges; [reuse] (a table this made before, 0
     * for none) refilled where it has that shape, else a new one made in its place.
     */
    fun floats(reuse: Int, width: Int, height: Int, channels: Int, data: ByteBuffer): Int {
        val format = if (channels == 1) Gles.RED else Gles.RGBA
        val shape = Triple(width, height, channels)
        gl.pixelStorei(Gles.UNPACK_ALIGNMENT, 4)
        if (reuse != 0 && floatShapes[reuse] == shape) {
            gl.bindTexture(Gles.TEXTURE_2D, reuse)
            gl.texSubImage2D(width, height, format, Gles.FLOAT, data)
            return reuse
        }
        if (reuse != 0 && floatShapes.remove(reuse) != null) {
            tables.remove(reuse)
            gl.deleteTexture(reuse)
        }
        val texture = gl.genTexture()
        gl.bindTexture(Gles.TEXTURE_2D, texture)
        gl.texImage2D(if (channels == 1) Gles.R16F else Gles.RGBA16F, width, height, format, Gles.FLOAT, data)
        sampling(Gles.LINEAR)
        gl.texParameteri(Gles.TEXTURE_2D, Gles.TEXTURE_WRAP_S, Gles.CLAMP_TO_EDGE)
        gl.texParameteri(Gles.TEXTURE_2D, Gles.TEXTURE_WRAP_T, Gles.CLAMP_TO_EDGE)
        tables += texture
        floatShapes[texture] = shape
        return texture
    }

    /** Deletes the programs built and the tables made, before the context goes; built anew if used again. */
    fun release() {
        programs.values.forEach(gl::deleteProgram)
        programs.clear()
        tables.forEach(gl::deleteTexture)
        tables.clear()
        floatShapes.clear()
    }

    /** A pass's uniforms on [program], by name; a name the program does not use is passed over. */
    class Uniforms internal constructor(private val gl: Gles, val program: Int) {
        private fun at(name: String) = gl.uniformLocation(program, name)
        fun set(name: String, x: Float) = gl.uniform1f(at(name), x)
        fun set(name: String, x: Float, y: Float) = gl.uniform2f(at(name), x, y)
        fun set(name: String, x: Float, y: Float, z: Float) = gl.uniform3f(at(name), x, y, z)
        fun set(name: String, x: Float, y: Float, z: Float, w: Float) = gl.uniform4f(at(name), x, y, z, w)
        fun setInt(name: String, x: Int) = gl.uniform1i(at(name), x)
        fun setInt(name: String, x: Int, y: Int) = gl.uniform2i(at(name), x, y)
        fun setInt(name: String, x: Int, y: Int, z: Int, w: Int) = gl.uniform4i(at(name), x, y, z, w)
        fun matrix3(name: String, m: FloatArray) = gl.uniformMatrix3fv(at(name), m)
        fun matrix4(name: String, m: FloatArray) = gl.uniformMatrix4fv(at(name), m)
    }

    companion object {
        /** The texture coordinates as they are. */
        val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
    }
}
