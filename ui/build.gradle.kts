// The screens of every platform, in Compose Multiplatform: the Android app and the desktop app show the same ones
// (README "Platform support", #286). What a screen needs of its platform comes through interfaces each app fills in.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)

    androidLibrary {
        namespace = "com.hawkynt.mediadroid.ui"
        compileSdk = 37
        minSdk = 26
    }

    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(project(":core:player"))
        }
    }
}
