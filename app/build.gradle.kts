import java.net.URI
import java.security.MessageDigest
import java.time.LocalDate
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

// ncnn (Tencent, BSD-3-Clause): the prebuilt Android release the native part builds against, fetched by ncnnPrebuilt.
val ncnnVersion = "20261020"
val ncnnRoot = layout.buildDirectory.dir("ncnn/$ncnnVersion")

android {
    // BouncyCastle's modules (for SMB and SFTP) each carry the same licence file; one copy is kept.
    packaging {
        resources {
            pickFirsts += "META-INF/LICENSE.md"
        }
    }
    namespace = "com.hawkynt.mediadroid"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.hawkynt.mediadroid"
        minSdk = 26
        targetSdk = 36

        // Date-based versioning, matching the repo's vYYYYMMDD release markers:
        // the version code is the build date, so it only ever moves forward.
        val today = LocalDate.now()
        versionCode = today.year * 10000 + today.monthValue * 100 + today.dayOfMonth
        versionName = "%04d.%02d.%02d".format(today.year, today.monthValue, today.dayOfMonth)

        // The native part (src/main/cpp: neural networks on ncnn) for every ABI ncnn's release has and Android uses.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DNCNN_ROOT=${ncnnRoot.get().asFile.absolutePath}"
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
    }

    // The neural upscaler's model is mapped straight from the APK, which needs it stored uncompressed.
    androidResources {
        noCompress += "tflite"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

/**
 * ncnn's prebuilt Android release, downloaded once into the build directory and checked against its digest before it
 * is unpacked (the archive's top folder dropped), for the CMake build of src/main/cpp.
 */
abstract class NcnnPrebuilt : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val archives: ArchiveOperations

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun fetch() {
        val zip = File(temporaryDir, "ncnn.zip")
        URI(url.get()).toURL().openStream().use { input -> zip.outputStream().use { input.copyTo(it) } }
        val digest = MessageDigest.getInstance("SHA-256").digest(zip.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(digest == sha256.get()) { "ncnn's archive has the digest $digest, not ${sha256.get()}" }
        val target = outputDir.get().asFile
        files.delete { delete(target) }
        files.copy {
            from(archives.zipTree(zip))
            into(target)
            eachFile { path = path.substringAfter('/') }
            includeEmptyDirs = false
        }
        zip.delete()
    }
}

val ncnnPrebuilt = tasks.register<NcnnPrebuilt>("ncnnPrebuilt") {
    url.set("https://github.com/Tencent/ncnn/releases/download/$ncnnVersion/ncnn-$ncnnVersion-android-vulkan.zip")
    sha256.set("6e0c5f9b397faf32b923de2ef6210af9bf375851869dceff06a8005331981086")
    outputDir.set(ncnnRoot)
}

tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) dependsOn(ncnnPrebuilt)
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
                appendLine("/** The shaders of app/src/main/glsl, compiled in (made by the shaderSources task). */")
                appendLine("internal object Shaders {")
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

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(shaderSources, ShaderSources::outputDir)
    }
}

// The README screenshots are rendered by the real screens on the JVM (Robolectric + Roborazzi) from a
// generated demo library. They are the slow tier: `./gradlew test` leaves them out, and
// `./gradlew :app:recordRoborazziDebug -Pscreenshots` (the screenshots workflow) writes screenshots/*.png.
tasks.withType<Test>().configureEach {
    if (!project.hasProperty("screenshots")) {
        exclude("**/screenshots/**")
        // Without them the app has no fast-tier tests of its own; that is expected, not a misconfiguration.
        failOnNoDiscoveredTests = false
    }
    // Robolectric's Android 16 sandbox reaches into these JDK internals.
    jvmArgs(
        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
    )
}

dependencies {
    implementation(project(":core:imaging"))
    implementation(project(":core:nfo"))
    implementation(project(":core:library"))
    implementation(project(":core:player"))
    implementation(project(":core:sources"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.media3.inspector)
    // FFmpeg audio decoder extension (AC3/E-AC3/DTS/TrueHD and other codecs the
    // device itself cannot decode), built from the androidx/media 1.11.1 sources
    // with an LGPL-only FFmpeg 6.0 — see tools/ffmpeg/README.md. `-PffmpegAar=<path>` swaps in another
    // build of it (buildnonlgpl.sh passes the GPL one, for your own device only) without touching this file.
    implementation(files(providers.gradleProperty("ffmpegAar").getOrElse("libs/lib-decoder-ffmpeg-release.aar")))
    implementation(libs.coil.compose)
    // Neural upscaling (Real-ESRGAN, see NeuralUpscaler): TensorFlow Lite with its GPU and NNAPI back-ends.
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu-api:2.16.1")
    implementation(libs.serialization.json)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    debugImplementation(libs.compose.ui.test.manifest)
}
