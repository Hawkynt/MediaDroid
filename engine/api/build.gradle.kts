// The playback engine the screens talk to, the same on every platform: what can be played, the tracks, the live
// knobs and the engine's commands and events. Plain JVM — each platform's engine implements it (README "Platform
// support", AGENTS.md).
plugins {
    alias(libs.plugins.kotlin.jvm)
    // The contract every engine passes and the fake engine that passes it (src/testFixtures), for the engines' and
    // the screens' tests: testImplementation(testFixtures(project(":engine:api"))).
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testFixturesImplementation(libs.junit)
    testImplementation(libs.junit)
}
