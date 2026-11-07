// The Android playback engine: Media3's ExoPlayer and the glue around it — renderers, FFmpeg's extension, libass,
// second subtitles, teletext, loudness, sidecar audio, network sources — behind :engine:api's PlaybackEngine
// (README "Platform support", AGENTS.md). The app's screens talk to the engine through the API.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.hawkynt.mediadroid.engine.android"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

// Media3 reads android.os.Build to learn whether it runs in a test, so the engine's tests (the contract on the real
// ExoPlayer, the subtitle routings) run under Robolectric: seconds to start its sandbox, so they run with the
// screenshots' tier (-Pscreenshots), as the app's own Robolectric tests do; ci.yml runs them on every pull request.
// LoudnessAudioProcessorTest needs no Android and stays in the fast tier.
tasks.withType<Test>().configureEach {
    if (!project.hasProperty("screenshots")) {
        exclude(
            "**/AndroidEngineContractTest*",
            "**/AssRoutingTest*",
            "**/TeletextRoutingTest*",
            "**/SecondSubtitlesTest*",
        )
        failOnNoDiscoveredTests = false
    }
    // Robolectric's Android 16 sandbox reaches into these JDK internals.
    jvmArgs(
        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
    )
}

// FFmpeg's decoder extension (see app/build.gradle.kts and tools/ffmpeg/README.md): the app ships it; the engine
// compiles against it and its tests run with it.
val ffmpegAar = files(
    rootProject.file("app").resolve(
        providers.gradleProperty("ffmpegAar").getOrElse("libs/lib-decoder-ffmpeg-release.aar"),
    ),
)

dependencies {
    api(project(":engine:api"))
    api(project(":core:player"))
    api(project(":core:library"))
    api(libs.media3.exoplayer)
    api(libs.media3.ui)
    compileOnly(ffmpegAar)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    // ShadowMediaCodecConfig: Robolectric's codecs, which pass the samples through as Media3's own tests play them.
    testImplementation(libs.media3.test.utils.robolectric)
    testImplementation(testFixtures(project(":engine:api")))
    testImplementation(ffmpegAar)
}
