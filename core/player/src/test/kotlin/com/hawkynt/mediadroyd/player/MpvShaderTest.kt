package com.hawkynt.mediadroyd.player

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
        // libplacebo's further operator, and a shader's parameters by name (its lookup_var).
        assertEquals(1.0, MpvShader.evaluate("7 3 %", sizes)!!, 0.0)
        assertEquals(1.0, MpvShader.evaluate("avg 0 = LUMA.w 640 = *", sizes, mapOf("avg" to 0.0))!!, 0.0)
        assertNull(MpvShader.evaluate("avg 0 =", sizes))
        val tuned = "//!PARAM avg\n//!TYPE float\n%s\n//!HOOK MAIN\n//!WHEN avg 0 =\nvec4 hook() { return vec4(1); }"
        assertTrue(MpvShader.parse(tuned.format("0.0")).planned("MAIN").single().runs(sizes))
        assertFalse(MpvShader.parse(tuned.format("0.5")).planned("MAIN").single().runs(sizes))
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
    fun `parameters become defines`() {
        assertFalse(MpvShader.parse("// nothing to run").supported)
        val parameter = "//!PARAM strength\n//!TYPE float\n0.5\n"
        val tuned = MpvShader.parse(parameter + "//!HOOK MAIN\nvec4 hook() { return vec4(strength); }")
        assertTrue(tuned.supported)
        assertTrue("#define strength (0.5)" in tuned.planned("MAIN")[0].source("MAIN"))
    }

    // A pass reading a 2 × 1 lookup table of half floats (1.0, 0.5, 0.0, 1.0 and 0.25, 0.0, 1.0, -2.0, little-endian).
    private val textured = """
        //!HOOK LUMA
        //!BIND HOOKED
        //!BIND LUT
        vec4 hook() { return HOOKED_tex(HOOKED_pos) * texture(LUT, vec2(0.25, 0.5)) + LUT_tex(vec2(0.75, 0.5)); }

        //!TEXTURE LUT
        //!SIZE 2 1
        //!FORMAT rgba16hf
        //!FILTER LINEAR
        //!BORDER REPEAT
        003c00380000003c00340000003c00c0
    """.trimIndent()

    @Test
    fun `embedded textures are read with their size, format, filter, border and data`() {
        val shader = MpvShader.parse(textured)
        assertTrue(shader.unsupported, shader.supported)
        val lut = shader.textures.single()
        assertEquals("LUT", lut.name)
        assertEquals(2, lut.width)
        assertEquals(1, lut.height)
        assertEquals(0, lut.depth)
        assertEquals(MpvShader.Format(4, 16, float = true), lut.format)
        // libplacebo's OpenGL formats: rgba16f is given as full floats, held as half floats.
        val ones = textured.replace("rgba16hf", "rgba16f")
            .replace("003c00380000003c00340000003c00c0", "0000803f".repeat(8))
        val floats = MpvShader.parse(ones)
        assertEquals(MpvShader.Format(4, 32, float = true, heldBits = 16), floats.textures.single().format)
        assertTrue(lut.linear)
        assertEquals(MpvShader.Border.REPEAT, lut.border)
        assertEquals(16, lut.data.size)
        assertEquals(0x3c, lut.data[1].toInt())
        assertEquals(0xc0, lut.data[15].toInt() and 0xFF)
        val nearest = MpvShader.parse("//!TEXTURE T\n//!SIZE 1 1 2\n//!FORMAT r8\n0aff\n//!HOOK MAIN\nvoid hook() {}")
        val volume = nearest.textures.single()
        assertEquals(2, volume.depth)
        assertFalse(volume.linear)
        assertEquals(MpvShader.Border.CLAMP, volume.border)
        assertEquals(MpvShader.Format(1, 8, float = false), volume.format)
    }

    @Test
    fun `textures the stages cannot hold refuse the shader`() {
        fun refused(texture: String) =
            !MpvShader.parse("$texture\n//!HOOK MAIN\nvec4 hook() { return vec4(0); }").supported
        assertFalse("one dimension", refused("//!TEXTURE T\n//!SIZE 4\n//!FORMAT r8\n00010203"))
        assertTrue("four dimensions", refused("//!TEXTURE T\n//!SIZE 1 1 1 1\n//!FORMAT r8\n00"))
        assertTrue("too few bytes", refused("//!TEXTURE T\n//!SIZE 2 2\n//!FORMAT r8\n000102"))
        assertTrue("not hex", refused("//!TEXTURE T\n//!SIZE 1 1\n//!FORMAT r8\nzz"))
        assertTrue("no format", refused("//!TEXTURE T\n//!SIZE 1 1\n00"))
        assertFalse("16-bit normalised", refused("//!TEXTURE T\n//!SIZE 1 1\n//!FORMAT r16\n0000"))
        assertFalse("16-bit integers", refused("//!TEXTURE T\n//!SIZE 1 1\n//!FORMAT rg16u\n00000000"))
        assertTrue("integers read linearly", refused("//!TEXTURE T\n//!SIZE 1 1\n//!FORMAT r8u\n//!FILTER LINEAR\n00"))
        assertTrue("signed integers", refused("//!TEXTURE T\n//!SIZE 1 1\n//!FORMAT r16i\n0000"))
        assertTrue("storage", refused("//!TEXTURE T\n//!SIZE 1 1\n//!FORMAT r8\n//!STORAGE\n00"))
        assertFalse("32-bit floats", refused("//!TEXTURE T\n//!SIZE 1 1\n//!FORMAT rg32f\n0000000000000000"))
        assertTrue("r16f given as half floats", refused("//!TEXTURE T\n//!SIZE 1 1\n//!FORMAT r16f\n0000"))
    }

    @Test
    fun `a texture a pass binds is a sampler of its own name, not one of mpv's hooked textures`() {
        val planned = MpvShader.parse(textured).planned("LUMA").single()
        assertEquals(listOf("LUMA"), planned.textures("LUMA"))
        assertEquals(listOf("LUT"), planned.userTextures().map { it.name })
        val source = planned.source("LUMA")
        assertTrue("uniform highp sampler2D LUT;" in source)
        assertTrue("#define LUT_tex(pos) (textureLod(LUT, pos, 0.0))" in source)
        val volume = MpvShader.parse("//!TEXTURE V\n//!SIZE 1 1 1\n//!FORMAT r8\n00\n//!HOOK MAIN\n//!BIND V\n" +
            "vec4 hook() { return texture(V, vec3(0.5)); }")
        assertTrue("uniform highp sampler3D V;" in volume.planned("MAIN").single().source("MAIN"))
    }

    @Test
    fun `a compute pass runs its blocks with its threads, writing out_image`() {
        val shader = MpvShader.parse(
            "//!HOOK LUMA\n//!BIND HOOKED\n//!COMPUTE 64 16 32 8\nvoid hook() {\n" +
                "imageStore(out_image, ivec2(gl_GlobalInvocationID), HOOKED_tex(HOOKED_pos)); }",
        )
        assertTrue(shader.supported)
        assertTrue(shader.usesCompute)
        assertEquals(MpvShader.Compute(64, 16, 32, 8), shader.passes.single().compute)
        val source = shader.planned("LUMA").single().source("LUMA")
        assertTrue(source.startsWith("#version 310 es\n"))
        assertTrue("layout(local_size_x = 32, local_size_y = 8) in;" in source)
        assertTrue("layout(rgba16f, binding = 0) writeonly uniform highp image2D out_image;" in source)
        assertTrue("#define LUMA_pos ((vec2(gl_GlobalInvocationID.xy) + vec2(0.5)) / mpvTarget)" in source)
        assertFalse("out vec4 mpvOutput;" in source)
        assertTrue(source.trimEnd().endsWith("void main() { hook(); }"))
        val square = MpvShader.parse("//!HOOK MAIN\n//!COMPUTE 16 8\nvoid hook() {}").passes.single().compute
        assertEquals(MpvShader.Compute(16, 8, 16, 8), square)
        assertFalse(MpvShader.parse(twoPasses).usesCompute)
    }

    @Test
    fun `passes hooking the chroma are planned for the chroma`() {
        val krig = MpvShader.parse(
            "//!HOOK CHROMA\n//!BIND HOOKED\n//!BIND LUMA\n//!WIDTH LUMA.w\n//!HEIGHT LUMA.h\n" +
                "vec4 hook() { return HOOKED_tex(HOOKED_pos); }",
        )
        assertTrue(krig.supported)
        assertTrue(krig.hooksChroma)
        assertEquals(listOf("CHROMA", "LUMA"), krig.planned("CHROMA").single().textures("CHROMA"))
        assertFalse(MpvShader.parse(twoPasses).hooksChroma)
    }

    @Test
    fun `one-dimensional textures are held as a row and read through their own functions`() {
        val shader = MpvShader.parse(
            "//!TEXTURE CURVE\n//!SIZE 4\n//!FORMAT r16u\n0000ff00ff0fffff\n" +
                "//!HOOK MAIN\n//!BIND HOOKED\n//!BIND CURVE\nvec4 hook() {\n" +
                "    int n = textureSize(CURVE, 0);\n" +
                "    uint a = texelFetch( CURVE , n - 1, 0).r + texture(CURVE, 0.5).r;\n" +
                "    return vec4(float(a) + float(CURVE_tex(0.25).r) + float(textureLod(CURVE, 0.1, 0.0).r));\n}",
        )
        assertTrue(shader.unsupported, shader.supported)
        val curve = shader.textures.single()
        assertEquals(1, curve.dimensions)
        assertEquals(4, curve.width)
        assertEquals(1, curve.height)
        assertEquals(MpvShader.Format(1, 16, float = false, integer = true), curve.format)
        val source = shader.planned("MAIN").single().source("MAIN")
        assertTrue(source, "uniform highp usampler2D CURVE;" in source)
        assertTrue(source, "int n = CURVE_mpv1d_textureSize(0);" in source)
        assertTrue(source, "uint a = CURVE_mpv1d_texelFetch(n - 1, 0).r + CURVE_mpv1d_texture(0.5).r;" in source)
        assertTrue(source, "float(CURVE_mpv1d_textureLod(0.1, 0.0).r)" in source)
        val fetch = "uvec4 CURVE_mpv1d_texelFetch(int i, int lod) { return texelFetch(CURVE, ivec2(i, 0), lod); }"
        assertTrue(source, fetch in source)
        assertTrue(source, "#define CURVE_tex(pos) (textureLod(CURVE, vec2(pos, 0.5), 0.0))" in source)
        val unorm = MpvShader.parse(
            "//!TEXTURE T\n//!SIZE 2 1\n//!FORMAT rgba16\n" + "ffff".repeat(8) +
                "\n//!HOOK MAIN\n//!BIND T\nvec4 hook() { return T_tex(vec2(0.5)); }",
        )
        assertEquals(MpvShader.Format(4, 16, float = false, heldBits = 32), unorm.textures.single().format)
        assertTrue("uniform highp sampler2D T;" in unorm.planned("MAIN").single().source("MAIN"))
    }

    // libplacebo's own example (docs/custom-shaders.md), a vec3 and a float added: a uniform block and a storage
    // block, read by one pass.
    private val buffered = """
        //!HOOK MAIN
        //!BIND HOOKED
        //!BIND buf_uniform
        //!BIND buf_storage
        vec4 hook() { return vec4(foo, bar, bat); }

        //!BUFFER buf_uniform
        //!VAR float foo
        //!VAR float bar
        0000803f00000040

        //!BUFFER buf_storage
        //!VAR vec2 bat
        //!VAR int big[32];
        //!VAR vec3 dir
        //!VAR float tail
        //!STORAGE
    """.trimIndent()

    @Test
    fun `buffers are laid out as std140 and std430, the storage ones where compute runs`() {
        val shader = MpvShader.parse(buffered)
        assertTrue(shader.unsupported, shader.supported)
        val (uniform, storage) = shader.buffers
        assertEquals("buf_uniform", uniform.name)
        assertFalse(uniform.storage)
        assertEquals(listOf(0, 4), uniform.variables.map { it.offset })
        assertEquals(8, uniform.size)
        assertEquals(1f, java.nio.ByteBuffer.wrap(uniform.data).order(java.nio.ByteOrder.LITTLE_ENDIAN).float)
        assertTrue(storage.storage)
        // std430: the int array packed at 4 bytes from 8, the vec3 aligned to 16 after it, the float in its tail.
        assertEquals(listOf(0, 8, 144, 156), storage.variables.map { it.offset })
        assertEquals(160, storage.size)
        assertTrue("no initial data: zeros", storage.data.size == 160 && storage.data.all { it.toInt() == 0 })
        // std140 rounds an array's elements up to 16 bytes each.
        val std140 = MpvShader.parse(buffered.replace("//!STORAGE", "") + "\n" + "00".repeat(544)).buffers[1]
        assertEquals(listOf(0, 16, 528, 540), std140.variables.map { it.offset })
        assertTrue(shader.usesStorage)
        val uniformOnly = buffered.substringBefore("//!BUFFER buf_storage")
        assertFalse(MpvShader.parse(uniformOnly).usesStorage)
        val source = shader.planned("MAIN").single().source("MAIN")
        assertTrue(source.startsWith("#version 310 es\n"))
        assertTrue(source, "layout(std140) uniform buf_uniform {\n    float foo;\n    float bar;\n};" in source)
        val block = "layout(std430, binding = 1) coherent restrict buffer buf_storage {\n    vec2 bat;\n" +
            "    int big[32];\n    vec3 dir;\n    float tail;\n};"
        assertTrue(source, block in source)
        val planned = shader.planned("MAIN").single()
        assertEquals(listOf("MAIN"), planned.textures("MAIN"))
        assertEquals(listOf("buf_uniform", "buf_storage"), planned.buffers().map { it.name })
        fun refused(buffer: String) =
            !MpvShader.parse("$buffer\n//!HOOK MAIN\nvec4 hook() { return vec4(0); }").supported
        assertTrue("too few bytes", refused("//!BUFFER b\n//!VAR float x\n00"))
        assertTrue("an unknown type", refused("//!BUFFER b\n//!VAR double x\n0000000000000000"))
        assertTrue("a uniform block without data", refused("//!BUFFER b\n//!VAR float x"))
        assertFalse(refused("//!BUFFER b\n//!VAR mat3 m\n" + "00".repeat(48)))
        try {
            MpvShaders.load(mapOf("s.glsl" to buffered, "u.glsl" to uniformOnly))
            MpvShaders.compute = false
            assertEquals(listOf("u.glsl"), MpvShaders.choices)
            MpvShaders.compute = true
            assertEquals(listOf("s.glsl", "u.glsl"), MpvShaders.choices)
        } finally {
            MpvShaders.compute = false
            MpvShaders.load(emptyMap())
        }
    }

    @Test
    fun `passes hooking a plane are told apart from those on the picture`() {
        assertTrue(MpvShader.parse(twoPasses).hooksPlanes)
        assertTrue(MpvShader.parse("//!HOOK CHROMA\nvec4 hook() { return vec4(0); }").hooksPlanes)
        assertFalse(MpvShader.parse("//!HOOK MAIN\nvec4 hook() { return vec4(0); }").hooksPlanes)
    }

    @Test
    fun `the library offers the files the stages can run, by name, compute where the stage has it`() {
        MpvShaders.load(
            mapOf(
                "b.glsl" to "//!HOOK MAIN\nvec4 hook() { return vec4(1); }",
                "d.hook" to "//!HOOK LUMA\nvec4 hook() { return vec4(1); }",
                "a.GLSL" to "//!HOOK MAIN\n//!COMPUTE 8 8\nvoid hook() {}",
                "c.glsl" to "//!TEXTURE T\n//!SIZE 2 1\n//!FORMAT r8\n//!STORAGE\n0000\n" +
                    "//!HOOK MAIN\nvec4 hook() { return vec4(1); }",
                "notes.txt" to "//!HOOK MAIN",
            ),
        )
        try {
            MpvShaders.compute = false
            assertEquals(listOf("b.glsl", "d.hook"), MpvShaders.choices)
            MpvShaders.compute = true
            assertEquals(listOf("a.GLSL", "b.glsl", "d.hook"), MpvShaders.choices)
            assertEquals(setOf("a.GLSL", "b.glsl", "c.glsl", "d.hook"), MpvShaders.library.keys)
        } finally {
            MpvShaders.compute = false
            MpvShaders.load(emptyMap())
        }
    }
}
