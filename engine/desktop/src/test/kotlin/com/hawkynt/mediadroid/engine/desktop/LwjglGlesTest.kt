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
    }
}
