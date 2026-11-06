package com.hawkynt.mediadroid.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading mpv user shaders as mpv's manual specifies them; libplacebo runs them as the oracle (the desktop tests). */
class MpvShaderTest {

    private val twoPasses = """
        // A licence comment before the first header is no pass.
        //!HOOK LUMA
        //!BIND HOOKED
        //!SAVE FEATURE
        //!COMPONENTS 4
        //!WHEN OUTPUT.w LUMA.w / 1.300 > OUTPUT.h LUMA.h / 1.300 > *
        //!DESC first
        vec4 hook() { return HOOKED_texOff(vec2(1, 0)); }

        //!HOOK LUMA
        //!BIND FEATURE
        //!WIDTH LUMA.w 2 *
        //!HEIGHT LUMA.height 2 *
        //!DESC second
        vec4 hook() { return FEATURE_tex(FEATURE_pos); }
    """.trimIndent()

    @Test
    fun `passes are read with their headers and bodies, the comment before them left out`() {
        val shader = MpvShader.parse(twoPasses, "file")
        assertTrue(shader.supported)
        assertEquals("first", shader.description)
        assertEquals(2, shader.passes.size)
        val first = shader.passes[0]
        assertEquals(listOf("LUMA"), first.hooks)
        assertEquals(listOf("HOOKED"), first.binds)
        assertEquals("FEATURE", first.save)
        assertEquals(4, first.components)
        assertTrue("HOOKED_texOff" in first.body)
        assertNull(shader.passes[1].save)
        assertEquals(1, shader.planned("LUMA").size.coerceAtMost(1))
        assertTrue(shader.planned("MAIN").isEmpty())
    }

    @Test
    fun `sizes and conditions are reverse Polish over textures' sizes, as mpv evaluates them`() {
        val sizes = mapOf("LUMA" to MpvShader.Size(640, 360), "OUTPUT" to MpvShader.Size(1920, 1080))
        assertEquals(1280.0, MpvShader.evaluate("LUMA.w 2 *", sizes)!!, 0.0)
        assertEquals(1.0, MpvShader.evaluate("OUTPUT.w LUMA.w / 1.300 > OUTPUT.h LUMA.h / 1.300 > *", sizes)!!, 0.0)
        assertEquals(0.0, MpvShader.evaluate("OUTPUT.w LUMA.w / 4 >", sizes)!!, 0.0)
        assertEquals(1.0, MpvShader.evaluate("LUMA.w 640 =", sizes)!!, 0.0)
        assertEquals(0.0, MpvShader.evaluate("1 !", sizes)!!, 0.0)
        assertEquals(1.0, MpvShader.evaluate("LUMA.height 400 <", sizes)!!, 0.0)
        assertNull(MpvShader.evaluate("CHROMA.w", sizes))
        assertNull(MpvShader.evaluate("2 *", sizes))
    }

    @Test
    fun `a pass runs where its condition holds, at the size it asks for or the hooked one's`() {
        val second = MpvShader.parse(twoPasses).planned("LUMA")[1]
        val small = mapOf("HOOKED" to MpvShader.Size(640, 360), "LUMA" to MpvShader.Size(640, 360))
        assertEquals(MpvShader.Size(1280, 720), second.size(small))
        val first = MpvShader.parse(twoPasses).planned("LUMA")[0]
        assertTrue(first.runs(small + ("OUTPUT" to MpvShader.Size(1920, 1080))))
        assertFalse(first.runs(small + ("OUTPUT" to MpvShader.Size(640, 360))))
        assertEquals(MpvShader.Size(640, 360), first.size(small))
    }

    @Test
    fun `the pass's GLSL gets mpv's texture macros, HOOKED naming the hooked texture`() {
        val first = MpvShader.parse(twoPasses).planned("LUMA")[0]
        assertEquals(listOf("LUMA"), first.textures("LUMA"))
        val source = first.source("LUMA")
        assertTrue(source.startsWith("#version 300 es\n"))
        assertTrue("uniform sampler2D LUMA_raw;" in source)
        assertTrue("#define HOOKED_texOff LUMA_texOff" in source)
        assertTrue("#define LUMA_texOff(off) LUMA_tex(LUMA_pos + LUMA_pt * vec2(off))" in source)
        assertTrue(source.trimEnd().endsWith("void main() { mpvOutput = hook(); }"))
        val gathering =
            MpvShader.parse("//!HOOK MAIN\n//!BIND HOOKED\nvec4 hook() { return HOOKED_gather(HOOKED_pos, 0); }")
        assertTrue(gathering.planned("MAIN")[0].source("MAIN").startsWith("#version 310 es\n"))
    }

    @Test
    fun `compute passes and embedded textures are not run, parameters become defines`() {
        assertFalse(MpvShader.parse("//!HOOK MAIN\n//!COMPUTE 16 16\nvoid hook() {}").supported)
        val textured = "//!TEXTURE LUT\n//!SIZE 4 4\nffff\n//!HOOK MAIN\nvec4 hook() { return vec4(0); }"
        assertFalse(MpvShader.parse(textured).supported)
        assertFalse(MpvShader.parse("// nothing to run").supported)
        val parameter = "//!PARAM strength\n//!TYPE float\n0.5\n"
        val tuned = MpvShader.parse(parameter + "//!HOOK MAIN\nvec4 hook() { return vec4(strength); }")
        assertTrue(tuned.supported)
        assertTrue("#define strength (0.5)" in tuned.planned("MAIN")[0].source("MAIN"))
    }

    @Test
    fun `the library offers the files the stages can run, by name`() {
        MpvShaders.load(
            mapOf(
                "b.glsl" to "//!HOOK MAIN\nvec4 hook() { return vec4(1); }",
                "a.GLSL" to "//!HOOK MAIN\n//!COMPUTE 8 8\nvoid hook() {}",
                "notes.txt" to "//!HOOK MAIN",
            ),
        )
        assertEquals(listOf("b.glsl"), MpvShaders.choices)
        assertEquals(setOf("a.GLSL", "b.glsl"), MpvShaders.library.keys)
        MpvShaders.load(emptyMap())
    }
}
