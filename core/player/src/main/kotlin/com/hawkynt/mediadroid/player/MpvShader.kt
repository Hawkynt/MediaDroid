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
 * The stages run hooks on the picture's planes where they have the picture's 4:2:0 planes (`LUMA` the decoded Y plane,
 * `CHROMA` Cb and Cr at their own size — [PLANES]), else on its brightness (`LUMA`, the RGB picture's Y, the colour
 * following); on the picture before it is enlarged (`MAIN`, and `NATIVE`, `RGB`, `LINEAR`, `PREKERNEL` alike: the
 * stage's picture is RGB already), and after it was enlarged to the screen (`POSTKERNEL`, `SCALED`, `OUTPUT`); a pass
 * hooking anything else is left out. Embedded textures (`//!TEXTURE`, [textures]) are uploaded as declared — one, two
 * or three dimensions, 8-bit or 16-bit normalised, 8-bit or 16-bit unsigned integers, half or full floats, filtered
 * and bordered as they say — buffers (`//!BUFFER`, [buffers]) as uniform blocks, or storage blocks where the stage has
 * compute shaders ([usesStorage]), and compute passes (`//!COMPUTE`, [Pass.compute]) run where it has them
 * ([usesCompute]); storage textures make a shader [supported] false. libplacebo's `parse_tex`, `parse_buf` and
 * `hook_hook` (`src/shaders/custom_mpv.c`), its buffer layouts (`pl_std140_layout`, `pl_std430_layout`,
 * `src/gpu.c`) and its compute dispatch (`src/dispatch.c`) are what the textures, buffers and compute passes are
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
    /** The buffers it declares (`//!BUFFER`), which its passes bind by name and read by their variables' names. */
    val buffers: List<Buffer> = emptyList(),
) {
    val supported: Boolean get() = unsupported == null

    /** Whether a pass of it is a compute pass, which runs only where the stage has compute shaders. */
    val usesCompute: Boolean get() = passes.any { it.compute != null }

    /** Whether it declares a storage buffer, which (as compute passes) needs OpenGL ES 3.1. */
    val usesStorage: Boolean get() = buffers.any { it.storage }

    /** Whether a pass of it hooks the chroma, which runs only where the stage has the picture's planes. */
    val hooksChroma: Boolean get() = passes.any { CHROMA in it.hooks }

    /** Whether a pass of it hooks a plane ([PLANES]), which runs on the decoded planes where the stage has them. */
    val hooksPlanes: Boolean get() = passes.any { p -> p.hooks.any { it in PLANES } }

    // The numeric parameters, which sizes and conditions may name (libplacebo's lookup_var).
    private val values: Map<String, Double> = parameters.mapNotNull { (k, v) -> v.toDoubleOrNull()?.let { k to it } }
        .toMap()

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
     * How a texture's texels are given and held: [components] (1 to 4) of [bits] each in the file, floats, normalised
     * unsigned integers or ([integer]) unsigned integers read as they are, held on the graphics card at [heldBits]
     * each — 16-bit normalised ones as full floats, which OpenGL ES holds where it has no 16-bit normalised textures.
     */
    data class Format(
        val components: Int,
        val bits: Int,
        val float: Boolean,
        val heldBits: Int = bits,
        val integer: Boolean = false,
    ) {
        /** The bytes one texel takes in the file. */
        val texelBytes: Int get() = components * bits / 8
    }

    /** How a texture is read outside 0 to 1. */
    enum class Border { CLAMP, REPEAT, MIRROR }

    /**
     * An embedded texture (`//!TEXTURE`): [width] × [height] × [depth] texels ([depth] 0 for a one- or
     * two-dimensional one, [height] 1 for a one-dimensional one, which the stages hold as a row) of [format], read
     * [linear]ly or nearest, its [border] as declared; [data] the texels as the file gives them in hex, rows from the
     * first.
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
        val dimensions: Int = if (depth > 0) 3 else 2,
    )

    /**
     * A buffer (`//!BUFFER`): a uniform block, or a [storage] block that keeps what the passes write from one frame to
     * the next, of [variables] laid out as std140 or std430 (as libplacebo places them), [size] bytes starting as
     * [data] (zeros where a storage block gives none).
     */
    class Buffer(val name: String, val storage: Boolean, val variables: List<Variable>, val data: ByteArray) {
        val size: Int get() = data.size
    }

    /** A buffer's variable: [type] (a GLSL type) [name], [count] of them where it is an array, at [offset]. */
    data class Variable(val type: String, val name: String, val count: Int?, val offset: Int)

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
         * The hook points on the picture's planes, in the order libplacebo hooks a YCbCr frame's planes: run on the
         * decoded planes where the stage has them, `LUMA` on the RGB picture's brightness where it has not.
         */
        val PLANES = listOf("LUMA", CHROMA)

        /**
         * The texture formats the stages hold, by the names libplacebo's OpenGL backend gives them
         * (`src/opengl/formats.c`), one to four components: 8-bit and 16-bit normalised (`r8`, `r16`); half floats
         * given as full floats (`r16f`, uploaded as GL_FLOAT into GL_R16F) or as half floats (`r16hf`); full floats
         * (`r32f`); 8-bit and 16-bit unsigned integers (`r8u`, `r16u`), read nearest only. Its signed formats are left
         * out, as its own OpenGL backend leaves them.
         */
        private val FORMATS: Map<String, Format> = buildMap {
            listOf("r", "rg", "rgb", "rgba").forEachIndexed { i, prefix ->
                put("${prefix}8", Format(i + 1, 8, float = false))
                put("${prefix}16", Format(i + 1, 16, float = false, heldBits = 32))
                put("${prefix}16f", Format(i + 1, 32, float = true, heldBits = 16))
                put("${prefix}16hf", Format(i + 1, 16, float = true))
                put("${prefix}32f", Format(i + 1, 32, float = true))
                put("${prefix}8u", Format(i + 1, 8, float = false, integer = true))
                put("${prefix}16u", Format(i + 1, 16, float = false, integer = true))
            }
        }

        /**
         * The GLSL types a buffer's variable may have, as libplacebo's `pl_var_glsl_types` names them (`src/gpu.c`):
         * the type's columns and rows, its scalars 4 bytes each.
         */
        private val TYPES: Map<String, Pair<Int, Int>> = mapOf(
            "float" to (1 to 1), "vec2" to (1 to 2), "vec3" to (1 to 3), "vec4" to (1 to 4),
            "mat2" to (2 to 2), "mat2x3" to (2 to 3), "mat2x4" to (2 to 4), "mat3" to (3 to 3), "mat3x4" to (3 to 4),
            "mat4x2" to (4 to 2), "mat4x3" to (4 to 3), "mat4" to (4 to 4),
            "int" to (1 to 1), "ivec2" to (1 to 2), "ivec3" to (1 to 3), "ivec4" to (1 to 4),
            "uint" to (1 to 1), "uvec2" to (1 to 2), "uvec3" to (1 to 3), "uvec4" to (1 to 4),
        )

        /**
         * A variable of [type] ([columns] × [rows] scalars, [count] of them) placed after [offset] bytes as
         * libplacebo's `pl_std140_layout` and `pl_std430_layout` place it: its offset and its size.
         */
        private fun place(offset: Int, columns: Int, rows: Int, count: Int, std140: Boolean): Pair<Int, Int> {
            var stride = 4 * rows
            var align = stride
            if (rows == 3) align += 4
            if (columns * count > 1) {
                align = if (std140) (align + 15) / 16 * 16 else align
                stride = align
            }
            val at = (offset + align - 1) / align * align
            return at to stride * columns * count
        }

        /** [text], a `.glsl` user shader, read; [name] stands for its description where it gives none. */
        fun parse(text: String, name: String = ""): MpvShader {
            val passes = mutableListOf<Pass>()
            val parameters = linkedMapOf<String, String>()
            val textures = mutableListOf<Texture>()
            val buffers = mutableListOf<Buffer>()
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
                    one("BUFFER") != null -> {
                        val name = one("BUFFER")!!
                        buffer(name, one("STORAGE") != null, all("VAR"), body.toString()).fold(
                            { buffers += it },
                            { unsupported = unsupported ?: "the buffer $name: ${it.message}" },
                        )
                    }
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
            return MpvShader(description, passes, parameters, unsupported, textures, buffers)
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
            val format = header("FORMAT")?.trim()?.let { FORMATS[it] } ?: return refuse("a FORMAT it cannot hold")
            val linear = when (header("FILTER")?.trim()) {
                null, "NEAREST" -> false
                "LINEAR" -> true
                else -> return refuse("a FILTER it cannot read")
            }
            if (linear && format.integer) return refuse("integers cannot be read linearly")
            val border = when (header("BORDER")?.trim()) {
                null, "CLAMP" -> Border.CLAMP
                "REPEAT" -> Border.REPEAT
                "MIRROR" -> Border.MIRROR
                else -> return refuse("a BORDER it cannot read")
            }
            val data = hex(hex) ?: return refuse("a body that is not hex")
            val w = size[0]!!
            val h = size.getOrNull(1) ?: 1
            val d = size.getOrNull(2) ?: 0
            if (data.size != w * h * maxOf(d, 1) * format.texelBytes) return refuse("${data.size} bytes for its size")
            return Result.success(Texture(name.trim(), w, h, d, format, linear, border, data, size.size))
        }

        // [text] (whitespace around it left out) as the bytes its hex digits spell, or null where it is not hex.
        private fun hex(text: String): ByteArray? {
            val hex = text.trim()
            if (hex.length % 2 != 0 || hex.any { Character.digit(it, 16) < 0 }) return null
            return ByteArray(hex.length / 2) {
                (Character.digit(hex[2 * it], 16) * 16 + Character.digit(hex[2 * it + 1], 16)).toByte()
            }
        }

        // A `//!BUFFER` block as libplacebo's parse_buf reads it: a uniform block unless STORAGE, its VARs (a GLSL type
        // and a name, an array where `[n]` follows) placed one after another as std140 or std430 place them, its hex
        // body exactly as many bytes as they take — or nothing, for a storage block, which then starts as zeros.
        private fun buffer(name: String, storage: Boolean, declared: List<String>, body: String): Result<Buffer> {
            fun refuse(why: String) = Result.failure<Buffer>(IllegalArgumentException(why))
            var end = 0
            val variables = declared.map { line ->
                val type = line.trim().substringBefore(' ')
                val (columns, rows) = TYPES[type] ?: return refuse("a VAR of a type it cannot read: $type")
                val rest = line.trim().substringAfter(' ', "")
                val count = if ('[' in rest) {
                    rest.substringAfter('[').substringBefore(']').trim().toIntOrNull()?.takeIf { it > 0 }
                        ?: return refuse("an array size it cannot read: $rest")
                } else {
                    null
                }
                val variable = rest.substringBefore('[').trim().removeSuffix(";").trim()
                if (variable.isEmpty()) return refuse("a VAR without a name")
                val (offset, size) = place(end, columns, rows, count ?: 1, std140 = !storage)
                end = offset + size
                Variable(type, variable, count, offset)
            }
            if (variables.isEmpty()) return refuse("no VAR")
            val data = hex(body) ?: return refuse("a body that is not hex")
            if (data.isEmpty() && storage) return Result.success(Buffer(name.trim(), true, variables, ByteArray(end)))
            if (data.size != end) return refuse("${data.size} bytes for $end")
            return Result.success(Buffer(name.trim(), storage, variables, data))
        }

        /**
         * A size or condition expression as mpv evaluates it: reverse Polish over numbers and textures' sizes
         * (`NAME.w`, `NAME.width`, `NAME.h`, `NAME.height`, looked up in [sizes]) and the shader's parameters by name
         * ([values]) with `+ - * / % > < = !`; null where it cannot be evaluated (an unknown texture or parameter, too
         * few operands).
         */
        fun evaluate(expression: String, sizes: Map<String, Size>, values: Map<String, Double> = emptyMap()): Double? {
            val stack = ArrayDeque<Double>()
            for (token in expression.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                val number = token.toDoubleOrNull()
                when {
                    number != null -> stack.addLast(number)
                    token == "!" -> stack.addLast(if ((stack.removeLastOrNull() ?: return null) == 0.0) 1.0 else 0.0)
                    token.length == 1 && token in "+-*/%><=" -> {
                        val b = stack.removeLastOrNull() ?: return null
                        val a = stack.removeLastOrNull() ?: return null
                        stack.addLast(
                            when (token) {
                                "+" -> a + b
                                "-" -> a - b
                                "*" -> a * b
                                "/" -> a / b
                                "%" -> a % b
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
                    else -> stack.addLast(values[token] ?: return null)
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
            pass.whenExpression?.let { (evaluate(it, sizes, shader.values) ?: 0.0) != 0.0 } ?: true

        /** Its output size over [sizes]: `WIDTH` and `HEIGHT`, the hooked texture's where not given. */
        fun size(sizes: Map<String, Size>): Size {
            val hooked = sizes.getValue("HOOKED")
            fun of(expression: String?, fallback: Int) =
                expression?.let { evaluate(it, sizes, shader.values) }?.roundToInt()?.coerceAtLeast(1) ?: fallback
            return Size(of(pass.width, hooked.width), of(pass.height, hooked.height))
        }

        /**
         * The textures it reads when it hooks [hooked], as bound to texture units 0, 1, … ([source]'s samplers are
         * `<name>_raw`): `HOOKED` is the hooked texture under its own name.
         */
        fun textures(hooked: String): List<String> = pass.binds.map { if (it == "HOOKED") hooked else it }.distinct()
            .filter { name -> shader.textures.none { it.name == name } && shader.buffers.none { it.name == name } }

        /** The shader's embedded textures it reads, each a sampler of its own name, in the order it binds them. */
        fun userTextures(): List<Texture> = pass.binds.distinct().mapNotNull { name ->
            shader.textures.firstOrNull { it.name == name }
        }

        /** The shader's buffers it reads, in the order it binds them: the binding points 0, 1, …. */
        fun buffers(): List<Buffer> = pass.binds.distinct().mapNotNull { name ->
            shader.buffers.firstOrNull { it.name == name }
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
         * Embedded textures are samplers of their own names (integer ones `usampler`s); a one-dimensional one, which
         * OpenGL ES has not, is held as a row, and the calls reading it (`texture`, `textureLod`, `texelFetch`,
         * `textureSize`) are turned into functions reading that row. Buffers are interface blocks of their variables,
         * as libplacebo declares them: `std140` uniform blocks, `std430` storage blocks at their binding point (GLSL ES
         * 3.10, which storage blocks need).
         */
        fun source(hooked: String): String = buildString {
            val gather = pass.body.contains("gather", ignoreCase = true)
            val compute = pass.compute
            val buffers = buffers()
            val storage = buffers.any { it.storage }
            appendLine(if (gather || compute != null || storage) "#version 310 es" else "#version 300 es")
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
            var body = pass.body
            for (texture in userTextures()) {
                val name = texture.name
                val prefix = if (texture.format.integer) "u" else ""
                appendLine("uniform highp ${prefix}sampler${if (texture.dimensions == 3) "3D" else "2D"} $name;")
                if (texture.dimensions != 1) {
                    appendLine("#define ${name}_tex(pos) (textureLod($name, pos, 0.0))")
                    continue
                }
                val vec = "${prefix}vec4"
                val read = "${name}_mpv1d_"
                val row = "vec2(x, 0.5)"
                appendLine("$vec ${read}texture(float x) { return texture($name, $row); }")
                // A bias only where the lookup has derivatives: in a fragment shader.
                if (compute == null) {
                    appendLine("$vec ${read}texture(float x, float bias) { return texture($name, $row, bias); }")
                }
                appendLine("$vec ${read}textureLod(float x, float lod) { return textureLod($name, $row, lod); }")
                appendLine("$vec ${read}texelFetch(int i, int lod) { return texelFetch($name, ivec2(i, 0), lod); }")
                appendLine("int ${read}textureSize(int lod) { return textureSize($name, lod).x; }")
                appendLine("#define ${name}_tex(pos) (textureLod($name, vec2(pos, 0.5), 0.0))")
                val calls = "\\b(texture|textureLod|texelFetch|textureSize)\\s*\\(\\s*${Regex.escape(name)}\\s*,\\s*"
                body = body.replace(Regex(calls)) { "$read${it.groupValues[1]}(" }
            }
            buffers.forEachIndexed { binding, buffer ->
                if (buffer.storage) {
                    appendLine("layout(std430, binding = $binding) coherent restrict buffer ${buffer.name} {")
                } else {
                    appendLine("layout(std140) uniform ${buffer.name} {")
                }
                for (v in buffer.variables) appendLine("    ${v.type} ${v.name}${v.count?.let { "[$it]" } ?: ""};")
                appendLine("};")
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
            appendLine(body)
            appendLine(if (compute == null) "void main() { mpvOutput = hook(); }" else "void main() { hook(); }")
        }
    }
}
