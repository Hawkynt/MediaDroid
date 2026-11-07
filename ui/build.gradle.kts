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
        // The drawables (composeResources) travel with the library.
        androidResources { enable = true }
    }

    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            // Res.drawable.* for both apps; the apps draw them too, so the resources reach them.
            api(compose.components.resources)
            implementation(libs.compose.mp.material.icons)
            implementation(libs.compose.mp.backhandler)
            // The art on the walls, from files the platform hands over (MediaArt).
            implementation(libs.coil.compose)
            implementation(project(":core:player"))
            // The household's memory, one model on every platform; the screens read and change it.
            api(project(":core:state"))
            // A network source tried from its dialog before it is kept.
            implementation(project(":core:sources"))
        }
    }
}

// The drawables both apps show (the sidebar's, the onboarding's, the Files browser's), as Res.drawable.*.
compose.resources {
    publicResClass = true
    packageOfResClass = "com.hawkynt.mediadroid.ui.resources"
}
