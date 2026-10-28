plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":core:imaging"))
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
}
