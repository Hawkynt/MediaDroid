package com.hawkynt.mediadroid.stage

import java.nio.ByteBuffer

/**
 * The OpenGL ES calls the picture stage's passes make, so that the passes are written once and run on every platform:
 * on Android over android.opengl, on the desktop over LWJGL's OpenGL 4.3 (which compiles GLSL ES 1.00 and 3.00 as they
 * are). The enums are OpenGL's own values (see [Gles.Companion]), the same on both; one thread, whose context is
 * current, makes every call.
 */
interface Gles {
    fun createShader(type: Int): Int

    /** [source] as the shader's text; a platform that needs it (desktop OpenGL) adds what GLSL ES leaves implicit. */
    fun shaderSource(shader: Int, source: String)
    fun compileShader(shader: Int)
    fun shaderCompiled(shader: Int): Boolean
    fun shaderInfoLog(shader: Int): String
    fun createProgram(): Int
    fun attachShader(program: Int, shader: Int)
    fun bindAttribLocation(program: Int, index: Int, name: String)
    fun linkProgram(program: Int)
    fun programLinked(program: Int): Boolean
    fun programInfoLog(program: Int): String
    fun deleteProgram(program: Int)
    fun useProgram(program: Int)
    fun uniformLocation(program: Int, name: String): Int
    fun uniform1f(location: Int, x: Float)
    fun uniform2f(location: Int, x: Float, y: Float)
    fun uniform3f(location: Int, x: Float, y: Float, z: Float)
    fun uniform4f(location: Int, x: Float, y: Float, z: Float, w: Float)
    fun uniform1i(location: Int, x: Int)
    fun uniform2i(location: Int, x: Int, y: Int)
    fun uniform4i(location: Int, x: Int, y: Int, z: Int, w: Int)
    fun uniformMatrix3fv(location: Int, matrix: FloatArray)
    fun uniformMatrix4fv(location: Int, matrix: FloatArray)

    /** Makes texture unit [unit] (0, 1, …) the one [bindTexture] binds on. */
    fun activeTexture(unit: Int)
    fun bindTexture(target: Int, texture: Int)
    fun texParameteri(target: Int, name: Int, value: Int)
    fun genTexture(): Int
    fun deleteTexture(texture: Int)

    /** A 2D texture's storage, [pixels] its texels from the bottom row (null: left undefined). */
    fun texImage2D(internalFormat: Int, width: Int, height: Int, format: Int, type: Int, pixels: ByteBuffer?)

    /** A 3D texture's storage (an mpv user shader's own textures), [pixels] its texels layer by layer. */
    fun texImage3D(
        internalFormat: Int,
        width: Int,
        height: Int,
        depth: Int,
        format: Int,
        type: Int,
        pixels: ByteBuffer,
    )

    /** Runs the compute program in use over [x] × [y] × [z] work groups (OpenGL ES 3.1). */
    fun dispatchCompute(x: Int, y: Int, z: Int)

    /** [texture]'s level 0 as image unit [unit], written only, held as [format] (OpenGL ES 3.1). */
    fun bindImageTexture(unit: Int, texture: Int, format: Int)

    /** Waits until what the passes before wrote is seen by the reads [bits] name (OpenGL ES 3.1). */
    fun memoryBarrier(bits: Int)

    /** The bound 2D texture's texels from (0, 0) to [width] × [height] replaced by [pixels], from the bottom row. */
    fun texSubImage2D(width: Int, height: Int, format: Int, type: Int, pixels: ByteBuffer)
    fun pixelStorei(name: Int, value: Int)
    fun genFramebuffer(): Int
    fun deleteFramebuffer(framebuffer: Int)

    /** Binds [framebuffer] (0: the window's) for drawing. */
    fun bindFramebuffer(framebuffer: Int)

    /** [texture] as the bound framebuffer's colour. */
    fun framebufferTexture2D(texture: Int)

    /** The bound framebuffer's [width] × [height] pixels from (0, 0) as RGBA bytes into [into], bottom row first. */
    fun readPixels(width: Int, height: Int, into: ByteBuffer)
    fun viewport(x: Int, y: Int, width: Int, height: Int)

    /**
     * The quad over the whole target drawn with [program] in use: the vertex shader's `aPosition` from -1 to 1 and its
     * `aTexCoord` from 0 to 1, as a triangle strip.
     */
    fun drawQuad(program: Int)

    companion object {
        // OpenGL's enums (Khronos gl2.h / gl3.h): the same values in OpenGL ES and desktop OpenGL.
        const val VERTEX_SHADER = 0x8B31
        const val FRAGMENT_SHADER = 0x8B30
        const val TEXTURE_2D = 0x0DE1
        const val TEXTURE_MIN_FILTER = 0x2801
        const val TEXTURE_MAG_FILTER = 0x2800
        const val TEXTURE_WRAP_S = 0x2802
        const val TEXTURE_WRAP_T = 0x2803
        const val NEAREST = 0x2600
        const val LINEAR = 0x2601
        const val CLAMP_TO_EDGE = 0x812F
        const val RGBA = 0x1908
        const val RGBA16F = 0x881A
        const val UNSIGNED_BYTE = 0x1401
        const val HALF_FLOAT = 0x140B
        const val FLOAT = 0x1406
        const val UNPACK_ALIGNMENT = 0x0CF5
        const val PACK_ALIGNMENT = 0x0D05
        const val COMPUTE_SHADER = 0x91B9
        const val TEXTURE_3D = 0x806F
        const val TEXTURE_WRAP_R = 0x8072
        const val REPEAT = 0x2901
        const val MIRRORED_REPEAT = 0x8370
        const val RED = 0x1903
        const val RG = 0x8227
        const val RGB = 0x1907
        const val R8 = 0x8229
        const val RG8 = 0x822B
        const val RGB8 = 0x8051
        const val RGBA8 = 0x8058
        const val R16F = 0x822D
        const val RG16F = 0x822F
        const val RGB16F = 0x881B
        const val R32F = 0x822E
        const val RG32F = 0x8230
        const val RGB32F = 0x8815
        const val RGBA32F = 0x8814
        const val TEXTURE_FETCH_BARRIER_BIT = 0x00000008
        const val SHADER_IMAGE_ACCESS_BARRIER_BIT = 0x00000020
        const val FRAMEBUFFER_BARRIER_BIT = 0x00000400
    }
}
