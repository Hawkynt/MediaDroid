package com.hawkynt.mediadroid.engine.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Where the native library and what it needs at run time are loaded from (NativeNetworks.plan). */
class NativeLoadPlanTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun folder(name: String, vararg files: String): File =
        temp.newFolder(name).also { dir -> files.forEach { File(dir, it).writeText("") } }

    @Test
    fun `the folder the property names, its OpenMP runtime first`() {
        val dir = folder("lib", "libmediadroid.so", "libgomp.so.1")
        val plan = NativeNetworks.plan(dir.path, null, folder("jre"), windows = false)
        assertEquals(listOf(File(dir, "libgomp.so.1"), File(dir, "libmediadroid.so")), plan)
    }

    @Test
    fun `without the property, the library beside the app's jars - as the packages carry it`() {
        val app = folder("app", "libmediadroid.so", "libgomp.so.1", "engine-desktop.jar")
        val plan = NativeNetworks.plan(null, app, folder("jre"), windows = false)
        assertEquals(listOf(File(app, "libgomp.so.1"), File(app, "libmediadroid.so")), plan)
    }

    @Test
    fun `an OpenMP runtime the folder lacks is left to the system`() {
        val app = folder("app", "libmediadroid.so")
        assertEquals(listOf(File(app, "libmediadroid.so")), NativeNetworks.plan(null, app, folder("jre"), false))
    }

    @Test
    fun `on Windows the C++ runtime the JVM came with, then vcomp140 beside the library`() {
        val app = folder("app", "mediadroid.dll", "vcomp140.dll")
        val jre = folder("jre")
        val bin = File(jre, "bin").apply { mkdirs() }
        listOf("vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll").forEach { File(bin, it).writeText("") }
        val plan = NativeNetworks.plan(null, app, jre, windows = true)
        assertEquals(
            listOf(
                File(bin, "vcruntime140.dll"), File(bin, "vcruntime140_1.dll"), File(bin, "msvcp140.dll"),
                File(app, "vcomp140.dll"), File(app, "mediadroid.dll"),
            ),
            plan,
        )
    }

    @Test
    fun `no library in either folder - the JVM's library path is searched`() {
        val jre = folder("jre")
        assertNull(NativeNetworks.plan(null, folder("app", "engine-desktop.jar"), jre, windows = false))
        assertNull(NativeNetworks.plan(null, null, jre, windows = false))
    }
}
