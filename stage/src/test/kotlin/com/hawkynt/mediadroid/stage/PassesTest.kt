package com.hawkynt.mediadroid.stage

import com.hawkynt.mediadroid.player.PictureAdjustment
import com.hawkynt.mediadroid.shaders.Shaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PassesTest {

    @Test
    fun `a program is built once, its quad's attributes bound before it links`() {
        val gl = RecordingGles()
        val passes = Passes(gl)
        val first = passes.program(Shaders.VIDEO_SHARPEN)
        assertEquals(first, passes.program(Shaders.VIDEO_SHARPEN))
        assertEquals(1, gl.calls.count { it.startsWith("createProgram") })
        val attributes = gl.calls.indexOf("attrib 0 aPosition")
        assertTrue(attributes in 0 until gl.calls.indexOf("link $first"))
        assertTrue(gl.calls.contains("attrib 1 aTexCoord"))
    }

    @Test
    fun `a shader that does not compile is an exception, not a program`() {
        val passes = Passes(RecordingGles(failing = "uSharpness"))
        try {
            passes.program(Shaders.VIDEO_SHARPEN)
            fail("built")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.startsWith("Shader"))
        }
    }

    @Test
    fun `a pass draws over the whole of its target, its textures on units in order, grain and seed handed on`() {
        val gl = RecordingGles()
        val passes = Passes(gl, grain = { 0.25f }, seed = { 7f })
        val into = FakeTarget(texture = 5, width = 30, height = 20)
        passes.draw(Shaders.VIDEO_BLEND, into, 11, listOf(12 to "uPrevious")) { set("uWeight", 0.5f) }
        val drawn = gl.calls.dropWhile { !it.startsWith("framebuffer") }
        assertEquals(listOf("framebuffer 1005", "viewport 0 0 30 20"), drawn.take(2))
        assertTrue(drawn.containsAll(listOf("uTex = 0", "uPrevious = 1", "uGrain = 0.25", "uSeed = 7.0",
            "uWeight = 0.5", "uTexMatrix = matrix4")))
        assertTrue(drawn.indexOf("uWeight = 0.5") < drawn.indexOf(drawn.first { it.startsWith("draw") }))
        assertEquals("unit 0", drawn.last())
    }

    @Test
    fun `a nearest pass reads every texture texel by texel and leaves them linear`() {
        val gl = RecordingGles()
        Passes(gl).draw(Shaders.VIDEO_TEMPORAL, FakeTarget(1, 4, 4), 2, listOf(3 to "uPrev"), nearest = true)
        val draw = gl.calls.indexOfFirst { it.startsWith("draw") }
        val min = "${Gles.TEXTURE_MIN_FILTER}"
        assertEquals(2, gl.calls.take(draw).count { it == "parameter $min ${Gles.NEAREST}" })
        assertEquals(2, gl.calls.drop(draw).count { it == "parameter $min ${Gles.LINEAR}" })
    }

    @Test
    fun `NIS enlarges with its table on unit 1 and its scale divided exactly`() {
        val gl = RecordingGles()
        Upscaling(Passes(gl)).nis(FakeTarget(1, 300, 200), FakeTarget(2, 600, 400))
        assertTrue(gl.calls.contains("uCoef = 1"))
        assertTrue(gl.calls.contains("uScale = 0.5, 0.5"))
        assertTrue(gl.calls.contains("uOutSize = 600.0, 400.0"))
        assertTrue(gl.calls.contains("uInSize = 300.0, 200.0"))
    }

    @Test
    fun `ACNet's last convolution reads the picture, the shuffle the second pair's first half`() {
        val gl = RecordingGles()
        val from = FakeTarget(1, 8, 8)
        val net = (2..5).map { FakeTarget(it, 8, 8) }
        Upscaling(Passes(gl)).acnet(from, net[0], net[1], net[2], net[3], FakeTarget(9, 16, 16))
        assertEquals(12, gl.calls.count { it.startsWith("draw") })
        val shuffle = gl.calls.lastIndexOf("framebuffer 1009")
        val bound = gl.calls.drop(shuffle).filter { it.startsWith("texture") }
        assertEquals(listOf("texture 1", "texture 4"), bound)
    }

    @Test
    fun `xBRZ reaches the shown size by a whole factor from 2 to 6`() {
        assertEquals(2, Upscaling.xbrzFactor(100, 100, 150, 120))
        assertEquals(3, Upscaling.xbrzFactor(100, 100, 300, 260))
        assertEquals(6, Upscaling.xbrzFactor(10, 10, 1000, 1000))
        assertEquals(2, Upscaling.xbrzFactor(100, 100, 100, 100))
    }

    @Test
    fun `building an upscaler builds every program its passes use`() {
        val gl = RecordingGles()
        Upscaling(Passes(gl)).build(PictureAdjustment.Upscaler.ACNET)
        assertEquals(12, gl.calls.count { it.startsWith("createProgram") })
        Upscaling(Passes(gl)).build(PictureAdjustment.Upscaler.STANDARD)
        assertEquals(12, gl.calls.count { it.startsWith("createProgram") })
    }
}
