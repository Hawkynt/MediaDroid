plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":core:library"))
    implementation(libs.smbj)
    implementation(libs.commons.net)
    implementation(libs.sshj)
    implementation(libs.okhttp)
    // smbj and sshj pull different BouncyCastle releases; the modules must be one release, or their
    // provider fails to load (a field missing between bcprov and bcutil).
    constraints {
        implementation(libs.bcprov)
        implementation(libs.bcpkix)
        implementation(libs.bcutil)
    }
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    // In-process servers for the integration tier.
    testImplementation(libs.ftpserver.core)
    testImplementation(libs.sshd.core)
    testImplementation(libs.sshd.sftp)
    testImplementation(libs.nfs4j.core)
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
