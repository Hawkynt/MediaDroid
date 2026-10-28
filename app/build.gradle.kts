import java.time.LocalDate

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.hawkynt.mediadroid"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hawkynt.mediadroid"
        minSdk = 26
        targetSdk = 36

        // Date-based versioning, matching the repo's vYYYYMMDD release markers:
        // the version code is the build date, so it only ever moves forward.
        val today = LocalDate.now()
        versionCode = today.year * 10000 + today.monthValue * 100 + today.dayOfMonth
        versionName = "%04d.%02d.%02d".format(today.year, today.monthValue, today.dayOfMonth)
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
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
    // with an LGPL-only FFmpeg 6.0 — see tools/ffmpeg/README.md.
    implementation(files("libs/lib-decoder-ffmpeg-release.aar"))
    implementation(libs.coil.compose)
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
