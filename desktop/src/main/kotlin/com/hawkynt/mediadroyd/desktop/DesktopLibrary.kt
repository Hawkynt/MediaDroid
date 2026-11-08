package com.hawkynt.mediadroyd.desktop

import com.hawkynt.mediadroyd.library.FsFile
import com.hawkynt.mediadroyd.library.Library
import com.hawkynt.mediadroyd.library.LibraryScanner
import com.hawkynt.mediadroyd.library.LocalFile
import com.hawkynt.mediadroyd.library.Source
import com.hawkynt.mediadroyd.library.SourceKind
import com.hawkynt.mediadroyd.sources.SourceFiles
import java.io.File

/**
 * The household's places for media on the desktop: the folder picked on this machine and the network sources added
 * (SMB, NFS, FTP/FTPS, SFTP, WebDAV — core:sources), their passwords in [secrets]. It scans them into one library and
 * finds the file behind any location in it, for art and for playing.
 */
class DesktopLibrary(private val store: DesktopStore, private val secrets: DesktopSecrets) : AutoCloseable {

    private val files = SourceFiles(secrets)

    /** The network sources (and further local folders), in the order they were added. */
    val networkSources: List<Source> get() = store.sources

    private val sources: List<Source>
        get() = listOfNotNull(store.libraryFolder?.let { Source.local(LOCAL_ID, "This computer", it) }) +
            store.sources

    /** Adds [source], keeping its [password] (if any) with the system's keeper, not beside it. */
    fun add(source: Source, password: String?) {
        secrets.store(source.id, password?.takeIf { it.isNotEmpty() })
        store.sources = store.sources.filter { it.id != source.id } + source
        files.forget(source.id)
    }

    /** Lets go of the connection open to the source [sourceId]: it changed or is gone. */
    fun forget(sourceId: String) = files.forget(sourceId)

    /** Removes [source] and forgets its password. */
    fun remove(source: Source) {
        store.sources = store.sources.filter { it.id != source.id }
        secrets.store(source.id, null)
        files.forget(source.id)
    }

    /** Every source scanned, merged into one library; a source that cannot be reached adds nothing. */
    fun scan(): Library = Library.merge(
        sources.mapNotNull { source ->
            runCatching { files.root(source)?.let { LibraryScanner.scan(it) } }.getOrNull()
        },
    )

    /** Tries [source] once: null when its root can be listed, else what went wrong, in words. */
    fun check(source: Source, password: String?): String? = runCatching {
        val root = if (source.kind == SourceKind.LOCAL) {
            LocalFile(File(source.path))
        } else {
            val remote = SourceFiles.open(source) { password } ?: return "This kind of source cannot be read yet."
            return remote.use { if (it.root.listFiles() == null) "The folder cannot be read." else null }
        }
        if (root.listFiles() == null) "The folder cannot be read." else null
    }.getOrElse { it.message ?: it.toString() }

    /** The file at [location]: on whichever source holds it, else on this computer. */
    fun file(location: String): FsFile = remoteFile(location) ?: LocalFile(File(location))

    /** The file at [location] when a network source holds it; null for this computer's own files. */
    fun remoteFile(location: String): FsFile? =
        if (File(location).isAbsolute) null else files.resolve(sources, location)

    override fun close() = files.close()

    private companion object {
        const val LOCAL_ID = "local"
    }
}
