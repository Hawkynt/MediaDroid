package com.hawkynt.mediadroid.library

import com.hawkynt.mediadroid.nfo.TimeRange
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryCacheTest {

    @Test
    fun aLibraryComesBackFromItsCacheAsItWas() {
        val heat = Movie(
            title = "Heat",
            year = 1995,
            path = "smb://nas/media/Heat",
            videoPath = "smb://nas/media/Heat/Heat.mkv",
            intro = TimeRange(0, 90_000),
            quality = Quality(resolution = "4K", hdr = "HDR10"),
            versions = listOf(Movie(title = "Heat", path = "/a", videoPath = "/a/Heat.mkv")),
        )
        val dark = Show(
            title = "Dark",
            path = "smb://nas/media/Dark",
            seasons = listOf(Season(1, listOf(Episode(1, "Secrets", videoPath = "smb://nas/media/Dark/e1.mkv")))),
        )
        val library = Library(listOf(heat), listOf(dark))
        assertEquals(library, LibraryCodec.decode(LibraryCodec.encode(library)))
    }

    @Test
    fun aDamagedCacheIsNoLibrary() {
        assertEquals(null, LibraryCodec.decode("{not json"))
    }

    /** A folder of files in memory that counts how often each is opened. */
    private class Counted(
        override val name: String,
        private val content: String?,
        private val modified: Long,
        private val children: List<Counted> = emptyList(),
        override val parentFile: FsFile? = null,
    ) : FsFile {
        val opened = HashMap<String, Int>()
        override val path: String get() = "mem:/$name"
        override val isDirectory: Boolean get() = content == null
        override fun length(): Long = content?.length?.toLong() ?: 0
        override fun lastModified(): Long = modified
        override fun listFiles(): List<FsFile>? = children.takeIf { isDirectory }
        override fun child(name: String): FsFile = children.first { it.name == name }
        override fun open(offset: Long): InputStream {
            opened.merge(name, 1, Int::plus)
            return ByteArrayInputStream(content!!.toByteArray(), offset.toInt(), Int.MAX_VALUE)
        }
    }

    @Test
    fun aRescanReadsASidecarAgainOnlyWhenItChanged() {
        val cache = SidecarCache()
        val nfo = Counted("movie.nfo", "<movie><title>Heat</title></movie>", modified = 1000)
        val video = Counted("Heat.mkv", "video", modified = 1000)
        val folder = Counted("Heat (1995)", null, 1000, listOf(nfo, video))

        repeat(2) {
            val read = cache.wrap(folder).listFiles()!!.single { it.name == "movie.nfo" }.readTextOrNull()
            assertEquals("<movie><title>Heat</title></movie>", read)
        }
        assertEquals(1, nfo.opened["movie.nfo"])

        val changed = Counted("movie.nfo", "<movie><title>Heat 2</title></movie>", modified = 2000)
        val again = Counted("Heat (1995)", null, 1000, listOf(changed, video))
        assertEquals(
            "<movie><title>Heat 2</title></movie>",
            cache.wrap(again).listFiles()!!.single { it.name == "movie.nfo" }.readTextOrNull(),
        )
        // The video itself is never cached.
        cache.wrap(folder).listFiles()!!.single { it.name == "Heat.mkv" }.open().close()
        cache.wrap(folder).listFiles()!!.single { it.name == "Heat.mkv" }.open().close()
        assertEquals(2, video.opened["Heat.mkv"])
    }

    @Test
    fun theSidecarCacheIsKeptAsText() {
        val cache = SidecarCache()
        val nfo = Counted("movie.nfo", "<movie/>", modified = 1000)
        cache.wrap(Counted("d", null, 1, listOf(nfo))).listFiles()!!.single().readTextOrNull()
        val restored = SidecarCache.decode(cache.encode())
        restored.wrap(Counted("d", null, 1, listOf(nfo))).listFiles()!!.single().readTextOrNull()
        assertEquals(1, nfo.opened["movie.nfo"])
    }
}
