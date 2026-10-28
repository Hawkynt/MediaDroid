package com.hawkynt.mediadroid.library

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How a [Source] is reached. */
enum class SourceKind(val scheme: String?) {
    /** Storage on the device itself: internal storage, SD card, USB drive. */
    LOCAL(null),

    /** A Windows or Samba share, SMB 2/3. */
    SMB("smb"),

    /** An NFS export. */
    NFS("nfs"),
    FTP("ftp"),

    /** FTP over TLS. */
    FTPS("ftps"),

    /** File transfer over SSH. */
    SFTP("sftp"),

    /** WebDAV — Nextcloud, ownCloud, NAS web shares; [Source.url] is the folder's URL. */
    WEBDAV(null),

    /** A folder from an Android document provider — Drive, OneDrive, Dropbox; [Source.url] is its tree URI. */
    DOCUMENTS(null),
}

/**
 * A place media lives, saved once with a name: a folder on the device or on a network or cloud
 * source. It never holds a password or key — those live in the Android Keystore, by [id] — so a
 * Source can be stored and shown anywhere.
 */
@Serializable
data class Source(
    val id: String,
    val name: String,
    val kind: SourceKind,
    val host: String = "",
    /** The port, or 0 for the protocol's default. */
    val port: Int = 0,
    /** The folder on the host: a local path, a share and folder (`/media/movies`), an export. */
    val path: String = "",
    val user: String = "",
    /** The whole location for WebDAV and document providers, which are addressed by URL. */
    val url: String = "",
    /**
     * SFTP: the server's host key fingerprint, taken on the first connection and checked on every
     * later one, so a server that changed its key is refused rather than trusted.
     */
    val hostKey: String = "",
) {
    /** Where the source's root is: an absolute local path, or a URI every location below it starts with. */
    val rootUri: String
        get() = when (kind) {
            SourceKind.LOCAL -> path.trimEnd('/').ifEmpty { "/" }
            SourceKind.WEBDAV, SourceKind.DOCUMENTS -> url.trimEnd('/')
            else -> buildString {
                append(kind.scheme).append("://").append(host)
                if (port > 0) append(':').append(port)
                append('/').append(path.trim('/'))
            }.trimEnd('/')
        }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** A folder on the device itself. */
        fun local(id: String, name: String, path: String) = Source(id, name, SourceKind.LOCAL, path = path)

        /** The source in [sources] whose root holds [location]; null when none does. */
        fun owning(sources: List<Source>, location: String): Source? = sources
            .filter { location == it.rootUri || location.startsWith(it.rootUri.trimEnd('/') + "/") }
            .maxByOrNull { it.rootUri.length }

        /** The source as one line of JSON, for the state file. */
        fun encode(source: Source): String = json.encodeToString(serializer(), source)

        fun decode(line: String): Source = json.decodeFromString(serializer(), line)
    }
}
