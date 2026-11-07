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
    // Xbrz: the table of its patterns' shares the xBRZ shader reads.
    implementation(project(":core:imaging"))
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
    // PlaybackEngineContract: what every engine does alike (DesktopEngineContractTest).
    testImplementation(testFixtures(project(":engine:api")))
}

// The neural networks the app carries (NeuralModels.CARRIED), the same files as the Android app's assets, on the
// classpath under models/: DesktopNeural unpacks them into the app-data folder on first use, digest-checked there.
val carriedModels = tasks.register<Sync>("carriedModels") {
    from(rootProject.file("app/src/main/assets/models")) {
        include("rife-4.25-lite/**", "ifrnet-s/**", "waifu2x-cunet/**", "realcugan-se/**")
    }
    into(layout.buildDirectory.dir("generated/carried/models"))
}
sourceSets["main"].resources.srcDir(carriedModels.map { it.destinationDir.parentFile })

tasks.test {
    systemProperty("mediadroid.ffmpegLicence", if (fullFfmpeg) "GPL" else "LGPL")
    // The native library's tests (NativeNetworksTest, NativeNeuralTest, LibAssTest; LibAssEngineTest with FFmpeg) need
    // it built for this system first — CMake, a C++ compiler, ncnn's release and libass's sources: `-PnativeLibrary`
    // builds it and brings the tests in, `./gradlew test` leaves them out. _desktop.yml runs them on Linux and Windows
    // (Linux arm64, which has no FFmpeg natives, the ones without FFmpeg).
    if (providers.gradleProperty("nativeLibrary").isPresent) {
        dependsOn(":native:desktopLibrary")
        val library = rootProject.file("native/build/desktop/lib")
        inputs.dir(library)
        systemProperty("mediadroid.native", library.path)
        // The upscalers' fetched models, kept between runs (NativeNeuralTest).
        systemProperty("mediadroid.testModels", layout.buildDirectory.dir("test-models").get().asFile.path)
    } else {
        exclude("**/NativeNetworksTest*", "**/NativeNeuralTest*", "**/LibAssTest*", "**/LibAssEngineTest*")
    }
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

// What the stage draws, a digest per case, into a file: ./gradlew :engine:desktop:stageDigests -Pout=<file>, before
// and after a change that should draw the same pixels (StageDigests).
tasks.register<JavaExec>("stageDigests") {
    group = "verification"
    description = "Writes a digest of what the stage draws for every pass and its variants"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.hawkynt.mediadroid.engine.desktop.StageDigestsKt")
    args(providers.gradleProperty("out").getOrElse(layout.buildDirectory.file("stage-digests.txt").get().asFile.path))
    dependsOn("testClasses")
}

// The real stage as tools/shader-check's oracle.sh drives it (StageRender): the classpath to run it with, written to
// build/stage-render.classpath (with the test classes built), as java -cp "$(cat …)" runs it once per command.
tasks.register("stageRenderClasspath") {
    group = "verification"
    description = "Writes the classpath tools/shader-check runs the stage's StageRender with"
    dependsOn("testClasses")
    val classpath = sourceSets["test"].runtimeClasspath
    val out = layout.buildDirectory.file("stage-render.classpath")
    inputs.files(classpath)
    outputs.file(out)
    doLast { out.get().asFile.writeText(classpath.files.joinToString(File.pathSeparator)) }
}
