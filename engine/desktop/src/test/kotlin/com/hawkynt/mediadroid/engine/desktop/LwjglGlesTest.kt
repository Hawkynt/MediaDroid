package com.hawkynt.mediadroid.engine.desktop

import com.hawkynt.mediadroid.stage.Gles
import org.junit.Assert.assertEquals
import org.junit.Test
import org.lwjgl.opengl.GL43

class LwjglGlesTest {

    @Test
    fun `the stage's enums are OpenGL's own`() {
        assertEquals(GL43.GL_VERTEX_SHADER, Gles.VERTEX_SHADER)
        assertEquals(GL43.GL_FRAGMENT_SHADER, Gles.FRAGMENT_SHADER)
        assertEquals(GL43.GL_TEXTURE_2D, Gles.TEXTURE_2D)
        assertEquals(GL43.GL_TEXTURE_MIN_FILTER, Gles.TEXTURE_MIN_FILTER)
        assertEquals(GL43.GL_TEXTURE_MAG_FILTER, Gles.TEXTURE_MAG_FILTER)
        assertEquals(GL43.GL_TEXTURE_WRAP_S, Gles.TEXTURE_WRAP_S)
        assertEquals(GL43.GL_TEXTURE_WRAP_T, Gles.TEXTURE_WRAP_T)
        assertEquals(GL43.GL_NEAREST, Gles.NEAREST)
        assertEquals(GL43.GL_LINEAR, Gles.LINEAR)
        assertEquals(GL43.GL_CLAMP_TO_EDGE, Gles.CLAMP_TO_EDGE)
        assertEquals(GL43.GL_RGBA, Gles.RGBA)
        assertEquals(GL43.GL_RGBA16F, Gles.RGBA16F)
        assertEquals(GL43.GL_UNSIGNED_BYTE, Gles.UNSIGNED_BYTE)
        assertEquals(GL43.GL_HALF_FLOAT, Gles.HALF_FLOAT)
        assertEquals(GL43.GL_FLOAT, Gles.FLOAT)
        assertEquals(GL43.GL_UNPACK_ALIGNMENT, Gles.UNPACK_ALIGNMENT)
        assertEquals(GL43.GL_PACK_ALIGNMENT, Gles.PACK_ALIGNMENT)
        assertEquals(GL43.GL_COMPUTE_SHADER, Gles.COMPUTE_SHADER)
        assertEquals(GL43.GL_RGBA16, Gles.RGBA16)
        assertEquals(GL43.GL_UNSIGNED_SHORT, Gles.UNSIGNED_SHORT)
        assertEquals(GL43.GL_TEXTURE_3D, Gles.TEXTURE_3D)
        assertEquals(GL43.GL_TEXTURE_WRAP_R, Gles.TEXTURE_WRAP_R)
        assertEquals(GL43.GL_REPEAT, Gles.REPEAT)
        assertEquals(GL43.GL_MIRRORED_REPEAT, Gles.MIRRORED_REPEAT)
        assertEquals(GL43.GL_RED, Gles.RED)
        assertEquals(GL43.GL_RG, Gles.RG)
        assertEquals(GL43.GL_RGB, Gles.RGB)
        assertEquals(GL43.GL_R8, Gles.R8)
        assertEquals(GL43.GL_RG8, Gles.RG8)
        assertEquals(GL43.GL_RGB8, Gles.RGB8)
        assertEquals(GL43.GL_RGBA8, Gles.RGBA8)
        assertEquals(GL43.GL_R16F, Gles.R16F)
        assertEquals(GL43.GL_RG16F, Gles.RG16F)
        assertEquals(GL43.GL_RGB16F, Gles.RGB16F)
        assertEquals(GL43.GL_R32F, Gles.R32F)
        assertEquals(GL43.GL_RG32F, Gles.RG32F)
        assertEquals(GL43.GL_RGB32F, Gles.RGB32F)
        assertEquals(GL43.GL_RGBA32F, Gles.RGBA32F)
        assertEquals(GL43.GL_TEXTURE_FETCH_BARRIER_BIT, Gles.TEXTURE_FETCH_BARRIER_BIT)
        assertEquals(GL43.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT, Gles.SHADER_IMAGE_ACCESS_BARRIER_BIT)
        assertEquals(GL43.GL_FRAMEBUFFER_BARRIER_BIT, Gles.FRAMEBUFFER_BARRIER_BIT)
    }
}
