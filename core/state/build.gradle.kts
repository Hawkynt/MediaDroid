// The household's memory, one model for every platform (#286): what was watched and where it stopped, the pictures
// per video, folder and household, the sources, the settings. Each app only reads and writes the file it lives in.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":core:player"))
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
}
