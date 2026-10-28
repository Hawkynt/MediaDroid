plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":core:library"))
    implementation(libs.smbj)
    testImplementation(libs.junit)
}

// Tests against real servers started on the machine (smbd, …) are the slow tier: `./gradlew test`
// leaves them out, and `./gradlew :core:sources:test -Pintegration` (the pull-request gate, which
// installs the servers) runs them.
tasks.test {
    if (!project.hasProperty("integration")) {
        exclude("**/integration/**")
        failOnNoDiscoveredTests = false
    }
}
