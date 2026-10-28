package com.hawkynt.mediadroid.sources

import com.hawkynt.mediadroid.library.FsFile
import com.hawkynt.mediadroid.library.LocalFile
import com.hawkynt.mediadroid.library.Source
import com.hawkynt.mediadroid.library.SourceKind
import java.io.File

/**
 * The files of the household's [Source]s: a source's root to scan or browse, and the file behind any
 * stored location — a resume point, a poster — to play or show. One connection per network source,
 * kept open until [close].
 */
class SourceFiles(private val secrets: Secrets) : AutoCloseable {

    private val remote = HashMap<String, RemoteFs>()

    /** The root of [source]; null for a kind of source that cannot be read yet. */
    fun root(source: Source): FsFile? =
        if (source.kind == SourceKind.LOCAL) LocalFile(File(source.path)) else remoteOf(source)?.root

    /** The file at [location] on whichever of [sources] holds it; local paths need no source. */
    fun resolve(sources: List<Source>, location: String): FsFile? {
        if (location.startsWith("/")) return LocalFile(File(location))
        val source = Source.owning(sources, location) ?: return null
        return remoteOf(source)?.resolve(location)
    }

    @Synchronized
    private fun remoteOf(source: Source): RemoteFs? {
        remote[source.id]?.let { return it }
        return open(source, secrets)?.also { remote[source.id] = it }
    }

    /** Forgets [sourceId]'s connection, after its settings or password changed. */
    @Synchronized
    fun forget(sourceId: String) {
        remote.remove(sourceId)?.close()
    }

    @Synchronized
    override fun close() {
        remote.values.forEach { it.close() }
        remote.clear()
    }

    companion object {
        /** A fresh connection to [source]; null for a kind that cannot be read yet. */
        fun open(source: Source, secrets: Secrets): RemoteFs? = when (source.kind) {
            SourceKind.SMB -> SmbFs(source, secrets)
            SourceKind.FTP, SourceKind.FTPS -> FtpFs(source, secrets)
            SourceKind.SFTP -> SftpFs(source, secrets)
            SourceKind.WEBDAV -> WebDavFs(source, secrets)
            else -> null
        }
    }
}
