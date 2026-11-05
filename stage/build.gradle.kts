// The picture stage shared by every platform: its shaders (src/main/glsl), compiled into the object Shaders, and —
// as #284 goes on — the passes themselves against an OpenGL ES interface (README "Platform support", AGENTS.md).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

/**
 * The shaders under src/main/glsl compiled in: one Kotlin string constant per `.vert`, `.frag`, `.agsl` or `.comp`
 * (an ncnn compute shader, handed to the native part) file, named
 * after its path (`video/fsr-easu.frag` → `Shaders.VIDEO_FSR_EASU`), in the generated object
 * `com.hawkynt.mediadroid.shaders.Shaders`. A line `#include "name.glsl"` is replaced by that file, beside the
 * shader; `.glsl` files are such shared parts only and get no constant of their own.
 */
abstract class ShaderSources : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val glsl: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val root = glsl.get().asFile
        val include = Regex("""^\s*#include\s+"([^"]+)"\s*$""")
        fun text(file: File, seen: Set<File> = emptySet()): String {
            check(file !in seen) { "$file includes itself" }
            return file.readLines().joinToString("\n") { line ->
                include.matchEntire(line)?.let { text(file.resolveSibling(it.groupValues[1]), seen + file) } ?: line
            }
        }
        fun literal(value: String) = buildString {
            append('"')
            for (c in value) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '$' -> append("\\$")
                    '\n' -> append("\\n")
                    else -> append(c)
                }
            }
            append('"')
        }
        val shaders = root.walkTopDown()
            .filter { it.isFile && it.extension in setOf("vert", "frag", "agsl", "comp") }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
        val out = outputDir.get().asFile.resolve("com/hawkynt/mediadroid/shaders/Shaders.kt")
        out.parentFile.mkdirs()
        out.writeText(
            buildString {
                appendLine("package com.hawkynt.mediadroid.shaders")
                appendLine()
                appendLine("/** The shaders of stage/src/main/glsl, compiled in (made by the shaderSources task). */")
                appendLine("object Shaders {")
                for (file in shaders) {
                    val path = file.relativeTo(root).invariantSeparatorsPath
                    val name = path.substringBeforeLast('.').uppercase().replace(Regex("[^A-Z0-9]+"), "_")
                    appendLine("    /** glsl/$path */")
                    val lines = (text(file) + "\n").split("\n").dropLast(1).map { literal(it + "\n") }
                    appendLine("    const val $name =")
                    appendLine(lines.joinToString(" +\n") { "        $it" })
                }
                appendLine("}")
            },
        )
    }
}

val shaderSources = tasks.register<ShaderSources>("shaderSources") {
    glsl.set(layout.projectDirectory.dir("src/main/glsl"))
    outputDir.set(layout.buildDirectory.dir("generated/source/shaders"))
}

kotlin.sourceSets["main"].kotlin.srcDir(shaderSources.map { it.outputDir })
