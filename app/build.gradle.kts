import java.time.LocalDate

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
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
    // FFmpeg audio decoder extension (AC3/E-AC3/DTS/TrueHD and other codecs the
    // device itself cannot decode), built from the androidx/media 1.11.1 sources
    // with an LGPL-only FFmpeg 6.0 — see tools/ffmpeg/README.md.
    implementation(files("libs/lib-decoder-ffmpeg-release.aar"))
    implementation(libs.coil.compose)
    implementation(libs.serialization.json)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
