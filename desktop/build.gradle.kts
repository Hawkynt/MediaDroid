// The desktop app for Windows and Linux: a Compose Desktop window over the shared cores and the desktop engine
// (README "Platform support"). `./gradlew :desktop:run` starts it; `-PfullFfmpeg` plays with the GPL FFmpeg build on
// the household's own machines.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core:library"))
    implementation(project(":core:nfo"))
    implementation(project(":core:imaging"))
    implementation(project(":core:player"))
    implementation(project(":engine:desktop"))
    implementation(compose.desktop.linux_x64)
    implementation(compose.desktop.windows_x64)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    testImplementation(libs.junit)
}

compose.desktop {
    application {
        mainClass = "com.hawkynt.mediadroid.desktop.MainKt"
        jvmArgs += listOf("-Xmx2g")
        if (providers.gradleProperty("fullFfmpeg").isPresent) jvmArgs += "-Dmediadroid.fullFfmpeg=true"
    }
}
