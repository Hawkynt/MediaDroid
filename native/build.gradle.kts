import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import javax.inject.Inject

// The native tree (CMakeLists.txt): ncnn's releases and libass's sources fetched and checked against their digests, and
// the desktop library built for the building machine. The Android ABIs are built by the app's externalNativeBuild,
// against ncnnAndroid and assSources.
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        // XZ for Java (0BSD): the source releases come as .tar.xz, which Gradle's tarTree does not read.
        classpath("org.tukaani:xz:1.10")
    }
}

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

/**
 * A source release (.tar.xz), downloaded once into the build directory and checked against its digest before it is
 * unpacked into [outputDir] without its top folder.
 */
abstract class SourceRelease : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val archives: ArchiveOperations

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun fetch() {
        val packed = File(temporaryDir, "source.tar.xz")
        URI(url.get()).toURL().openStream().use { input -> packed.outputStream().use { input.copyTo(it) } }
        val digest = MessageDigest.getInstance("SHA-256").digest(packed.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(digest == sha256.get()) { "${url.get()} has the digest $digest, not ${sha256.get()}" }
        val tar = File(temporaryDir, "source.tar")
        org.tukaani.xz.XZInputStream(packed.inputStream().buffered()).use { input ->
            tar.outputStream().use { input.copyTo(it) }
        }
        val target = outputDir.get().asFile
        files.delete { delete(target) }
        files.copy {
            from(archives.tarTree(tar))
            into(target)
            eachFile { path = path.substringAfter('/') }
            includeEmptyDirs = false
        }
        packed.delete()
        tar.delete()
    }
}

// libass (ISC) and what it draws text with — FreeType (FTL), FriBidi (LGPL-2.1-or-later), HarfBuzz (MIT): their
// releases' sources, unpacked a folder each into assRoot, the native tree's ASS_SOURCES on every system.
data class AssSource(val name: String, val url: String, val sha256: String)

