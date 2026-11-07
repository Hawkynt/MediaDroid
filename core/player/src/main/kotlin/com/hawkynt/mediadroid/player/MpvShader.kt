package com.hawkynt.mediadroid.player

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * An mpv user shader: a `.glsl` file of hook passes, each headed by `//!` lines — `HOOK` the textures it runs on,
 * `BIND` those it reads, `SAVE` where its result goes (the hooked texture itself when absent), `WIDTH` and `HEIGHT`
 * its size and `WHEN` whether it runs (reverse Polish expressions over textures' sizes), `COMPONENTS`, `DESC` —
 * followed by GLSL defining `vec4 hook()`. mpv's manual ("--glsl-shaders", "user shader syntax") is the format's
 * specification; libplacebo's own parser (`pl_mpv_user_shader_parse`, LGPL-2.1-or-later), which renders them for mpv
 * and video2x, is the oracle the stages' runs are held to (tools/shader-check and the desktop stage's tests).
 *
 * The stages run hooks on the picture before it is enlarged (`MAIN`, and `NATIVE`, `RGB`, `LINEAR`, `PREKERNEL`
 * alike: the stage's picture is RGB already), on its brightness (`LUMA`, the picture's Y, the colour following), on
 * its chroma where the stage has the picture's 4:2:0 planes (`CHROMA`: Cb and Cr at their own size, `LUMA` the Y plane
 * beside it), and after it was enlarged to the screen (`POSTKERNEL`, `SCALED`, `OUTPUT`); a pass hooking anything
 * else is left out. Embedded textures (`//!TEXTURE`, [textures]) are uploaded as declared — two or three dimensions,
 * 8-bit, half or full floats, filtered and bordered as they say — and compute passes (`//!COMPUTE`, [Pass.compute])
 * run where the stage has compute shaders ([usesCompute]); storage buffers (`//!BUFFER`), storage textures and
 * one-dimensional ones make a shader [supported] false. libplacebo's `parse_tex` and `hook_hook`
 * (`src/shaders/custom_mpv.c`) and its compute dispatch (`src/dispatch.c`) are what the textures and compute passes are
 * converted from (AGENTS.md rung 1).
 */
