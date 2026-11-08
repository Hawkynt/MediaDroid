package com.hawkynt.mediadroyd.engine.desktop

import java.io.File
import kotlin.system.exitProcess

/**
 * The native library on the desktop — `libmediadroyd.so` on Linux, `mediadroyd.dll` on Windows, built from `native/`
 * by `./gradlew :native:desktopLibrary` — and its ncnn networks, whose models it reads from files. The packages carry
 * it beside the app's jars, with the OpenMP runtime ncnn's releases are built against (`libgomp.so.1`,
 * `vcomp140.dll`), which is loaded before it.
 */
object NativeNetworks {
    /**
     * The system property naming the folder the library lies in; where unset, the folder of the app's jars, then the
     * JVM's library path.
     */
    const val FOLDER_PROPERTY = "mediadroyd.native"

    /** The library's file once loaded; null where it was not built for this system or is not where it is looked for. */
    val file: File? by lazy { load() }

    /** Whether the library loaded. */
    val loaded: Boolean get() = file != null

    private fun load(): File? {
        val appFolder = runCatching {
            File(NativeNetworks::class.java.protectionDomain.codeSource.location.toURI()).parentFile
        }.getOrNull()
        val windows = System.getProperty("os.name").startsWith("Windows")
        val files = plan(System.getProperty(FOLDER_PROPERTY), appFolder, File(System.getProperty("java.home")), windows)
        return runCatching {
            if (files == null) {
                System.loadLibrary("mediadroyd")
                File(System.mapLibraryName("mediadroyd"))
            } else {
                // What the library needs, each from where it was found: once loaded, the system takes it for the
                // library's own dependency of that name, whichever folder the library itself lies in.
                files.dropLast(1).forEach { runCatching { System.load(it.absolutePath) } }
                files.last().also { System.load(it.absolutePath) }
            }
        }.getOrNull()
    }

    /**
     * The files to load, in order, the library last: from the folder [folder] names, else from [appFolder] where the
     * library lies there; null where neither applies (the JVM's library path is searched then). Before the library,
     * the OpenMP runtime where it lies beside it — and on [windows] first the C++ runtime the JVM in [javaHome] came
     * with, which Windows does not look for in the JVM's folder on the library's behalf.
     */
    internal fun plan(folder: String?, appFolder: File?, javaHome: File, windows: Boolean): List<File>? {
        val name = if (windows) "mediadroyd.dll" else "libmediadroyd.so"
        val dir = folder?.let(::File) ?: appFolder?.takeIf { File(it, name).isFile } ?: return null
        val runtime = if (windows) {
            listOf("vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll").map { File(javaHome, "bin/$it") } +
                File(dir, "vcomp140.dll")
        } else {
            listOf(File(dir, "libgomp.so.1"))
        }
        return runtime.filter { it.isFile } + File(dir, name)
    }

    /**
     * Tells whether the library loads here, and from where — the packages' check: `java -cp <the app's jars>
     * com.hawkynt.mediadroyd.engine.desktop.NativeNetworks`, exit code 1 where it does not.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val from = file
        println(if (from != null) "loaded ${from.path}" else "not loaded")
        if (from == null) exitProcess(1)
    }

    /**
     * The network [name] in [dir] (its `name.param` and `name.bin`, blobs `in0` and `out0`) run on the CPU over
     * [input], [c] planes of [w] × [h] floats; its output the same way, plane after plane, or null where the network
     * does not load or run. Only where [loaded].
     */
    external fun run(dir: String, name: String, input: FloatArray, w: Int, h: Int, c: Int): FloatArray?
}
