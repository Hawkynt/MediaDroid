import java.net.URI
import java.security.MessageDigest
import java.time.LocalDate

// The desktop app for Windows and Linux: a Compose Desktop window over the shared cores and the desktop engine
// (README "Platform support"). `./gradlew :desktop:run` starts it; `-PfullFfmpeg` plays with the GPL FFmpeg build on
// the household's own machines.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core:library"))
    implementation(project(":core:nfo"))
    implementation(project(":core:imaging"))
    implementation(project(":core:player"))
    implementation(project(":engine:desktop"))
    // LWJGL's native calls, for the system functions the window needs (ScreenKeeper); its natives come with the engine.
    implementation(libs.lwjgl)
    // The window's natives (Skia) for the system built for: -PdesktopTarget=linux|windows|all, the building machine's
    // own by default — as :engine:desktop picks FFmpeg's and LWJGL's.
    val target = providers.gradleProperty("desktopTarget").getOrElse(
        if (System.getProperty("os.name").startsWith("Windows")) "windows" else "linux",
    )
    if (target == "linux" || target == "all") implementation(compose.desktop.linux_x64)
    if (target == "windows" || target == "all") implementation(compose.desktop.windows_x64)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    testImplementation(libs.junit)
}

// The icons (desktop/icons) on the classpath too, for the window's own icon.
sourceSets["main"].resources.srcDir("icons")

compose.desktop {
    application {
        mainClass = "com.hawkynt.mediadroid.desktop.MainKt"
        jvmArgs += listOf("-Xmx2g")
        if (providers.gradleProperty("fullFfmpeg").isPresent) jvmArgs += "-Dmediadroid.fullFfmpeg=true"
        nativeDistributions {
            packageName = "MediaDroid"
            // The build's date, as the Android app's versionName: YYYY.MM.DD (MSI wants each part below 256: the
            // year as its last two digits there).
            val today = LocalDate.now()
            packageVersion = "%d.%d.%d".format(today.year % 100, today.monthValue, today.dayOfMonth)
            description = "Plays the movies and shows already organised in your folders"
            vendor = "Hawkynt"
            licenseFile.set(rootProject.file("LICENSE"))
            modules("java.desktop", "java.naming", "java.sql", "jdk.unsupported", "java.management")
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.AppImage,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
            )
            linux { iconFile.set(rootProject.file("desktop/icons/mediadroid.png")) }
            windows {
                menu = true
                shortcut = true
                upgradeUuid = "8f0c2b44-3d61-4a5e-9b2e-6d1c7f3a2e90"
                iconFile.set(rootProject.file("desktop/icons/mediadroid.ico"))
            }
        }
    }
}

// desktop/icons drawn again from the Android launcher icon's layers: ./gradlew :desktop:icons.
tasks.register<JavaExec>("icons") {
    group = "build"
    description = "Draws the desktop icons from the Android launcher icon"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.hawkynt.mediadroid.desktop.IconsKt")
    args(rootDir.path)
    dependsOn("testClasses")
}

// The portable Windows zip (unpack anywhere, run MediaDroid.bat; settings stay beside it): the app's jars built for
// Windows (-PdesktopTarget=windows), Temurin's Windows JRE (fetched once, checked against its digest), a launcher and
// the `portable` marker. ./gradlew :desktop:windowsZip -PdesktopTarget=windows → desktop/build/dist/.
// The MSI is built by jpackage on Windows itself (packageMsi); this zip is made on any system.
val windowsJre = "OpenJDK21U-jre_x64_windows_hotspot_21.0.13_9.zip"
val windowsJreUrl = "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/$windowsJre"
val windowsJreSha256 = "2332c0b4cff6bed722da3567c91d5e47568bcba863fa4945d0a8d6fc76158628"

