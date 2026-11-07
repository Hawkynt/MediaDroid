plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}

// A failing test says why in the build's own output — the whole exception with its causes — so a failure on a CI
// runner (a native library that would not load on Windows, say) can be read from the log without the report.
subprojects {
    tasks.withType<Test>().configureEach {
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showCauses = true
            showStackTraces = true
        }
    }
}

// A plain JVM module's jar is named after its whole path (core-player.jar, engine-desktop.jar): :desktop and
// :engine:desktop would otherwise both make desktop.jar, and a folder of jars (the portable Windows zip) keeps one.
subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        tasks.withType<Jar>().configureEach {
            archiveBaseName.set(project.path.removePrefix(":").replace(':', '-'))
        }
    }
}
