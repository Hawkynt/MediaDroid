import java.net.URI
import java.security.MessageDigest
import java.time.LocalDate
import javax.inject.Inject

// The desktop app for Windows and Linux: a Compose Desktop window over the shared cores and the desktop engine
// (README "Platform support"). `./gradlew :desktop:run` starts it; `-PfullFfmpeg` plays with the GPL FFmpeg build on
// the household's own machines.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// The build's date: the packages' version, as the Android app's versionName (app/build.gradle.kts).
val buildDay: LocalDate = LocalDate.now()
val buildVersion = "%04d.%02d.%02d".format(buildDay.year, buildDay.monthValue, buildDay.dayOfMonth)

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core:library"))
    implementation(project(":core:nfo"))
    implementation(project(":core:imaging"))
    implementation(project(":core:player"))
    implementation(project(":core:sources"))
    implementation(project(":engine:desktop"))
    // The settings file, settings.json (DesktopStore).
    implementation(libs.serialization.json)
    // SMB servers announced on the local network (mDNS), SmbDiscovery.
    implementation(libs.jmdns)
    // The session bus on Linux (MPRIS media keys, the screensaver's inhibit): dbus-java over the JDK's own Unix
    // sockets.
    implementation(libs.dbus.java.core)
    implementation(libs.dbus.java.unixsocket)
    // Windows' system media controls (SMTC), WinRT called through JNA.
    implementation(libs.jna)
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
            // The build's date (MSI wants each part below 256: the year as its last two digits there).
            packageVersion = "%d.%d.%d".format(buildDay.year % 100, buildDay.monthValue, buildDay.dayOfMonth)
            description = "Plays the movies and shows already organised in your folders"
            vendor = "Hawkynt"
            licenseFile.set(rootProject.file("LICENSE"))
            // jdk.security.auth: the user id dbus-java authenticates with on the session bus.
            modules(
                "java.desktop", "java.naming", "java.sql", "jdk.unsupported", "java.management", "jdk.security.auth",
            )
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


// Copies at execution time, without reaching for the project there.
interface FileOps {
    @get:Inject val fs: FileSystemOperations
}
val fileOps = objects.newInstance<FileOps>().fs

// A file fetched once into build/download and checked against its SHA-256; fetched again whenever it does not match.
fun fetch(name: String, file: String, url: String, sha256: String) = tasks.register(name) {
    val out = layout.buildDirectory.file("download/$file")
    inputs.property("sha256", sha256)
    outputs.file(out)
    doLast {
        val target = out.get().asFile
        target.parentFile.mkdirs()
        fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes())
            .joinToString("") { "%02x".format(it) }
        if (!target.exists() || sha(target) != sha256) {
            URI(url).toURL().openStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        check(sha(target) == sha256) { "$file does not match its digest" }
    }
}

// What every package owes: MediaDroid's licence and the notices of everything inside it — the desktop's libraries and
// runtimes, FFmpeg, the picture stage's shaders, the code converted into the cores — side by side at its top.
val notices = tasks.register<Sync>("notices") {
    from(rootProject.file("LICENSE"))
    from("THIRD-PARTY-NOTICE.desktop.txt")
    from(rootProject.file("engine/desktop/THIRD-PARTY-NOTICE.ffmpeg.txt"))
    from(rootProject.file("app")) {
        listOf("ACNet", "Anime4K", "FidelityFX-FSR", "NVIDIA-Image-Scaling", "Snapdragon-GSR", "hash-without-sine")
            .forEach { include("THIRD-PARTY-NOTICE.$it.txt") }
    }
    listOf("imaging", "library", "player", "sources").forEach { core ->
        from(rootProject.file("core/$core/THIRD_PARTY_NOTICES.md")) { rename { "THIRD-PARTY-NOTICE.core-$core.md" } }
    }
    into(layout.buildDirectory.dir("notices"))
}

// createDistributable's folder is what the MSI, the Windows-built zip and the AppImage are made from: the notices go
// in there (the task comes once the Compose plugin has read the configuration, hence matching rather than named).
val distributable = layout.buildDirectory.dir("compose/binaries/main/app/MediaDroid")
tasks.matching { it.name == "createDistributable" }.configureEach {
    inputs.files(notices)
    doLast {
        fileOps.copy {
            from(notices)
            into(distributable)
        }
    }
}

// The portable Windows zip (unpack anywhere and run it; settings stay beside it): the app built for Windows
// (-PdesktopTarget=windows), a Java runtime, a launcher and the `portable` marker.
// ./gradlew :desktop:windowsZip -PdesktopTarget=windows → desktop/build/dist/.
// The MSI is built by jpackage on Windows itself (packageMsi); this zip is made on any system.
val windowsJre = "OpenJDK21U-jre_x64_windows_hotspot_21.0.13_9.zip"
val windowsJreFetch = fetch(
    "windowsJreFetch",
    windowsJre,
    "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/$windowsJre",
    "2332c0b4cff6bed722da3567c91d5e47568bcba863fa4945d0a8d6fc76158628",
)