val windowsJreFetch = tasks.register("windowsJreFetch") {
    val out = layout.buildDirectory.file("download/$windowsJre")
    outputs.file(out)
    doLast {
        val file = out.get().asFile
        file.parentFile.mkdirs()
        fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes())
            .joinToString("") { "%02x".format(it) }
        if (!file.exists() || sha(file) != windowsJreSha256) {
            URI(windowsJreUrl).toURL().openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        check(sha(file) == windowsJreSha256) { "$windowsJre does not match its digest" }
    }
}

// The JRE unpacked once, its top folder (jdk-…-jre) dropped.
val windowsJreUnpack = tasks.register<Sync>("windowsJreUnpack") {
    dependsOn(windowsJreFetch)
    from(windowsJreFetch.map { zipTree(it.outputs.files.singleFile) })
    eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray()) }
    includeEmptyDirs = false
    into(layout.buildDirectory.dir("windows-jre"))
}

tasks.register<Zip>("windowsZip") {
    group = "distribution"
    description = "The portable Windows zip: app, Temurin JRE, launcher"
    dependsOn(windowsJreUnpack, tasks.named("jar"))
    archiveFileName.set("MediaDroid-windows-x64.zip")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
    into("MediaDroid") {
        into("runtime") { from(windowsJreUnpack) }
        into("lib") {
            from(tasks.named("jar"))
            from(configurations.named("runtimeClasspath"))
        }
        from(rootProject.file("desktop/windows"))
        from(rootProject.file("LICENSE"))
        from(rootProject.file("engine/desktop/THIRD-PARTY-NOTICE.ffmpeg.txt"))
    }
}

// The Linux AppImage: createDistributable's folder as an AppDir (AppRun, .desktop, icon), squashed (mksquashfs, from
// squashfs-tools) behind the AppImage project's type-2 runtime (fetched once, checked against its digest).
// ./gradlew :desktop:appImage → desktop/build/dist/MediaDroid-linux-x64.AppImage.
val appImageRuntimeUrl = "https://github.com/AppImage/type2-runtime/releases/download/continuous/runtime-x86_64"
val appImageRuntimeSha256 = "617aa77314d57c8b8e4fae9c3f0b35af7b30d5529cb69e98f64f92510ed98a46"

tasks.register("appImage") {
    group = "distribution"
    description = "The Linux AppImage"
    dependsOn("createDistributable")
    val app = layout.buildDirectory.dir("compose/binaries/main/app/MediaDroid")
    val work = layout.buildDirectory.dir("appimage")
    val out = layout.buildDirectory.file("dist/MediaDroid-linux-x64.AppImage")
    outputs.file(out)
    doLast {
        val dir = work.get().asFile.apply { deleteRecursively(); mkdirs() }
        val appDir = File(dir, "AppDir")
        copy {
            from(app)
            into(File(appDir, "usr"))
        }
        copy {
            from(rootProject.file("desktop/linux"))
            from(rootProject.file("desktop/icons/mediadroid.png"))
            into(appDir)
        }
        File(appDir, "AppRun").setExecutable(true)
        File(appDir, "usr/bin/MediaDroid").setExecutable(true)
        val runtime = File(dir, "runtime")
        URI(appImageRuntimeUrl).toURL().openStream().use { input -> runtime.outputStream().use { input.copyTo(it) } }
        val digest = MessageDigest.getInstance("SHA-256").digest(runtime.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(digest == appImageRuntimeSha256) { "the AppImage runtime does not match its digest" }
        val squash = File(dir, "app.squashfs")
        val made = ProcessBuilder("mksquashfs", appDir.path, squash.path, "-root-owned", "-noappend", "-comp", "zstd")
            .inheritIO().start().waitFor()
        check(made == 0) { "mksquashfs failed (squashfs-tools installed?)" }
        val image = out.get().asFile.apply { parentFile.mkdirs() }
        image.outputStream().use { o ->
            runtime.inputStream().use { it.copyTo(o) }
            squash.inputStream().use { it.copyTo(o) }
        }
        image.setExecutable(true)
    }
}
