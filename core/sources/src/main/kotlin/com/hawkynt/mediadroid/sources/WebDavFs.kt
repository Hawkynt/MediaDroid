package com.hawkynt.mediadroid.sources

import com.hawkynt.mediadroid.library.FsFile
import com.hawkynt.mediadroid.library.Source
import java.io.IOException
import java.io.InputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element

/**
 * A WebDAV folder — Nextcloud, ownCloud, NAS web shares — read by RFC 4918's PROPFIND and ranged
 * GETs over OkHttp. [Source.url] is the folder's URL; locations are the files' URLs. Signs in with
 * Basic authentication when the source has a user.
 */
class WebDavFs(private val source: Source, private val secrets: Secrets) : RemoteFs {

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    private val base: HttpUrl = (source.url.trimEnd('/') + "/").toHttpUrlOrNull()
        ?: throw IOException("Not a WebDAV address: ${source.url}")

    override val root: FsFile get() = WebDavFile(this, base, isDirectory = true, size = 0, modified = 0)

    private fun request(url: HttpUrl): Request.Builder = Request.Builder().url(url).apply {
        if (source.user.isNotEmpty()) {
            header("Authorization", Credentials.basic(source.user, secrets.secretFor(source.id).orEmpty()))
        }
    }

    /** PROPFIND [url] at [depth]: the entries the server reports, the folder itself first at depth 1. */
    internal fun propfind(url: HttpUrl, depth: Int): List<WebDavFile> {
        val call = request(url)
            .header("Depth", depth.toString())
            .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .build()
        client.newCall(call).execute().use { response ->
            if (response.code != MULTI_STATUS) throw IOException("${url.host} answered ${response.code}")
            val xml = response.body.byteStream()
            val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(xml)
            val responses = document.getElementsByTagNameNS(DAV, "response")
            return (0 until responses.length).mapNotNull { i ->
                val entry = responses.item(i) as Element
                val href = text(entry, "href") ?: return@mapNotNull null
                val at = url.resolve(href) ?: return@mapNotNull null
                val props = entry.getElementsByTagNameNS(DAV, "prop").item(0) as? Element ?: return@mapNotNull null
                val folder = props.getElementsByTagNameNS(DAV, "collection").length > 0
                WebDavFile(
                    this,
                    at,
                    isDirectory = folder,
                    size = text(props, "getcontentlength")?.toLongOrNull() ?: 0,
                    modified = text(props, "getlastmodified")?.let(::httpDate) ?: 0,
                )
            }
        }
    }

    /** GET [url] from byte [offset] on. */
    internal fun get(url: HttpUrl, offset: Long): InputStream {
        val call = request(url).apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
        val response = client.newCall(call).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("${url.host} answered ${response.code} for $url")
        }
        val stream = response.body.byteStream()
        // A server that ignores the range sends the whole file; the start is skipped here then.
        if (offset > 0 && response.code != PARTIAL_CONTENT) stream.skipNBytes(offset)
        return stream
    }

    override fun resolve(location: String): FsFile? {
        if (!location.startsWith(base.toString().trimEnd('/'))) return null
        val url = location.toHttpUrlOrNull() ?: return null
        return runCatching { propfind(url, 0).firstOrNull() }.getOrNull()
    }

    override fun close() {
        client.connectionPool.evictAll()
    }

    private fun text(element: Element, name: String): String? =
        element.getElementsByTagNameNS(DAV, name).item(0)?.textContent?.trim()?.ifEmpty { null }

    private fun httpDate(text: String): Long? = runCatching {
        ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    }.getOrNull()

    private companion object {
        const val DAV = "DAV:"
        const val MULTI_STATUS = 207
        const val PARTIAL_CONTENT = 206
        const val TIMEOUT_S = 20L
        val PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:"><d:prop>
              <d:resourcetype/><d:getcontentlength/><d:getlastmodified/>
            </d:prop></d:propfind>
        """.trimIndent()
    }
}

/** A file or folder on a WebDAV server, at [url]. */
internal class WebDavFile(
    private val fs: WebDavFs,
    private val url: HttpUrl,
    override val isDirectory: Boolean,
    private val size: Long,
    private val modified: Long,
) : FsFile {
    override val name: String get() = url.pathSegments.lastOrNull { it.isNotEmpty() }.orEmpty()
    override val path: String get() = url.toString().trimEnd('/')
    override val parentFile: FsFile?
        get() = url.resolve(if (url.encodedPath.endsWith("/")) ".." else ".")
            ?.let { WebDavFile(fs, it, isDirectory = true, size = 0, modified = 0) }

    override fun length(): Long = size

    override fun lastModified(): Long = modified

    override fun listFiles(): List<FsFile>? = runCatching {
        val self = folderUrl()
        // The folder itself comes back as one of the entries; it is no child of its own.
        fs.propfind(self, 1).filter { it.folderUrl() != self }
    }.getOrNull()

    override fun child(name: String): FsFile {
        val url = folderUrl().newBuilder().addPathSegment(name).build()
        return WebDavFile(fs, url, isDirectory = false, size = 0, modified = 0)
    }

    override fun open(offset: Long): InputStream = fs.get(url, offset)

    /** The URL with a trailing slash, as a folder's is compared. */
    internal fun folderUrl(): HttpUrl =
        if (url.encodedPath.endsWith("/")) url else url.newBuilder().addPathSegment("").build()

    override fun equals(other: Any?): Boolean = other is WebDavFile && other.path == path

    override fun hashCode(): Int = path.hashCode()

    override fun toString(): String = path
}
