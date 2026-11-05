package com.hawkynt.mediadroid.engine.desktop

import java.io.File

/**
 * The native library on the desktop — `libmediadroid.so` on Linux, `mediadroid.dll` on Windows, built from `native/`
 * by `./gradlew :native:desktopLibrary` — and its ncnn networks, whose models it reads from files.
 */
object NativeNetworks {
    /** The system property naming the folder the library lies in; where unset, the JVM's library path is searched. */
    const val FOLDER_PROPERTY = "mediadroid.native"

    /** Whether the library loaded; false where it was not built for this system or is not where it is looked for. */
    val loaded: Boolean by lazy {
        runCatching {
            val folder = System.getProperty(FOLDER_PROPERTY)
            if (folder != null) {
                System.load(File(folder, System.mapLibraryName("mediadroid")).absolutePath)
            } else {
                System.loadLibrary("mediadroid")
            }
        }.isSuccess
    }

    /**
     * The network [name] in [dir] (its `name.param` and `name.bin`, blobs `in0` and `out0`) run on the CPU over
     * [input], [c] planes of [w] × [h] floats; its output the same way, plane after plane, or null where the network
     * does not load or run. Only where [loaded].
     */
    external fun run(dir: String, name: String, input: FloatArray, w: Int, h: Int, c: Int): FloatArray?
}
