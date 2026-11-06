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
 * alike: the stage's picture is RGB already), on its brightness (`LUMA`, the picture's Y, the colour following), and
 * after it was enlarged to the screen (`POSTKERNEL`, `SCALED`, `OUTPUT`); a pass hooking anything else is left out.
 * Compute passes (`//!COMPUTE`) and embedded textures (`//!TEXTURE`) are not run: such a shader is [supported] false.
 */
class MpvShader private constructor(
    /** What the file calls itself: its first pass's `DESC`, or the [name] it was loaded under. */
    val description: String,
    val passes: List<Pass>,
    /** `//!PARAM` values, as `#define`s every pass gets. */
    val parameters: Map<String, String>,
    /** Why the stages cannot run it, or null. */
    val unsupported: String?,
) {
    val supported: Boolean get() = unsupported == null

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
    )

    /** A texture's size in pixels. */
    data class Size(val width: Int, val height: Int)

    companion object {
        /** The hook points the stages run before enlarging, in mpv's order. */
        val BEFORE_SCALING = listOf("LUMA", "NATIVE", "RGB", "MAIN", "LINEAR", "PREKERNEL")

        /** The hook points after enlarging, in mpv's order. */
        val AFTER_SCALING = listOf("POSTKERNEL", "SCALED", "OUTPUT")

        /** [text], a `.glsl` user shader, read; [name] stands for its description where it gives none. */
        fun parse(text: String, name: String = ""): MpvShader {
            val passes = mutableListOf<Pass>()
            val parameters = linkedMapOf<String, String>()
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
                    one("TEXTURE") != null -> unsupported = unsupported ?: "embedded textures (//!TEXTURE)"
                    one("BUFFER") != null -> unsupported = unsupported ?: "storage buffers (//!BUFFER)"
                    one("PARAM") != null -> parameters[one("PARAM")!!] = body.toString().trim()
                    one("HOOK") != null -> {
                        if (one("COMPUTE") != null) unsupported = unsupported ?: "compute passes (//!COMPUTE)"
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
                        )
                    }
                }
            }
            if (passes.isEmpty()) unsupported = unsupported ?: "no hook passes"
            val description = passes.firstOrNull()?.description?.takeIf { it.isNotEmpty() } ?: name
            return MpvShader(description, passes, parameters, unsupported)
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
            appendLine(if (gather) "#version 310 es" else "#version 300 es")
            appendLine("precision highp float;")
            appendLine("precision highp int;")
            appendLine("precision highp sampler2D;")
            appendLine("out vec4 mpvOutput;")
            appendLine("uniform vec2 mpvTarget;")
            appendLine("uniform int frame;")
            appendLine("uniform float random;")
            appendLine("uniform vec2 input_size;")
            appendLine("uniform vec2 target_size;")
            appendLine("uniform vec2 tex_offset;")
            for ((name, value) in shader.parameters) appendLine("#define $name ($value)")
            for (name in textures(hooked)) {
                appendLine("uniform sampler2D ${name}_raw;")
                appendLine("uniform vec2 ${name}_size;")
                appendLine("#define ${name}_pos (gl_FragCoord.xy / mpvTarget)")
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
            appendLine("void main() { mpvOutput = hook(); }")
        }
    }
}