val assSources = listOf(
    AssSource(
        "libass", "https://github.com/libass/libass/releases/download/0.17.5/libass-0.17.5.tar.xz",
        "2dca25c0e0c837ddf00b52011b3f82cac1e4ddd3ad018227806b0c2288864acc",
    ),
    AssSource(
        "freetype", "https://download.savannah.gnu.org/releases/freetype/freetype-2.15.0.tar.xz",
        "93cd47eb719c325107f2d3cf087d06299e944c3b48a40b55537302304dea1dd5",
    ),
    AssSource(
        "fribidi", "https://github.com/fribidi/fribidi/releases/download/v1.0.17/fribidi-1.0.17.tar.xz",
        "6949dcde27d41cebad1fd741fcafc36d55a1020d2d872d4a6eb3914caabbada2",
    ),
    AssSource(
        "harfbuzz", "https://github.com/harfbuzz/harfbuzz/releases/download/14.6.0/harfbuzz-14.6.0.tar.xz",
        "d07a007327277708a2a73ae437887cdbaf282937f6d03ca5467723e9099af586",
    ),
)
val assRoot = layout.buildDirectory.dir("ass")
extra["assRoot"] = assRoot
val assFetches = assSources.map { source ->
    tasks.register<SourceRelease>("assSource${source.name.replaceFirstChar { it.uppercase() }}") {
        url.set(source.url)
        sha256.set(source.sha256)
        outputDir.set(assRoot.map { it.dir(source.name) })
    }
}
tasks.register("assSources") {
    group = "build"
    description = "Fetches libass, FreeType, FriBidi and HarfBuzz for the native tree"
    dependsOn(assFetches)
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

// libmediadroyd.so / mediadroyd.dll for this desktop: ./gradlew :native:desktopLibrary → native/build/desktop/lib.
tasks.register("desktopLibrary") {
    group = "build"
    description = "Builds the native library (native/) for this desktop"
    dependsOn(ncnnDesktop, "assSources")
    val sources = layout.projectDirectory
    inputs.files(sources.file("CMakeLists.txt"), sources.dir("src"), sources.dir("jni"), sources.dir("ass"))
    inputs.property("host", host)
    inputs.property("ncnn", ncnnForHost?.archive.orEmpty())
    outputs.dir(desktopLibraryDir)
    val ncnnDir = ncnnDesktop.flatMap { it.outputDir }
    // A CMake folder per ncnn, so a switch between release and source starts afresh.
    val work = layout.buildDirectory.dir("desktop/cmake-${ncnnForHost?.archive}")
    val javaHome = jdk
    val wantedForHost = ncnnForHost
    val ass = assRoot
    doLast {
        val wanted = checkNotNull(wantedForHost) { "ncnn has no release for $host" }
        val ncnn = ncnnDir.get().asFile.let { if (wanted.root.isEmpty()) it else File(it, wanted.root) }
        val cmake = cmake()
        // CMake's own output passed on line by line: inherited, it reaches no log where Gradle runs as a daemon.
        fun run(vararg args: String) {
            val process = ProcessBuilder(cmake, *args).redirectErrorStream(true)
            process.environment()["JAVA_HOME"] = javaHome.get()
            val started = process.start()
            started.outputStream.close()
            started.inputStream.bufferedReader().forEachLine { println(it) }
            check(started.waitFor() == 0) { "cmake ${args.joinToString(" ")} failed" }
        }
        val build = work.get().asFile.path
        // As many compilers as processors: a bare --parallel lets make start one per file, which ran a CI runner out
        // of memory on ncnn's source.
        val jobs = Runtime.getRuntime().availableProcessors().toString()
        run(
            "-S", sources.asFile.path, "-B", build,
            "-DCMAKE_BUILD_TYPE=Release",
            if (wanted.fromSource) "-DNCNN_SOURCE=${ncnn.path}" else "-DNCNN_ROOT=${ncnn.path}",
            "-DMEDIADROYD_OUTPUT=${desktopLibraryDir.get().asFile.path}",
            "-DASS_SOURCES=${ass.get().asFile.path}",
        )
        run("--build", build, "--config", "Release", "--parallel", jobs)
    }
}

// What the desktop packages carry of the native tree: the library and the OpenMP runtime ncnn's releases are built
// against, which neither system can be counted on to have and a jlinked Java runtime does not bring — on Linux
// libgomp.so.1 (GCC's, GPL-3.0 with the GCC Runtime Library Exception), the one the library resolves to here; on
// Windows vcomp140.dll from Visual Studio's redistributable folder (VC\Redist\MSVC, whose files its licence lets an app
// carry unmodified). ./gradlew :native:desktopRuntime → native/build/desktop/runtime, where :desktop's packages take it
// from. The C library of the machine that builds it sets the oldest glibc it runs on: the published AppImage's is built
// on Ubuntu 22.04 (glibc 2.35, as ncnn's release).
val desktopRuntimeDir = layout.buildDirectory.dir("desktop/runtime")

/** The text [command] writes, or null where it cannot run or fails. */
fun outputOf(vararg command: String): String? = runCatching {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    process.outputStream.close()
    val text = process.inputStream.bufferedReader().readText()
    text.takeIf { process.waitFor() == 0 }
}.getOrNull()

tasks.register("desktopRuntime") {
    group = "build"
    description = "The native library for this desktop with the runtime it needs, as the packages carry it"
    dependsOn("desktopLibrary")
    inputs.dir(desktopLibraryDir)
    outputs.dir(desktopRuntimeDir)
    val library = desktopLibraryDir
    val out = desktopRuntimeDir
    val windows = hostSystem == "windows"
    doLast {
        val target = out.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        val built = library.get().asFile.listFiles().orEmpty().filter { it.isFile }
        check(built.isNotEmpty()) { "the native library was not built" }
        built.forEach { it.copyTo(File(target, it.name)) }
        val name = if (windows) "vcomp140.dll" else "libgomp.so.1"
        val openMp = if (windows) {
            val vswhere = File(
                System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)",
                "Microsoft Visual Studio\\Installer\\vswhere.exe",
            )
            outputOf(
                vswhere.path, "-latest", "-products", "*",
                "-find", "VC\\Redist\\MSVC\\**\\x64\\Microsoft.VC*.OpenMP\\vcomp140.dll",
            ).orEmpty().lines().map { it.trim() }
                .filter { it.isNotEmpty() && "onecore" !in it.lowercase() && "debug" !in it.lowercase() }
                .maxOrNull()?.let(::File)
        } else {
            // Where the system's loader finds it for the library: the GCC runtime it was linked against.
            val linked = built.first { it.name == "libmediadroyd.so" }
            outputOf("ldd", linked.path).orEmpty().lines()
                .firstNotNullOfOrNull { Regex("""^\s*libgomp\.so\.1 => (\S+)""").find(it)?.groupValues?.get(1) }
                ?.let { File(it).canonicalFile }
        }
        checkNotNull(openMp?.takeIf { it.isFile }) { "$name, the OpenMP runtime the native library needs, not found" }
            .copyTo(File(target, name))
        println("native runtime: ${target.list()?.sorted()?.joinToString()} ($name from $openMp)")
    }
}