class MpvShader private constructor(
    /** What the file calls itself: its first pass's `DESC`, or the [name] it was loaded under. */
    val description: String,
    val passes: List<Pass>,
    /** `//!PARAM` values, as `#define`s every pass gets. */
    val parameters: Map<String, String>,
    /** Why the stages cannot run it, or null. */
    val unsupported: String?,
    /** The textures it embeds (`//!TEXTURE`), which its passes bind by name. */
    val textures: List<Texture> = emptyList(),
) {
    val supported: Boolean get() = unsupported == null

    /** Whether a pass of it is a compute pass, which runs only where the stage has compute shaders. */
    val usesCompute: Boolean get() = passes.any { it.compute != null }

    /** Whether a pass of it hooks the chroma, which runs only where the stage has the picture's planes. */
    val hooksChroma: Boolean get() = passes.any { CHROMA in it.hooks }

    /** One hook pass. */
    data class Pass(
        val hooks: List<String>,
        val binds: List<String>,
        val save: String?,
        val width: String?,
        val height: String?,
        val whenExpression: String?,
        val components: Int?,
        val description: String,
        val body: String,
        /** The compute pass's blocks and threads (`//!COMPUTE`), or null for a fragment pass. */
        val compute: Compute? = null,
    )

    /**
     * A compute pass's dispatch: each work group covers [blockWidth] × [blockHeight] pixels of the output with
     * [threadsWidth] × [threadsHeight] threads (the block's size where `//!COMPUTE` gives none).
     */
    data class Compute(val blockWidth: Int, val blockHeight: Int, val threadsWidth: Int, val threadsHeight: Int)

    /**
     * How a texture's texels are given and held: [components] (1 to 4) of [bits] each in the file, floats or
     * normalised unsigned integers, held on the graphics card at [heldBits] each.
     */
    data class Format(val components: Int, val bits: Int, val float: Boolean, val heldBits: Int = bits) {
        /** The bytes one texel takes in the file. */
        val texelBytes: Int get() = components * bits / 8
    }

    /** How a texture is read outside 0 to 1. */
    enum class Border { CLAMP, REPEAT, MIRROR }

    /**
     * An embedded texture (`//!TEXTURE`): [width] × [height] × [depth] texels ([depth] 0 for a two-dimensional one) of
     * [format], read [linear]ly or nearest, its [border] as declared; [data] the texels as the file gives them in hex,
     * rows from the first.
     */
    class Texture(
        val name: String,
        val width: Int,
        val height: Int,
        val depth: Int,
        val format: Format,
        val linear: Boolean,
        val border: Border,
        val data: ByteArray,
    )

    /** A texture's size in pixels. */
    data class Size(val width: Int, val height: Int)

    companion object {
        /** The hook points the stages run before enlarging, in mpv's order. */
        val BEFORE_SCALING = listOf("LUMA", "NATIVE", "RGB", "MAIN", "LINEAR", "PREKERNEL")

        /** The hook points after enlarging, in mpv's order. */
        val AFTER_SCALING = listOf("POSTKERNEL", "SCALED", "OUTPUT")

        /** The hook point on the picture's chroma plane, where the stage reads the picture from its planes. */
        const val CHROMA = "CHROMA"

        /**
         * The texture formats the stages hold, by the names libplacebo's OpenGL backend gives them
         * (`src/opengl/formats.c`), one to four components: 8-bit normalised (`r8`); half floats given as full floats
         * (`r16f`, uploaded as GL_FLOAT into GL_R16F) or as half floats (`r16hf`); full floats (`r32f`). Its 16-bit
         * normalised and integer formats are not held (OpenGL ES has no 16-bit normalised textures).
         */
        private val FORMATS: Map<String, Format> = buildMap {
            listOf("r", "rg", "rgb", "rgba").forEachIndexed { i, prefix ->
                put("${prefix}8", Format(i + 1, 8, float = false))
                put("${prefix}16f", Format(i + 1, 32, float = true, heldBits = 16))
                put("${prefix}16hf", Format(i + 1, 16, float = true))
                put("${prefix}32f", Format(i + 1, 32, float = true))
            }
        }

        /** [text], a `.glsl` user shader, read; [name] stands for its description where it gives none. */
        fun parse(text: String, name: String = ""): MpvShader {
            val passes = mutableListOf<Pass>()
            val parameters = linkedMapOf<String, String>()
            val textures = mutableListOf<Texture>()
            var unsupported: String? = null
            val lines = text.replace("\r\n", "\n").split("\n")
            var i = 0
            // What comes before the first header is the file's comment (its licence): no pass.
            while (i < lines.size && !lines[i].startsWith("//!")) i++
            while (i < lines.size) {
                val headers = mutableListOf<Pair<String, String>>()
                while (i < lines.size && lines[i].startsWith("//!")) {
                    val header = lines[i].removePrefix("//!").trim()
                    val key = header.substringBefore(' ').uppercase()
                    headers += key to header.substringAfter(' ', "").trim()
                    i++
                }
                val body = StringBuilder()
                while (i < lines.size && !lines[i].startsWith("//!")) body.appendLine(lines[i++])
                fun all(key: String) = headers.filter { it.first == key }.map { it.second }
                fun one(key: String) = all(key).lastOrNull()
                when {
                    one("TEXTURE") != null -> texture(one("TEXTURE")!!, ::one, body.toString().trim()).fold(
                        { textures += it },
                        { unsupported = unsupported ?: "the texture ${one("TEXTURE")}: ${it.message}" },
                    )
                    one("BUFFER") != null -> unsupported = unsupported ?: "storage buffers (//!BUFFER)"
                    one("PARAM") != null -> parameters[one("PARAM")!!] = body.toString().trim()
                    one("HOOK") != null -> {
                        val compute = one("COMPUTE")?.let(::compute)
                        if (one("COMPUTE") != null && compute == null) {
                            unsupported = unsupported ?: "a COMPUTE it cannot read: ${one("COMPUTE")}"
                        }
                        passes += Pass(
                            hooks = all("HOOK").flatMap { it.split(Regex("\\s+")) }.filter { it.isNotEmpty() },
                            binds = all("BIND"),
                            save = one("SAVE"),
                            width = one("WIDTH"),
                            height = one("HEIGHT"),
                            whenExpression = one("WHEN"),
                            components = one("COMPONENTS")?.toIntOrNull(),
                            description = one("DESC").orEmpty(),
                            body = body.toString(),
                            compute = compute,
                        )
                    }
                }
            }
            if (passes.isEmpty()) unsupported = unsupported ?: "no hook passes"
            val description = passes.firstOrNull()?.description?.takeIf { it.isNotEmpty() } ?: name
            return MpvShader(description, passes, parameters, unsupported, textures)
        }

        // `//!COMPUTE bw bh [tw th]`: the block's size, then the threads' (the block's where not given).
        private fun compute(value: String): Compute? {
            val n = value.trim().split(Regex("\\s+")).map { it.toIntOrNull()?.takeIf { v -> v > 0 } ?: return null }
            return when (n.size) {
                2 -> Compute(n[0], n[1], n[0], n[1])
                4 -> Compute(n[0], n[1], n[2], n[3])
                else -> null
            }
        }

        // A `//!TEXTURE` block, its headers read through [header] and its [hex] body decoded, as libplacebo's
        // parse_tex reads it: SIZE of one to three dimensions, a FORMAT by name, FILTER (NEAREST unless LINEAR), BORDER
        // (CLAMP unless REPEAT or MIRROR), exactly as many bytes as the texels take.
        private fun texture(name: String, header: (String) -> String?, hex: String): Result<Texture> {
            fun refuse(why: String) = Result.failure<Texture>(IllegalArgumentException(why))
            if (header("STORAGE") != null) return refuse("storage textures are not run")
            val size = header("SIZE")?.trim()?.split(Regex("\\s+"))?.map { it.toIntOrNull()?.takeIf { v -> v > 0 } }
            if (size == null || size.any { it == null } || size.size !in 1..3) return refuse("no SIZE it can read")
            if (size.size == 1) return refuse("one-dimensional textures are not run")
            val format = header("FORMAT")?.trim()?.let { FORMATS[it] } ?: return refuse("a FORMAT it cannot hold")
            val linear = when (header("FILTER")?.trim()) {
                null, "NEAREST" -> false
                "LINEAR" -> true
                else -> return refuse("a FILTER it cannot read")
            }
            val border = when (header("BORDER")?.trim()) {
                null, "CLAMP" -> Border.CLAMP
                "REPEAT" -> Border.REPEAT
                "MIRROR" -> Border.MIRROR
                else -> return refuse("a BORDER it cannot read")
            }
            if (hex.length % 2 != 0 || hex.any { Character.digit(it, 16) < 0 }) return refuse("a body that is not hex")
            val data = ByteArray(hex.length / 2) {
                (Character.digit(hex[2 * it], 16) * 16 + Character.digit(hex[2 * it + 1], 16)).toByte()
            }
            val (w, h) = size[0]!! to size[1]!!
            val d = size.getOrNull(2) ?: 0
            if (data.size != w * h * maxOf(d, 1) * format.texelBytes) return refuse("${data.size} bytes for its size")
            return Result.success(Texture(name.trim(), w, h, d, format, linear, border, data))
        }

        /**
         * A size or condition expression as mpv evaluates it: reverse Polish over numbers and textures' sizes
         * (`NAME.w`, `NAME.width`, `NAME.h`, `NAME.height`, looked up in [sizes]) with `+ - * / > < = !`; null where it
         * cannot be evaluated (an unknown texture, too few operands).
         */
        fun evaluate(expression: String, sizes: Map<String, Size>): Double? {
            val stack = ArrayDeque<Double>()
            for (token in expression.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                val number = token.toDoubleOrNull()
                when {
                    number != null -> stack.addLast(number)
                    token == "!" -> stack.addLast(if ((stack.removeLastOrNull() ?: return null) == 0.0) 1.0 else 0.0)
                    token.length == 1 && token in "+-*/><=" -> {
                        val b = stack.removeLastOrNull() ?: return null
                        val a = stack.removeLastOrNull() ?: return null
                        stack.addLast(
                            when (token) {
                                "+" -> a + b
                                "-" -> a - b
                                "*" -> a * b
                                "/" -> a / b
                                ">" -> if (a > b) 1.0 else 0.0
                                "<" -> if (a < b) 1.0 else 0.0
                                else -> if (abs(a - b) < 1e-6) 1.0 else 0.0
                            },
                        )
                    }
                    token.contains('.') -> {
                        val size = sizes[token.substringBefore('.')] ?: return null
                        stack.addLast(
                            when (token.substringAfter('.')) {
                                "w", "width" -> size.width.toDouble()
                                "h", "height" -> size.height.toDouble()
                                else -> return null
                            },
                        )
                    }
                    else -> return null
                }
            }
            return stack.singleOrNull()
        }
    }

    /** The passes that hook [stage], in the file's order; each decides when it runs whether it does (see [Planned]). */
    fun planned(stage: String): List<Planned> = passes.filter { stage in it.hooks }.map { Planned(it, this) }

    /** A pass to run: [pass] of [shader]; [size] and [runs] are worked out for the textures there are when it runs. */
    class Planned(val pass: Pass, val shader: MpvShader) {
        /** Whether it runs, its `WHEN` evaluated over [sizes]; a condition that cannot be evaluated does not hold. */
        fun runs(sizes: Map<String, Size>): Boolean =
            pass.whenExpression?.let { (evaluate(it, sizes) ?: 0.0) != 0.0 } ?: true

        /** Its output size over [sizes]: `WIDTH` and `HEIGHT`, the hooked texture's where not given. */
        fun size(sizes: Map<String, Size>): Size {
            val hooked = sizes.getValue("HOOKED")
            fun of(expression: String?, fallback: Int) =
                expression?.let { evaluate(it, sizes) }?.roundToInt()?.coerceAtLeast(1) ?: fallback
            return Size(of(pass.width, hooked.width), of(pass.height, hooked.height))
        }

        /**
         * The textures it reads when it hooks [hooked], as bound to texture units 0, 1, … ([source]'s samplers are
         * `<name>_raw`): `HOOKED` is the hooked texture under its own name.
         */
        fun textures(hooked: String): List<String> = pass.binds.map { if (it == "HOOKED") hooked else it }.distinct()
            .filter { name -> shader.textures.none { it.name == name } }

        /** The shader's embedded textures it reads, each a sampler of its own name, in the order it binds them. */
        fun userTextures(): List<Texture> = pass.binds.distinct().mapNotNull { name ->
            shader.textures.firstOrNull { it.name == name }
        }

        /**
         * The pass as a GLSL ES fragment shader for the stages: mpv's texture macros for every bound texture
         * (`_raw`, `_pos`, `_size`, `_pt`, `_mul`, `_rot`, `_off`, `_tex`, `_texOff`, `_gather`), `HOOKED_` naming the
         * hooked one,
         * the uniforms mpv gives (`frame`, `random`, `input_size`, `target_size`, `tex_offset`), `//!PARAM` values as
         * `#define`s, then the pass's own code and a `main` writing `hook()`. Coordinates run from the picture's top
         * left, as mpv's do; the stage holds the hook's textures that way round. Textures are read at their full size
         * (`textureLod` at level 0, as libplacebo reads them): a lookup whose position jumps between neighbouring
         * pixels, as a depth-to-space pass's does, would otherwise have the GPU guess a level from those jumps.
         */
        fun source(hooked: String): String = buildString {
            val gather = pass.body.contains("gather", ignoreCase = true)
            val compute = pass.compute
            appendLine(if (gather || compute != null) "#version 310 es" else "#version 300 es")
            appendLine("precision highp float;")
            appendLine("precision highp int;")
            appendLine("precision highp sampler2D;")
            if (compute == null) {
                appendLine("out vec4 mpvOutput;")
            } else {
                // A compute pass: its threads in work groups, and the output as an image it stores into; positions
                // from the thread's pixel, as libplacebo's dispatch has them.
                val (x, y) = compute.threadsWidth to compute.threadsHeight
                appendLine("layout(local_size_x = $x, local_size_y = $y) in;")
                appendLine("layout(rgba16f, binding = 0) writeonly uniform highp image2D out_image;")
            }
            appendLine("uniform vec2 mpvTarget;")
            appendLine("uniform int frame;")
            appendLine("uniform float random;")
            appendLine("uniform vec2 input_size;")
            appendLine("uniform vec2 target_size;")
            appendLine("uniform vec2 tex_offset;")
            for ((name, value) in shader.parameters) appendLine("#define $name ($value)")
            for (texture in userTextures()) {
                appendLine("uniform highp ${if (texture.depth > 0) "sampler3D" else "sampler2D"} ${texture.name};")
                appendLine("#define ${texture.name}_tex(pos) (textureLod(${texture.name}, pos, 0.0))")
            }
            val position = if (compute == null) {
                "(gl_FragCoord.xy / mpvTarget)"
            } else {
                "((vec2(gl_GlobalInvocationID.xy) + vec2(0.5)) / mpvTarget)"
            }
            for (name in textures(hooked)) {
                appendLine("uniform sampler2D ${name}_raw;")
                appendLine("uniform vec2 ${name}_size;")
                appendLine("#define ${name}_pos $position")
                appendLine("#define ${name}_pt (vec2(1.0) / ${name}_size)")
                appendLine("#define ${name}_mul 1.0")
                appendLine("#define ${name}_rot mat2(1.0)")
                appendLine("#define ${name}_off vec2(0.0)")
                appendLine("#define ${name}_tex(pos) (${name}_mul * vec4(textureLod(${name}_raw, pos, 0.0)))")
                appendLine("#define ${name}_texOff(off) ${name}_tex(${name}_pos + ${name}_pt * vec2(off))")
                if (gather) {
                    val gathered = "textureGather(${name}_raw, pos, c)"
                    appendLine("#define ${name}_gather(pos, c) (${name}_mul * vec4($gathered))")
                }
                if (name == hooked) {
                    for (suffix in listOf("raw", "pos", "size", "pt", "mul", "rot", "off", "tex", "texOff", "gather")) {
                        appendLine("#define HOOKED_$suffix ${name}_$suffix")
                    }
                }
            }
            appendLine(pass.body)
            appendLine(if (compute == null) "void main() { mpvOutput = hook(); }" else "void main() { hook(); }")
        }
    }
}
