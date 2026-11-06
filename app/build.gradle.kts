import java.time.LocalDate

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

// ncnn (Tencent, BSD-3-Clause): the prebuilt Android release the native tree (native/) builds against, fetched and
// checked by :native:ncnnAndroid; libass and what it needs as sources, by :native:assSources.
evaluationDependsOn(":native")
@Suppress("UNCHECKED_CAST")
val ncnnRoot = project(":native").extra["ncnnAndroidRoot"] as Provider<Directory>
@Suppress("UNCHECKED_CAST")
val assRoot = project(":native").extra["assRoot"] as Provider<Directory>

android {
    // BouncyCastle's modules (for SMB and SFTP) each carry the same licence file; one copy is kept.
    packaging {
        resources {
            pickFirsts += "META-INF/LICENSE.md"
        }
    }
    namespace = "com.hawkynt.mediadroid"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.hawkynt.mediadroid"
        minSdk = 26
        targetSdk = 36

        // Date-based versioning, matching the repo's vYYYYMMDD release markers:
        // the version code is the build date, so it only ever moves forward.
        val today = LocalDate.now()
        versionCode = today.year * 10000 + today.monthValue * 100 + today.dayOfMonth
        versionName = "%04d.%02d.%02d".format(today.year, today.monthValue, today.dayOfMonth)

        // The native tree (native/: neural networks on ncnn) for every ABI ncnn's release has and Android uses.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DNCNN_ROOT=${ncnnRoot.get().asFile.absolutePath}"
                arguments += "-DASS_SOURCES=${assRoot.get().asFile.absolutePath}"
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = rootProject.file("native/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
    }

    // The neural upscaler's model is mapped straight from the APK, which needs it stored uncompressed.
    androidResources {
        noCompress += "tflite"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) {
        dependsOn(":native:ncnnAndroid", ":native:assSources")
    }
}

// The README screenshots are rendered by the real screens on the JVM (Robolectric + Roborazzi) from a
// generated demo library. They are the slow tier: `./gradlew test` leaves them out, and
// `./gradlew :app:recordRoborazziDebug -Pscreenshots` (the screenshots workflow) writes screenshots/*.png.
// The app's other Robolectric tests (AssRoutingTest: seconds to start the sandbox) run with them.
tasks.withType<Test>().configureEach {
    if (!project.hasProperty("screenshots")) {
        exclude("**/screenshots/**", "**/AssRoutingTest*")
        // Without them a filtered run may find none of the app's few fast-tier tests; that is expected.
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
    implementation(project(":stage"))
    implementation(project(":core:imaging"))
    implementation(project(":core:nfo"))
    implementation(project(":core:library"))
    implementation(project(":core:player"))
    implementation(project(":core:sources"))
    implementation(project(":engine:api"))

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
    // with an LGPL-only FFmpeg 6.0 — see tools/ffmpeg/README.md. `-PffmpegAar=<path>` swaps in another
    // build of it (buildnonlgpl.sh passes the GPL one, for your own device only) without touching this file.
    implementation(files(providers.gradleProperty("ffmpegAar").getOrElse("libs/lib-decoder-ffmpeg-release.aar")))
    implementation(libs.coil.compose)
    // Neural upscaling (Real-ESRGAN, see NeuralUpscaler): TensorFlow Lite with its GPU and NNAPI back-ends.
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu-api:2.16.1")
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
