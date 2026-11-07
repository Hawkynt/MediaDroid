package com.hawkynt.mediadroid.player

import android.opengl.GLES20
import com.hawkynt.mediadroid.stage.Gles
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * :stage's Gles on Android: android.opengl's OpenGL ES 2 and 3 in VideoFilterStage's EGL context, on its thread. The
 * quad's corners come from arrays on the processor, as the stage's own passes draw theirs.
 */
internal class AndroidGles : Gles {
    private val one = IntArray(1)

    override fun createShader(type: Int) = GLES20.glCreateShader(type)
    override fun shaderSource(shader: Int, source: String) = GLES20.glShaderSource(shader, source)
    override fun compileShader(shader: Int) = GLES20.glCompileShader(shader)
    override fun shaderCompiled(shader: Int): Boolean {
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, one, 0)
        return one[0] != 0
    }
    override fun shaderInfoLog(shader: Int): String = GLES20.glGetShaderInfoLog(shader)
    override fun createProgram() = GLES20.glCreateProgram()
    override fun attachShader(program: Int, shader: Int) = GLES20.glAttachShader(program, shader)
    override fun bindAttribLocation(program: Int, index: Int, name: String) =
        GLES20.glBindAttribLocation(program, index, name)
    override fun linkProgram(program: Int) = GLES20.glLinkProgram(program)
    override fun programLinked(program: Int): Boolean {
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, one, 0)
        return one[0] != 0
    }
    override fun programInfoLog(program: Int): String = GLES20.glGetProgramInfoLog(program)
    override fun deleteProgram(program: Int) = GLES20.glDeleteProgram(program)
    override fun useProgram(program: Int) = GLES20.glUseProgram(program)
    override fun uniformLocation(program: Int, name: String) = GLES20.glGetUniformLocation(program, name)
    override fun uniform1f(location: Int, x: Float) = GLES20.glUniform1f(location, x)
    override fun uniform2f(location: Int, x: Float, y: Float) = GLES20.glUniform2f(location, x, y)
    override fun uniform3f(location: Int, x: Float, y: Float, z: Float) = GLES20.glUniform3f(location, x, y, z)
    override fun uniform4f(location: Int, x: Float, y: Float, z: Float, w: Float) =
        GLES20.glUniform4f(location, x, y, z, w)
    override fun uniform1i(location: Int, x: Int) = GLES20.glUniform1i(location, x)
    override fun uniform2i(location: Int, x: Int, y: Int) = GLES20.glUniform2i(location, x, y)
    override fun uniform4i(location: Int, x: Int, y: Int, z: Int, w: Int) = GLES20.glUniform4i(location, x, y, z, w)
    override fun uniformMatrix3fv(location: Int, matrix: FloatArray) =
        GLES20.glUniformMatrix3fv(location, 1, false, matrix, 0)
    override fun uniformMatrix4fv(location: Int, matrix: FloatArray) =
        GLES20.glUniformMatrix4fv(location, 1, false, matrix, 0)
    override fun activeTexture(unit: Int) = GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
    override fun bindTexture(target: Int, texture: Int) = GLES20.glBindTexture(target, texture)
    override fun texParameteri(target: Int, name: Int, value: Int) = GLES20.glTexParameteri(target, name, value)
    override fun genTexture(): Int {
        GLES20.glGenTextures(1, one, 0)
        return one[0]
    }
    override fun deleteTexture(texture: Int) = GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
    override fun texImage2D(internalFormat: Int, width: Int, height: Int, format: Int, type: Int, pixels: ByteBuffer?) =
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, internalFormat, width, height, 0, format, type, pixels)
    override fun texSubImage2D(width: Int, height: Int, format: Int, type: Int, pixels: ByteBuffer) =
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height, format, type, pixels)
    override fun pixelStorei(name: Int, value: Int) = GLES20.glPixelStorei(name, value)
    override fun texImage3D(
        internalFormat: Int,
        width: Int,
        height: Int,
        depth: Int,
        format: Int,
        type: Int,
        pixels: ByteBuffer,
    ) = android.opengl.GLES30.glTexImage3D(
        android.opengl.GLES30.GL_TEXTURE_3D, 0, internalFormat, width, height, depth, 0, format, type, pixels,
    )
    override fun dispatchCompute(x: Int, y: Int, z: Int) = android.opengl.GLES31.glDispatchCompute(x, y, z)
    override fun bindImageTexture(unit: Int, texture: Int, format: Int) = android.opengl.GLES31.glBindImageTexture(
        unit, texture, 0, false, 0, android.opengl.GLES31.GL_WRITE_ONLY, format,
    )
    override fun memoryBarrier(bits: Int) = android.opengl.GLES31.glMemoryBarrier(bits)
    override fun genFramebuffer(): Int {
        GLES20.glGenFramebuffers(1, one, 0)
        return one[0]
    }
    override fun deleteFramebuffer(framebuffer: Int) = GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
    override fun framebufferTexture2D(texture: Int) = GLES20.glFramebufferTexture2D(
        GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0,
    )
    override fun readPixels(width: Int, height: Int, into: ByteBuffer) =
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, into)
    override fun bindFramebuffer(framebuffer: Int) = GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
    override fun viewport(x: Int, y: Int, width: Int, height: Int) = GLES20.glViewport(x, y, width, height)

    override fun drawQuad(program: Int) {
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        // A pass that works from gl_FragCoord alone has its texture coordinate optimised away.
        val coordinate = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, QUAD)
        if (coordinate >= 0) {
            GLES20.glEnableVertexAttribArray(coordinate)
            GLES20.glVertexAttribPointer(coordinate, 2, GLES20.GL_FLOAT, false, 0, QUAD_TEX)
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        if (coordinate >= 0) GLES20.glDisableVertexAttribArray(coordinate)
    }

    private companion object {
        val QUAD: FloatBuffer = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val QUAD_TEX: FloatBuffer = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        fun floats(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(values)
                position(0)
            }
    }
}
