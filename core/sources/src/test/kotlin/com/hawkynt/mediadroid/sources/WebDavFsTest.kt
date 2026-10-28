package com.hawkynt.mediadroid.sources

import com.hawkynt.mediadroid.library.Source
import com.hawkynt.mediadroid.library.SourceKind
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The PROPFIND answer as RFC 4918 writes it, with an encoded name and a folder, read back. */
class WebDavFsTest {

    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private val multistatus = """
        <?xml version="1.0" encoding="utf-8"?>
        <D:multistatus xmlns:D="DAV:">
          <D:response><D:href>/dav/Movies/</D:href><D:propstat><D:prop>
            <D:resourcetype><D:collection/></D:resourcetype>
          </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
          <D:response><D:href>/dav/Movies/Heat%20(1995).mkv</D:href><D:propstat><D:prop>
            <D:resourcetype/><D:getcontentlength>10</D:getcontentlength>
            <D:getlastmodified>Wed, 02 Jan 2019 10:04:20 GMT</D:getlastmodified>
          </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
          <D:response><D:href>/dav/Movies/Extras/</D:href><D:propstat><D:prop>
            <D:resourcetype><D:collection/></D:resourcetype>
          </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
        </D:multistatus>
    """.trimIndent()

    @Test
    fun aFolderListsItsFilesAndFoldersButNotItself() {
        server.enqueue(MockResponse.Builder().code(207).body(multistatus).build())
        val source = Source("dav", "Cloud", SourceKind.WEBDAV, url = server.url("/dav/Movies").toString(), user = "me")
        WebDavFs(source) { "pw" }.use { fs ->
            val entries = fs.root.listFiles()!!.associateBy { it.name }
            assertEquals(setOf("Heat (1995).mkv", "Extras"), entries.keys)
            val heat = entries.getValue("Heat (1995).mkv")
            assertEquals(10, heat.length())
            assertEquals(1546423460000L, heat.lastModified())
            assertEquals(server.url("/dav/Movies/Heat%20(1995).mkv").toString(), heat.path)
            assertTrue(entries.getValue("Extras").isDirectory)
        }
        val request = server.takeRequest()
        assertEquals("PROPFIND", request.method)
        assertEquals("1", request.headers["Depth"])
        assertTrue(request.headers["Authorization"]!!.startsWith("Basic "))
    }

    @Test
    fun readingFromAnOffsetAsksForARange() {
        server.enqueue(MockResponse.Builder().code(206).body("6789").build())
        val source = Source("dav", "Cloud", SourceKind.WEBDAV, url = server.url("/dav").toString())
        WebDavFs(source) { null }.use { fs ->
            assertEquals("6789", fs.root.child("a.mkv").open(6).use { String(it.readBytes()) })
        }
        assertEquals("bytes=6-", server.takeRequest().headers["Range"])
    }

    @Test
    fun aServerIgnoringTheRangeHasTheStartSkipped() {
        server.enqueue(MockResponse.Builder().code(200).body("0123456789").build())
        val source = Source("dav", "Cloud", SourceKind.WEBDAV, url = server.url("/dav").toString())
        WebDavFs(source) { null }.use { fs ->
            assertEquals("6789", fs.root.child("a.mkv").open(6).use { String(it.readBytes()) })
        }
    }
}
