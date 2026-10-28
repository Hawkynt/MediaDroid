package com.hawkynt.mediadroid.sources.integration

import com.hawkynt.mediadroid.library.Source
import com.hawkynt.mediadroid.library.SourceKind
import com.hawkynt.mediadroid.library.readTextOrNull
import com.hawkynt.mediadroid.sources.SmbFs
import java.io.File
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * SmbFs against a real Samba server: an unprivileged `smbd` on a high port, with a guest share and a
 * share for a signed-in user. Needs `smbd` and `pdbedit` on the PATH.
 */
class SmbFsIntegrationTest {

    @Test
    fun aGuestShareListsReadsAndFindsItsFilesBack() {
        SmbFs(source("guest", path = "/media"), { null }).use { fs ->
            val root = fs.root
            assertEquals("smb://127.0.0.1:$PORT/media", root.path)
            val movies = root.listFiles()!!.single { it.name == "Movies" }
            assertTrue(movies.isDirectory)
            val video = movies.listFiles()!!.single { it.name == "Heat (1995).mkv" }
            assertEquals(10, video.length())
            assertTrue(video.lastModified() > 0)
            assertEquals("0123456789", video.open().use { String(it.readBytes()) })
            assertEquals("789", video.open(7).use { String(it.readBytes()) })
            assertEquals("<movie><title>Heat</title></movie>", movies.child("movie.nfo").readTextOrNull())

            val found = fs.resolve("smb://127.0.0.1:$PORT/media/Movies/Heat (1995).mkv")!!
            assertEquals(10, found.length())
            assertEquals("Movies", found.parentFile!!.name)
            assertNull(fs.resolve("smb://127.0.0.1:$PORT/other/x.mkv"))
        }
    }

    @Test
    fun aSourceCanStartDeeperInTheShare() {
        SmbFs(source("deep", path = "/media/Movies"), { null }).use { fs ->
            assertEquals(listOf("Heat (1995).mkv", "movie.nfo"), fs.root.listFiles()!!.map { it.name }.sorted())
        }
    }

    @Test
    fun aPrivateShareWantsTheRightPassword() {
        val user = System.getProperty("user.name")
        SmbFs(source("private", path = "/family", user = user), { PASSWORD }).use { fs ->
            assertEquals(listOf("secret.txt"), fs.root.listFiles()!!.map { it.name })
        }
        SmbFs(source("wrong", path = "/family", user = user), { "not it" }).use { fs ->
            assertNull(fs.root.listFiles())
        }
    }

    private fun source(id: String, path: String, user: String = "") =
        Source(id, id, SourceKind.SMB, host = "127.0.0.1", port = PORT, path = path, user = user)

    companion object {
        private const val PORT = 44455
        private const val PASSWORD = "s3cret-pass"
        private lateinit var dir: File
        private var server: Process? = null

        @BeforeClass
        @JvmStatic
        fun startSamba() {
            // Samba's sockets live under its directories, and a Unix socket path must stay short.
            dir = Files.createTempDirectory(Paths.get("/tmp"), "mds").toFile()
            listOf("private", "lock", "state", "cache", "pid", "ncalrpc", "media/Movies", "family")
                .forEach { File(dir, it).mkdirs() }
            File(dir, "media/Movies/Heat (1995).mkv").writeText("0123456789")
            File(dir, "media/Movies/movie.nfo").writeText("<movie><title>Heat</title></movie>")
            File(dir, "family/secret.txt").writeText("only for us")
            val user = System.getProperty("user.name")
            val conf = File(dir, "smb.conf")
            conf.writeText(
                """
                [global]
                  smb ports = $PORT
                  private dir = $dir/private
                  lock directory = $dir/lock
                  state directory = $dir/state
                  cache directory = $dir/cache
                  pid directory = $dir/pid
                  ncalrpc dir = $dir/ncalrpc
                  log file = $dir/log.smbd
                  server role = standalone server
                  map to guest = Bad User
                  guest account = $user
                  interfaces = lo
                  bind interfaces only = yes
                  load printers = no
                  disable spoolss = yes
                  server min protocol = SMB2_02
                [media]
                  path = $dir/media
                  read only = yes
                  guest ok = yes
                  guest only = yes
                [family]
                  path = $dir/family
                  read only = yes
                  valid users = $user
                """.trimIndent(),
            )
            val add = ProcessBuilder("pdbedit", "--configfile=$conf", "-a", "-u", user, "-t")
                .redirectErrorStream(true)
                .start()
            add.outputStream.bufferedWriter().use { it.write("$PASSWORD\n$PASSWORD\n") }
            check(add.waitFor() == 0) { "pdbedit: " + add.inputStream.bufferedReader().readText() }
            // In a session of its own: smbd signals its process group, which must not be the test's.
            server = ProcessBuilder(
                "setsid", "smbd", "--foreground", "--no-process-group", "--configfile=$conf", "--debug-stdout", "-d0",
            ).redirectErrorStream(true).redirectOutput(File(dir, "out.txt")).start()
            val until = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < until) {
                if (runCatching { Socket("127.0.0.1", PORT).close() }.isSuccess) return
                Thread.sleep(100)
            }
            error("smbd did not start: " + File(dir, "out.txt").readText())
        }

        @AfterClass
        @JvmStatic
        fun stopSamba() {
            server?.destroy()
            server?.waitFor()
            // smbd's own children are not ours to wait for; its pid file names the parent.
            File(dir, "pid").listFiles()?.forEach { pid ->
                pid.readText().trim().toLongOrNull()?.let { ProcessHandle.of(it).ifPresent { p -> p.destroy() } }
            }
            dir.deleteRecursively()
        }
    }
}
