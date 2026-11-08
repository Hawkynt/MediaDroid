plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":core:library"))
    testImplementation(libs.junit)
}

// Timings of the work the player does on the processor for every frame (Film's field matching and
// decimation; Motion's search once it is in): ./gradlew :core:player:benchmark. Not part of `test`: it
// measures, it does not check — the oracle tests guard what the optimised code computes.
tasks.register<JavaExec>("benchmark") {
    group = "verification"
    description = "Times the per-frame processor work on standard-definition pictures"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.hawkynt.mediadroyd.player.BenchmarkKt")
    dependsOn("testClasses")
}

// The loudness test signals as raw float32 files, for the oracles in src/test/resources/loudness to measure:
// ./gradlew :core:player:loudnessSignals -Pfolder=<dir>.
tasks.register<JavaExec>("loudnessSignals") {
    group = "verification"
    description = "Writes the loudness test signals for the libebur128 and FFmpeg oracles"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.hawkynt.mediadroyd.player.LoudnessSignals")
    args(providers.gradleProperty("folder").getOrElse("loudness-signals"))
    dependsOn("testClasses")
}
