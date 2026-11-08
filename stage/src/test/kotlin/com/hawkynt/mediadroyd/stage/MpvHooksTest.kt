package com.hawkynt.mediadroyd.stage

import com.hawkynt.mediadroyd.player.MpvShader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvHooksTest {

    private val gl = RecordingGles()
    private val passes = Passes(gl)
    private var frames = 100
    private val hooks = MpvHooks(gl, passes, Finishing(passes), compute = { false }) { _, w, h ->
        FakeTarget(frames++, w, h)
    }

    private val doubler = MpvShader.parse(
        """
        //!HOOK LUMA
        //!BIND HOOKED
        //!WIDTH HOOKED.w 2 *
        //!HEIGHT HOOKED.h 2 *
        vec4 hook() { return HOOKED_tex(HOOKED_pos); }
        """.trimIndent(),
    )

    private val chromaHook = MpvShader.parse(
        """
        //!HOOK CHROMA
        //!BIND HOOKED
        vec4 hook() { return HOOKED_tex(HOOKED_pos); }
        """.trimIndent(),
    )

    private class Upscaled(val y: DrawTarget, val into: DrawTarget)

    private fun planes(shader: MpvShader, upscaled: MutableList<Upscaled>): DrawTarget = hooks.planes(
        shader, FakeTarget(1, 64, 48), 2, 3, Passes.IDENTITY, MpvHooks.Sizes(128, 96, 64, 48), lumaHooks = true,
        colour = FloatArray(9), foot = 0f, matrix = Passes.IDENTITY,
        chromaUpscaler = { y, into -> upscaled += Upscaled(y, into); into },
    ) { w, h -> FakeTarget(50, w, h) }

    @Test
    fun `chroma no CHROMA pass touched goes through the chroma upscaler, at the hooked luma's size`() {
        val upscaled = mutableListOf<Upscaled>()
        val out = planes(doubler, upscaled)
        assertEquals(1, upscaled.size)
        val call = upscaled.single()
        // The doubled Y plane guides it, into the frame for that size.
        assertEquals(128 to 96, call.y.width to call.y.height)
        assertTrue(call.y.texture != 1)
        assertSame(call.into, out)
        assertEquals(128 to 96, out.width to out.height)
    }

    @Test
    fun `chroma a CHROMA pass hooked is put back as the pass left it, not upscaled again`() {
        val upscaled = mutableListOf<Upscaled>()
        val out = planes(chromaHook, upscaled)
        assertTrue(upscaled.isEmpty())
        assertEquals(64 to 48, out.width to out.height)
    }
}
