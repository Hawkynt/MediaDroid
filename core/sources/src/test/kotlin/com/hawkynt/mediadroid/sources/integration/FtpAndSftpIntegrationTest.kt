package com.hawkynt.mediadroid.sources.integration

import com.hawkynt.mediadroid.library.LibraryScanner
import com.hawkynt.mediadroid.library.Source
import com.hawkynt.mediadroid.library.SourceKind
import com.hawkynt.mediadroid.library.readTextOrNull
import com.hawkynt.mediadroid.sources.FtpFs
import com.hawkynt.mediadroid.sources.SftpFs
import com.hawkynt.mediadroid.sources.Secrets
import com.hawkynt.mediadroid.sources.SourceFiles
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.util.Base64
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * FtpFs and SftpFs against real servers run in the test: Apache FtpServer and Apache MINA SSHD,
 * serving the same small library.
 */
class FtpAndSftpIntegrationTest {

    @Test
    fun anonymousFtpListsReadsAndScans() {
        val source = Source("ftp", "FTP", SourceKind.FTP, host = "127.0.0.1", port = ftpPort, path = "/")
        FtpFs(source) { null }.use { fs ->
            val movies = fs.root.listFiles()!!.single { it.name == "Movies" }
            assertTrue(movies.isDirectory)
            val video = movies.listFiles()!!.single { it.name == "Heat (1995).mkv" }
            assertEquals(10, video.length())
            assertEquals("0123456789", video.open().use { String(it.readBytes()) })
            assertEquals("6789", video.open(6).use { String(it.readBytes()) })
            assertEquals("<movie><title>Heat</title></movie>", movies.child("movie.nfo").readTextOrNull())
            assertEquals(10, fs.resolve("ftp://127.0.0.1:$ftpPort/Movies/Heat (1995).mkv")!!.length())
            // The root itself is found too, as the Files browser opens a source at its root.
            assertEquals(2, fs.resolve("ftp://127.0.0.1:$ftpPort")!!.listFiles()!!.size)
        }
        SourceFiles(Secrets { null }).use { files ->
            assertEquals("Heat", LibraryScanner.scan(files.root(source)!!).movies.single().title)
        }
    }

    @Test
    fun ftpWantsTheRightPassword() {
        val source = Source("ftpu", "FTP", SourceKind.FTP, host = "127.0.0.1", port = ftpPort, path = "/", user = USER)
        FtpFs(source) { PASSWORD }.use { assertEquals(2, it.root.listFiles()!!.size) }
        FtpFs(source) { "not it" }.use { assertNull(it.root.listFiles()) }
    }

    @Test
    fun sftpSignsInWithPasswordAndTrustsTheHostKeyOnFirstUse() {
        val source = sftp("sftp", "/")
        val fingerprint = SftpFs(source) { PASSWORD }.use { fs ->
            val video = fs.root.child("Movies").listFiles()!!.single { it.name == "Heat (1995).mkv" }
            assertEquals(10, video.length())
            assertEquals("3456789", video.open(3).use { String(it.readBytes()) })
            assertEquals("Heat", LibraryScanner.scan(fs.root).movies.single().title)
            fs.hostKey
        }
        assertTrue(fingerprint.isNotEmpty())
        // The saved key keeps working; a server showing another one is refused.
        SftpFs(source.copy(hostKey = fingerprint)) { PASSWORD }.use { assertEquals(2, it.root.listFiles()!!.size) }
        val other = "00:" + fingerprint.substringAfter(':')
        assertNotEquals(fingerprint, other)
        SftpFs(source.copy(hostKey = other)) { PASSWORD }.use { assertNull(it.root.listFiles()) }
        SftpFs(source) { "not it" }.use { assertNull(it.root.listFiles()) }
    }

    @Test
    fun sftpSignsInWithAPrivateKey() {
        val source = sftp("key", "/Movies")
        SftpFs(source) { privateKeyPem }.use { fs ->
            assertEquals(listOf("Heat (1995).mkv", "movie.nfo"), fs.root.listFiles()!!.map { it.name }.sorted())
        }
    }

    private fun sftp(id: String, path: String) =
        Source(id, "SFTP", SourceKind.SFTP, host = "127.0.0.1", port = sshPort, path = path, user = USER)

    companion object {
        private const val USER = "family"
        private const val PASSWORD = "s3cret-pass"
        private lateinit var dir: File
        private lateinit var ftp: FtpServer
        private lateinit var ssh: SshServer
        private var ftpPort = 0
        private var sshPort = 0
        private lateinit var privateKeyPem: String

        private fun freePort(): Int = ServerSocket(0).use { it.localPort }

        @BeforeClass
        @JvmStatic
        fun startServers() {
            dir = Files.createTempDirectory("mdftp").toFile()
            val root = File(dir, "root")
            File(root, "Movies").mkdirs()
            File(root, "Movies/Heat (1995).mkv").writeText("0123456789")
            File(root, "Movies/movie.nfo").writeText("<movie><title>Heat</title></movie>")
            File(root, "notes.txt").writeText("hello")

            val users = PropertiesUserManagerFactory().createUserManager()
            users.save(BaseUser().apply { name = "anonymous"; homeDirectory = root.absolutePath })
            users.save(BaseUser().apply { name = USER; password = PASSWORD; homeDirectory = root.absolutePath })
            ftpPort = freePort()
            ftp = FtpServerFactory().apply {
                val listener = ListenerFactory().apply {
                    port = ftpPort
                    serverAddress = "127.0.0.1"
                }
                addListener("default", listener.createListener())
                userManager = users
            }.createServer()
            ftp.start()

            val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            privateKeyPem = "-----BEGIN PRIVATE KEY-----\n" +
                Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keys.private.encoded) +
                "\n-----END PRIVATE KEY-----\n"
            sshPort = freePort()
            ssh = SshServer.setUpDefaultServer().apply {
                host = "127.0.0.1"
                port = sshPort
                keyPairProvider = SimpleGeneratorHostKeyProvider(File(dir, "hostkey.ser").toPath())
                setPasswordAuthenticator { user, password, _ -> user == USER && password == PASSWORD }
                setPublickeyAuthenticator { user, key, _ -> user == USER && key == keys.public }
                subsystemFactories = listOf(SftpSubsystemFactory())
                fileSystemFactory = VirtualFileSystemFactory(root.toPath())
            }
            ssh.start()
        }

        @AfterClass
        @JvmStatic
        fun stopServers() {
            ftp.stop()
            ssh.stop(true)
            dir.deleteRecursively()
        }
    }
}
