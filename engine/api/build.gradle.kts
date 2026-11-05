// The playback engine the screens talk to, the same on every platform: what can be played, the tracks, the live
// knobs and the engine's commands and events. Plain JVM — each platform's engine implements it (README "Platform
// support", AGENTS.md).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(libs.junit)
}
