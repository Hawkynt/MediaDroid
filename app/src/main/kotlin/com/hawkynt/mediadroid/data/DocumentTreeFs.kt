package com.hawkynt.mediadroid.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.hawkynt.mediadroid.library.FsFile
import com.hawkynt.mediadroid.library.Source
import com.hawkynt.mediadroid.sources.RemoteFs
import java.io.FileInputStream
import java.io.InputStream

/**
 * A folder from an Android document provider — Google Drive, OneDrive, Dropbox, Nextcloud, anything
 * that installs one — picked in the system's own folder picker. [Source.url] is its tree URI; the
 * provider signs in, the app stores no password. Locations are the documents' URIs below the tree.
 */
class DocumentTreeFs(private val resolver: ContentResolver, source: Source) : RemoteFs {

    private val tree: Uri = Uri.parse(source.url)

    override val root: FsFile
        get() = DocumentEntry(
            this,
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)),
            parent = null,
            name = "",
            isDirectory = true,
            size = 0,
            modified = 0,
        )

    override fun resolve(location: String): FsFile? = runCatching {
        val uri = Uri.parse(location)
        query(uri).firstOrNull()?.let { (_, row) -> entry(uri, null, row) }
    }.getOrNull()

    /** The documents in [folder], each knowing the folder it was listed from. */
    internal fun children(folder: DocumentEntry): List<FsFile> {
        val id = DocumentsContract.getDocumentId(folder.uri)
        return query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)).map { (childId, row) ->
            entry(DocumentsContract.buildDocumentUriUsingTree(tree, childId), folder, row)
        }
    }

    internal fun open(uri: Uri, offset: Long): InputStream {
        val descriptor = resolver.openFileDescriptor(uri, "r") ?: error("cannot open $uri")
        val stream = FileInputStream(descriptor.fileDescriptor)
        // Seekable for providers that hand out real files; a pipe from a cloud provider is skipped instead.
        if (offset > 0 && runCatching { stream.channel.position(offset) }.isFailure) stream.skipNBytes(offset)
        return object : java.io.FilterInputStream(stream) {
            override fun close() {
                runCatching { super.close() }
                runCatching { descriptor.close() }
            }
        }
    }

    private data class Row(val name: String, val mime: String, val size: Long, val modified: Long)

    private fun entry(uri: Uri, parent: DocumentEntry?, row: Row) = DocumentEntry(
        this,
        uri,
        parent,
        row.name,
        isDirectory = row.mime == DocumentsContract.Document.MIME_TYPE_DIR,
        size = row.size,
        modified = row.modified,
    )

    private fun query(uri: Uri): List<Pair<String, Row>> {
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        return resolver.query(uri, columns, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        cursor.getString(0) to Row(
                            name = cursor.getString(1).orEmpty(),
                            mime = cursor.getString(2).orEmpty(),
                            size = if (cursor.isNull(3)) 0 else cursor.getLong(3),
                            modified = if (cursor.isNull(4)) 0 else cursor.getLong(4),
                        ),
                    )
                }
            }
        } ?: emptyList()
    }

    override fun close() = Unit
}

/** A document or folder in a [DocumentTreeFs]. */
internal class DocumentEntry(
    private val fs: DocumentTreeFs,
    val uri: Uri,
    /** The folder it was listed from; providers give no way up otherwise. */
    private val parent: DocumentEntry?,
    override val name: String,
    override val isDirectory: Boolean,
    private val size: Long,
    private val modified: Long,
) : FsFile {
    override val path: String get() = uri.toString()

    override val parentFile: FsFile? get() = parent

    // A provider query per call adds up: the scanner asks a folder for many children by name.
    private val listing: List<FsFile>? by lazy { runCatching { fs.children(this) }.getOrNull() }

    override fun length(): Long = size

    override fun lastModified(): Long = modified

    override fun listFiles(): List<FsFile>? = listing

    override fun child(name: String): FsFile =
        listing?.firstOrNull { it.name == name }
            ?: DocumentEntry(fs, uri, this, name, isDirectory = false, size = 0, modified = 0)

    override fun open(offset: Long): InputStream = fs.open(uri, offset)

    override fun equals(other: Any?): Boolean = other is DocumentEntry && other.uri == uri

    override fun hashCode(): Int = uri.hashCode()

    override fun toString(): String = path
}
