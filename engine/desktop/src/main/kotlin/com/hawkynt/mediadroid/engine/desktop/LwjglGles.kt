package com.hawkynt.mediadroid.engine.desktop

import com.hawkynt.mediadroid.stage.Gles
import org.lwjgl.opengl.GL43.*
import java.nio.ByteBuffer

/**
 * :stage's Gles on the desktop: LWJGL's OpenGL 4.3 in the stage's context, which compiles the stage's GLSL ES 1.00 and
 * 3.00 as they are once a GLSL ES 1.00 shader says so (`#version 100`, which OpenGL ES takes as given). The quad is
 * the stage's vertex array [quad] (attributes 0 and 1, see GlStage).
 */
internal class LwjglGles(private val quad: () -> Int) : Gles {
    override fun createShader(type: Int) = glCreateShader(type)
    override fun shaderSource(shader: Int, source: String) =
        glShaderSource(shader, if (source.startsWith("#version")) source else "#version 100\n$source")
    override fun compileShader(shader: Int) = glCompileShader(shader)
    override fun shaderCompiled(shader: Int) = glGetShaderi(shader, GL_COMPILE_STATUS) != 0
    override fun shaderInfoLog(shader: Int): String = glGetShaderInfoLog(shader)
    override fun createProgram() = glCreateProgram()
    override fun attachShader(program: Int, shader: Int) = glAttachShader(program, shader)
    override fun bindAttribLocation(program: Int, index: Int, name: String) = glBindAttribLocation(program, index, name)
    override fun linkProgram(program: Int) = glLinkProgram(program)
    override fun programLinked(program: Int) = glGetProgrami(program, GL_LINK_STATUS) != 0
    override fun programInfoLog(program: Int): String = glGetProgramInfoLog(program)
    override fun deleteProgram(program: Int) = glDeleteProgram(program)
    override fun useProgram(program: Int) = glUseProgram(program)
    override fun uniformLocation(program: Int, name: String) = glGetUniformLocation(program, name)
    override fun uniform1f(location: Int, x: Float) = glUniform1f(location, x)
    override fun uniform2f(location: Int, x: Float, y: Float) = glUniform2f(location, x, y)
    override fun uniform3f(location: Int, x: Float, y: Float, z: Float) = glUniform3f(location, x, y, z)
    override fun uniform4f(location: Int, x: Float, y: Float, z: Float, w: Float) = glUniform4f(location, x, y, z, w)
    override fun uniform1i(location: Int, x: Int) = glUniform1i(location, x)
    override fun uniform2i(location: Int, x: Int, y: Int) = glUniform2i(location, x, y)
    override fun uniform4i(location: Int, x: Int, y: Int, z: Int, w: Int) = glUniform4i(location, x, y, z, w)
    override fun uniformMatrix3fv(location: Int, matrix: FloatArray) = glUniformMatrix3fv(location, false, matrix)
    override fun uniformMatrix4fv(location: Int, matrix: FloatArray) = glUniformMatrix4fv(location, false, matrix)
    override fun activeTexture(unit: Int) = glActiveTexture(GL_TEXTURE0 + unit)
    override fun bindTexture(target: Int, texture: Int) = glBindTexture(target, texture)
    override fun texParameteri(target: Int, name: Int, value: Int) = glTexParameteri(target, name, value)
    override fun genTexture() = glGenTextures()
    override fun deleteTexture(texture: Int) = glDeleteTextures(texture)
    override fun texImage2D(internalFormat: Int, width: Int, height: Int, format: Int, type: Int, pixels: ByteBuffer?) =
        glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, width, height, 0, format, type, pixels)
    override fun texSubImage2D(width: Int, height: Int, format: Int, type: Int, pixels: ByteBuffer) =
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, format, type, pixels)
    override fun pixelStorei(name: Int, value: Int) = glPixelStorei(name, value)
    override fun texImage3D(
        internalFormat: Int,
        width: Int,
        height: Int,
        depth: Int,
        format: Int,
        type: Int,
        pixels: ByteBuffer,
    ) = glTexImage3D(GL_TEXTURE_3D, 0, internalFormat, width, height, depth, 0, format, type, pixels)
    override fun texStorage2D(internalFormat: Int, width: Int, height: Int) =
        glTexStorage2D(GL_TEXTURE_2D, 1, internalFormat, width, height)
    override fun dispatchCompute(x: Int, y: Int, z: Int) = glDispatchCompute(x, y, z)
    override fun bindImageTexture(unit: Int, texture: Int, format: Int) =
        glBindImageTexture(unit, texture, 0, false, 0, GL_WRITE_ONLY, format)
    override fun memoryBarrier(bits: Int) = glMemoryBarrier(bits)
    override fun genFramebuffer() = glGenFramebuffers()
    override fun deleteFramebuffer(framebuffer: Int) = glDeleteFramebuffers(framebuffer)
    override fun framebufferTexture2D(texture: Int) =
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
    override fun readPixels(width: Int, height: Int, into: ByteBuffer) =
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, into)
    override fun bindFramebuffer(framebuffer: Int) = glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
    override fun viewport(x: Int, y: Int, width: Int, height: Int) = glViewport(x, y, width, height)
    override fun drawQuad(program: Int) {
        glBindVertexArray(quad())
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
    }
}
