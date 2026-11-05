import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import javax.inject.Inject

// The native tree (CMakeLists.txt): ncnn's releases fetched and checked against their digests, and the desktop library
// built for the building machine. The Android ABIs are built by the app's externalNativeBuild, against ncnnAndroid.
plugins {
    base
    `java-base`
}

// ncnn (Tencent, BSD-3-Clause): the release every system builds against.
val ncnnVersion = "20261020"
val ncnnRelease = "https://github.com/Tencent/ncnn/releases/download/$ncnnVersion/ncnn-$ncnnVersion"

/**
 * One of ncnn's release archives, downloaded once into the build directory and checked against its digest before it
 * is unpacked — the archive's top folder dropped where it has one (the releases do, the full source does not).
 */
abstract class NcnnArchive : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:Input
    abstract val topFolder: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        topFolder.convention(true)
    }

    @get:Inject
    abstract val archives: ArchiveOperations

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun fetch() {
        val zip = File(temporaryDir, "ncnn.zip")
        URI(url.get()).toURL().openStream().use { input -> zip.outputStream().use { input.copyTo(it) } }
        val digest = MessageDigest.getInstance("SHA-256").digest(zip.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(digest == sha256.get()) { "ncnn's archive has the digest $digest, not ${sha256.get()}" }
        val target = outputDir.get().asFile
        files.delete { delete(target) }
        files.copy {
            from(archives.zipTree(zip))
            into(target)
            if (topFolder.get()) eachFile { path = path.substringAfter('/') }
            includeEmptyDirs = false
        }
        zip.delete()
    }
}

// The Android release (a folder per ABI), for the app's CMake build: NCNN_ROOT there, ncnnAndroidRoot to the app.
val ncnnAndroidRoot = layout.buildDirectory.dir("ncnn/$ncnnVersion/android")
extra["ncnnAndroidRoot"] = ncnnAndroidRoot
tasks.register<NcnnArchive>("ncnnAndroid") {
    url.set("$ncnnRelease-android-vulkan.zip")
    sha256.set("6e0c5f9b397faf32b923de2ef6210af9bf375851869dceff06a8005331981086")
    outputDir.set(ncnnAndroidRoot)
}

// The desktop this build runs on, as the library is named for it: linux-x64, linux-arm64 or windows-x64.
val hostSystem = if (System.getProperty("os.name").startsWith("Windows")) "windows" else "linux"
val hostArch = when (System.getProperty("os.arch")) {
    "amd64", "x86_64" -> "x64"
    "aarch64", "arm64" -> "arm64"
    else -> System.getProperty("os.arch")
}
val host = "$hostSystem-$hostArch"

// ncnn for the desktop: its Ubuntu 22.04 release on Linux x64 (the oldest C library it is built for), its Visual Studio
// 2022 release on Windows (x64 inside); Linux arm64 has no release, so it is built from ncnn's full source — as any
// desktop's is with -PncnnFromSource.
data class NcnnDesktop(val archive: String, val sha256: String, val root: String = "", val fromSource: Boolean = false)

val ncnnSource = NcnnDesktop(
    "full-source", "699d9acc67489eee0993a551ca1ebc247bbf0f4e33babe1dec810e6a9a52b988", fromSource = true,
)
val ncnnForHost = if (providers.gradleProperty("ncnnFromSource").isPresent) ncnnSource else mapOf(
    "linux-x64" to NcnnDesktop("ubuntu-2204", "0e364326f6b6e225f1d1886e2127898c9d3383811a894e2680d156f589fe2641"),
    "windows-x64" to NcnnDesktop(
        "windows-vs2022", "ef9fe4463e5db0f41484ad25e91686e709b91e400858639aed631c280dfa91d4", "x64",
    ),
    "linux-arm64" to ncnnSource,
)[host]

val ncnnDesktop = tasks.register<NcnnArchive>("ncnnDesktop") {
    val wanted = ncnnForHost
    onlyIf("ncnn has a release or source build for $host") { wanted != null }
    url.set(if (wanted != null) "$ncnnRelease-${wanted.archive}.zip" else "")
    sha256.set(wanted?.sha256.orEmpty())
    topFolder.set(wanted?.fromSource != true)
    outputDir.set(layout.buildDirectory.dir("ncnn/$ncnnVersion/${wanted?.archive ?: host}"))
}

/** The folder the desktop library is written to: what :engine:desktop's native test loads it from. */
val desktopLibraryDir = layout.buildDirectory.dir("desktop/lib")

val jdk = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
    .map { it.metadata.installationPath.asFile.absolutePath }

// CMake from the path, else the one the Android SDK installs (the version the app's build asks for).
fun cmake(): String {
    val onPath = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .map { File(it, if (hostSystem == "windows") "cmake.exe" else "cmake") }
        .firstOrNull { it.canExecute() }
    if (onPath != null) return onPath.path
    val properties = Properties()
    rootProject.file("local.properties").takeIf { it.exists() }?.reader()?.use { properties.load(it) }
    val sdk = properties.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    val bundled = sdk?.let { File(it, "cmake/3.22.1/bin/cmake") }
    check(bundled != null && bundled.canExecute()) { "CMake not found: on the path, or in the Android SDK (cmake 3.22.1)" }
    return bundled.path
}

// libmediadroid.so / mediadroid.dll for this desktop: ./gradlew :native:desktopLibrary → native/build/desktop/lib.
tasks.register("desktopLibrary") {
    group = "build"
    description = "Builds the native library (native/) for this desktop"
    dependsOn(ncnnDesktop)
    val sources = layout.projectDirectory
    inputs.files(sources.file("CMakeLists.txt"), sources.dir("src"), sources.dir("jni"))
    inputs.property("host", host)
    inputs.property("ncnn", ncnnForHost?.archive.orEmpty())
    outputs.dir(desktopLibraryDir)
    val ncnnDir = ncnnDesktop.flatMap { it.outputDir }
    // A CMake folder per ncnn, so a switch between release and source starts afresh.
    val work = layout.buildDirectory.dir("desktop/cmake-${ncnnForHost?.archive}")
    val javaHome = jdk
    val wantedForHost = ncnnForHost
    doLast {
        val wanted = checkNotNull(wantedForHost) { "ncnn has no release for $host" }
        val ncnn = ncnnDir.get().asFile.let { if (wanted.root.isEmpty()) it else File(it, wanted.root) }
        val cmake = cmake()
        fun run(vararg args: String) {
            val process = ProcessBuilder(cmake, *args).inheritIO()
            process.environment()["JAVA_HOME"] = javaHome.get()
            check(process.start().waitFor() == 0) { "cmake ${args.joinToString(" ")} failed" }
        }
        val build = work.get().asFile.path
        // As many compilers as processors: a bare --parallel lets make start one per file, which ran a CI runner out
        // of memory on ncnn's source.
        val jobs = Runtime.getRuntime().availableProcessors().toString()
        run(
            "-S", sources.asFile.path, "-B", build,
            "-DCMAKE_BUILD_TYPE=Release",
            if (wanted.fromSource) "-DNCNN_SOURCE=${ncnn.path}" else "-DNCNN_ROOT=${ncnn.path}",
            "-DMEDIADROID_OUTPUT=${desktopLibraryDir.get().asFile.path}",
        )
        run("--build", build, "--config", "Release", "--parallel", jobs)
    }
}
