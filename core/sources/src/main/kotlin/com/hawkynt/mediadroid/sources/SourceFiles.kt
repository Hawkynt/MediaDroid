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

    private val smb = HashMap<String, SmbFs>()

    /** The root of [source]; null for a kind of source that cannot be read yet. */
    fun root(source: Source): FsFile? = when (source.kind) {
        SourceKind.LOCAL -> LocalFile(File(source.path))
        SourceKind.SMB -> smbOf(source).root
        else -> null
    }

    /** The file at [location] on whichever of [sources] holds it; local paths need no source. */
    fun resolve(sources: List<Source>, location: String): FsFile? {
        if (location.startsWith("/")) return LocalFile(File(location))
        val source = Source.owning(sources, location) ?: return null
        return when (source.kind) {
            SourceKind.SMB -> smbOf(source).resolve(location)
            else -> null
        }
    }

    @Synchronized
    private fun smbOf(source: Source): SmbFs {
        smb[source.id]?.let { return it }
        return SmbFs(source, secrets).also { smb[source.id] = it }
    }

    /** Forgets [sourceId]'s connection, after its settings or password changed. */
    @Synchronized
    fun forget(sourceId: String) {
        smb.remove(sourceId)?.close()
    }

    @Synchronized
    override fun close() {
        smb.values.forEach { it.close() }
        smb.clear()
    }
}
