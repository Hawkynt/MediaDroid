package com.hawkynt.mediadroid.sources.integration

import com.hawkynt.mediadroid.library.LibraryScanner
import com.hawkynt.mediadroid.library.Source
import com.hawkynt.mediadroid.library.SourceKind
import com.hawkynt.mediadroid.library.readTextOrNull
import com.hawkynt.mediadroid.sources.SourceFiles
import com.hawkynt.mediadroid.sources.WebDavFs
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.BeforeClass
import org.junit.Test

/** WebDavFs against a real WebDAV server — WsgiDAV, started for the test. Needs `wsgidav` on the PATH. */
class WebDavIntegrationTest {

    private fun source(user: String = USER) =
        Source("dav", "Cloud", SourceKind.WEBDAV, url = "http://127.0.0.1:$port/", user = user)

    @Test
    fun aWebDavFolderListsReadsAndScans() {
        WebDavFs(source()) { PASSWORD }.use { fs ->
            val movies = fs.root.listFiles()!!.single { it.name == "Movies" }
            val video = movies.listFiles()!!.single { it.name == "Heat (1995).mkv" }
            assertEquals(10, video.length())
            assertEquals("0123456789", video.open().use { String(it.readBytes()) })
            assertEquals("456789", video.open(4).use { String(it.readBytes()) })
            assertEquals("<movie><title>Heat</title></movie>", movies.child("movie.nfo").readTextOrNull())
            assertEquals(10, fs.resolve(video.path)!!.length())
        }
        SourceFiles { PASSWORD }.use { files ->
            assertEquals("Heat", LibraryScanner.scan(files.root(source())!!).movies.single().title)
        }
    }

    @Test
    fun aWrongPasswordIsRefused() {
        WebDavFs(source()) { "not it" }.use { assertNull(it.root.listFiles()) }
    }

    companion object {
        private const val USER = "family"
        private const val PASSWORD = "s3cret-pass"
        private lateinit var dir: File
        private var server: Process? = null
        private var port = 0

        @BeforeClass
        @JvmStatic
        fun startWsgiDav() {
            dir = Files.createTempDirectory("mddav").toFile()
            File(dir, "root/Movies").mkdirs()
            File(dir, "root/Movies/Heat (1995).mkv").writeText("0123456789")
            File(dir, "root/Movies/movie.nfo").writeText("<movie><title>Heat</title></movie>")
            port = ServerSocket(0).use { it.localPort }
            val config = File(dir, "wsgidav.yaml")
            config.writeText(
                """
                host: 127.0.0.1
                port: $port
                provider_mapping:
                  "/": "${File(dir, "root").absolutePath}"
                http_authenticator:
                  domain_controller: null
                  accept_basic: true
                  accept_digest: false
                  default_to_digest: false
                simple_dc:
                  user_mapping:
                    "*":
                      "$USER":
                        password: "$PASSWORD"
                verbose: 1
                """.trimIndent(),
            )
            server = ProcessBuilder("wsgidav", "--config", config.absolutePath)
                .redirectErrorStream(true)
                .redirectOutput(File(dir, "out.txt"))
                .start()
            val until = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < until) {
                if (runCatching { Socket("127.0.0.1", port).close() }.isSuccess) return
                Thread.sleep(100)
            }
            error("wsgidav did not start: " + File(dir, "out.txt").readText())
        }

        @AfterClass
        @JvmStatic
        fun stopWsgiDav() {
            server?.destroy()
            server?.waitFor()
            dir.deleteRecursively()
        }
    }
}
