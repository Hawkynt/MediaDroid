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
    mainClass.set("com.hawkynt.mediadroid.player.BenchmarkKt")
    dependsOn("testClasses")
}
