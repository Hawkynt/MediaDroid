package com.hawkynt.mediadroyd.desktop

import com.hawkynt.mediadroyd.library.Source
import com.hawkynt.mediadroyd.library.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DesktopLibraryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun movie(root: File, title: String): File =
        File(root, "$title/$title.mkv").apply { parentFile.mkdirs(); writeBytes(ByteArray(16)) }

    @Test
    fun `the sources a household added are kept, their passwords apart from them`() {
        val data = folder.newFolder("data")
        val store = DesktopStore(data)
        val secrets = FileSecrets(File(data, "secrets.properties"))
        val nas = Source("nas", "NAS", SourceKind.SMB, host = "nas", path = "/media", user = "me")
        DesktopLibrary(store, secrets).add(nas, password = "secret")
        val again = DesktopLibrary(DesktopStore(data), secrets)
        assertEquals(listOf(nas), again.networkSources)
        assertEquals("secret", secrets.secretFor("nas"))
        assertEquals(false, File(data, "state.json").readText().contains("secret"))
        again.remove(nas)
        assertEquals(emptyList<Source>(), DesktopLibrary(DesktopStore(data), secrets).networkSources)
        assertNull(secrets.secretFor("nas"))
    }

    @Test
    fun `the library is every source's movies together, and any location reads back as its file`() {
        val data = folder.newFolder("data")
        val local = folder.newFolder("local")
        val other = folder.newFolder("other")
        val heat = movie(local, "Heat")
        movie(other, "Ronin")
        val store = DesktopStore(data).apply { libraryFolder = local.path }
        val library = DesktopLibrary(store, FileSecrets(File(data, "secrets.properties")))
        library.add(Source.local("other", "Other disk", other.path), password = null)
        assertEquals(listOf("Heat", "Ronin"), library.scan().movies.map { it.title })
        assertEquals(heat.path, library.file(heat.path).path)
        assertNull(library.remoteFile(heat.path))
    }

    @Test
    fun `trying a source says nothing when it can be read, and what went wrong when not`() {
        val data = folder.newFolder("data")
        val library = DesktopLibrary(DesktopStore(data), FileSecrets(File(data, "secrets.properties")))
        assertNull(library.check(Source.local("disk", "Disk", folder.newFolder("disk").path), null))
        val nowhere = Source("x", "Nowhere", SourceKind.SFTP, host = "127.0.0.1", port = 1, path = "/")
        assertEquals(true, library.check(nowhere, "pw") != null)
    }
}
