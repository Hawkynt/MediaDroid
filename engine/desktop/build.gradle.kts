// The desktop playback engine (Windows, Linux): FFmpeg through Bytedeco's JavaCPP presets (Apache-2.0 bindings over
// FFmpeg's LGPL build) — demuxing, decoding, resampling — with the JVM's own sound output and an audio-clocked picture.
// See README "Platform support".
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

// The natives for the desktops we ship to; the plain artifacts carry the Java side. Published builds take FFmpeg's
// LGPL build; `-PfullFfmpeg` takes Bytedeco's GPL build instead (x264, x265 …) — for the household's own machines
// only, never shipped (AGENTS.md).
// -PdesktopTarget=linux|windows|all picks whose natives come along: by default the building machine's own.
val target = providers.gradleProperty("desktopTarget").getOrElse(
    if (System.getProperty("os.name").startsWith("Windows")) "windows" else "linux",
)
val platforms = listOf("linux-x86_64", "windows-x86_64").filter { target == "all" || it.startsWith(target) }
val fullFfmpeg = providers.gradleProperty("fullFfmpeg").isPresent
val ffmpegFlavour = if (fullFfmpeg) "-gpl" else ""

dependencies {
    api(project(":engine:api"))
    api(project(":core:player"))
    // FsFile: what the network sources (core:sources) hand over to be played.
    api(project(":core:library"))
    implementation(project(":stage"))
    implementation(libs.lwjgl)
    implementation(libs.lwjgl.glfw)
    implementation(libs.lwjgl.opengl)
    implementation(libs.ffmpeg)
    implementation(libs.javacpp)
    platforms.forEach { platform ->
        runtimeOnly(variantOf(libs.ffmpeg) { classifier(platform + ffmpegFlavour) })
        runtimeOnly(variantOf(libs.javacpp) { classifier(platform) })
        val natives = if (platform.startsWith("windows")) "natives-windows" else "natives-linux"
        runtimeOnly(variantOf(libs.lwjgl) { classifier(natives) })
        runtimeOnly(variantOf(libs.lwjgl.glfw) { classifier(natives) })
        runtimeOnly(variantOf(libs.lwjgl.opengl) { classifier(natives) })
    }
    testImplementation(libs.junit)
}

tasks.test {
    systemProperty("mediadroid.ffmpegLicence", if (fullFfmpeg) "GPL" else "LGPL")
}

// Every video in a folder opened and its first second decoded: ./gradlew :engine:desktop:probe -Pfolder=<dir>.
tasks.register<JavaExec>("probe") {
    group = "verification"
    description = "Opens and decodes the start of every video in a folder"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.hawkynt.mediadroid.engine.desktop.ProbeKt")
    args(providers.gradleProperty("folder").getOrElse("."))
    dependsOn("testClasses")
}