// The JRE unpacked once, its top folder (jdk-…-jre) dropped.
val windowsJreUnpack = tasks.register<Sync>("windowsJreUnpack") {
    dependsOn(windowsJreFetch)
    from(windowsJreFetch.map { zipTree(it.outputs.files.singleFile) })
    eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray()) }
    includeEmptyDirs = false
    into(layout.buildDirectory.dir("windows-jre"))
}

// Built on Windows itself, the zip is createDistributable's folder — the native MediaDroid.exe launcher and its jlinked
// runtime — with the marker; built anywhere else, it is the jars, Temurin's JRE and MediaDroid.bat.
val onWindows = System.getProperty("os.name").startsWith("Windows")

tasks.register<Zip>("windowsZip") {
    group = "distribution"
    description = "The portable Windows zip: app, Java runtime, launcher"
    archiveFileName.set("MediaDroid-windows-x64.zip")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
    into("MediaDroid") {
        if (onWindows) {
            dependsOn("createDistributable")
            from(distributable)
            from(rootProject.file("desktop/windows/portable"))
        } else {
            dependsOn(windowsJreUnpack, tasks.named("jar"))
            into("runtime") { from(windowsJreUnpack) }
            into("lib") {
                from(tasks.named("jar"))
                from(configurations.named("runtimeClasspath"))
            }
            from(rootProject.file("desktop/windows"))
        }
        from(notices)
    }
}

// The Linux AppImage: createDistributable's folder as an AppDir (AppRun, .desktop, icon), packed by appimagetool
// (https://github.com/AppImage/appimagetool, MIT) behind the AppImage project's type-2 runtime (MIT) — both from
// tagged releases, fetched once and checked against their digests, so a new upstream build cannot slip in. The
// AppImage carries the build's date as its version (X-AppImage-Version), as the Android app's versionName.
// ./gradlew :desktop:appImage → desktop/build/dist/MediaDroid-linux-x64.AppImage.
val appImageTool = fetch(
    "appImageToolFetch",
    "appimagetool-1.9.1-x86_64.AppImage",
    "https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-x86_64.AppImage",
    "ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0",
)
val appImageRuntime = fetch(
    "appImageRuntimeFetch",
    "appimage-runtime-20251108-x86_64",
    "https://github.com/AppImage/type2-runtime/releases/download/20251108/runtime-x86_64",
    "2fca8b443c92510f1483a883f60061ad09b46b978b2631c807cd873a47ec260d",
)

tasks.register("appImage") {
    group = "distribution"
    description = "The Linux AppImage"
    dependsOn("createDistributable", appImageTool, appImageRuntime)
    val tool = appImageTool.map { it.outputs.files.singleFile }
    val runtime = appImageRuntime.map { it.outputs.files.singleFile }
    inputs.files(tool, runtime, rootProject.file("desktop/linux"), rootProject.file("desktop/icons/mediadroid.png"))
    inputs.dir(distributable)
    inputs.property("version", buildVersion)
    val work = layout.buildDirectory.dir("appimage")
    val out = layout.buildDirectory.file("dist/MediaDroid-linux-x64.AppImage")
    outputs.file(out)
    val version = buildVersion
    doLast {
        val appDir = File(work.get().asFile.apply { deleteRecursively() }, "AppDir")
        fileOps.copy {
            from(distributable)
            into(File(appDir, "usr"))
        }
        fileOps.copy {
            from(rootProject.file("desktop/linux"))
            from(rootProject.file("desktop/icons/mediadroid.png"))
            into(appDir)
        }
        File(appDir, "AppRun").setExecutable(true)
        File(appDir, "usr/bin/MediaDroid").setExecutable(true)
        val image = out.get().asFile.apply { parentFile.mkdirs(); delete() }
        val toolFile = tool.get().apply { setExecutable(true) }
        // Unpacked and run in place (no FUSE needed, as on CI runners); no AppStream file to check; no update
        // information guessed from the CI's environment.
        val made = ProcessBuilder(
            toolFile.path, "--no-appstream", "--runtime-file", runtime.get().path, appDir.path, image.path,
        ).apply {
            environment()["APPIMAGE_EXTRACT_AND_RUN"] = "1"
            environment()["ARCH"] = "x86_64"
            environment()["VERSION"] = version
            listOf("GITHUB_ACTIONS", "GITHUB_REPOSITORY", "GITLAB_CI", "TRAVIS").forEach { environment().remove(it) }
        }.inheritIO().start().waitFor()
        check(made == 0) { "appimagetool failed" }
    }
}
