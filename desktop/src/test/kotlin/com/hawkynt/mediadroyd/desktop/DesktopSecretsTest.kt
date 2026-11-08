package com.hawkynt.mediadroyd.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

class DesktopSecretsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun roundTrip(secrets: DesktopSecrets) {
        val id = "test-${System.nanoTime()}"
        assertNull(secrets.secretFor(id))
        secrets.store(id, "pa\$\$ wörd'\"")
        assertEquals("pa\$\$ wörd'\"", secrets.secretFor(id))
        secrets.store(id, null)
        assertNull(secrets.secretFor(id))
    }

    @Test
    fun `without a keeper of the system's, the passwords are in a file only the user can read`() {
        val file = File(folder.root, "secrets.properties")
        roundTrip(FileSecrets(file))
        FileSecrets(file).store("nas", "a password")
        assertEquals("a password", FileSecrets(file).secretFor("nas"))
        runCatching { Files.getPosixFilePermissions(file.toPath()) }.getOrNull()?.let { permissions ->
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), permissions)
        }
    }

    @Test
    fun `on Windows, the passwords are sealed by DPAPI and the file holds no password`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val file = File(folder.root, "secrets.properties")
        roundTrip(DpapiSecrets(file))
        DpapiSecrets(file).store("nas", "a password")
        assertFalse(file.readText().contains("a password"))
        assertEquals("a password", DpapiSecrets(file).secretFor("nas"))
    }

    @Test
    fun `where a Secret Service answers, the passwords are kept there`() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows") && SecretServiceSecrets.available())
        roundTrip(SecretServiceSecrets())
    }
}
