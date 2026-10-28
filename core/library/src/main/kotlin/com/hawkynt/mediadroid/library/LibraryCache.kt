package com.hawkynt.mediadroid.library

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** A [Library] as text and back, for keeping a source's scan on the device. */
object LibraryCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(library: Library): String = json.encodeToString(Library.serializer(), library)

    /** The library in [text]; null when it is damaged or from an incompatible version. */
    fun decode(text: String): Library? = runCatching { json.decodeFromString(Library.serializer(), text) }.getOrNull()
}

/**
 * The small sidecar files a scan reads — NFOs, EDLs — kept by location, size and modification time,
 * so scanning a network source again reads only what changed. Everything else, videos and pictures,
 * passes through untouched.
 */
class SidecarCache(private val entries: MutableMap<String, String> = HashMap()) {

    /** [root] with its sidecar files served from this cache where they did not change. */
    fun wrap(root: FsFile): FsFile = Cached(root)

    /** The cache as text, to keep on the device. */
    @Synchronized
    fun encode(): String = Json.encodeToString(SERIALIZER, entries)

    @Synchronized
    private fun text(file: FsFile): String? {
        val key = "${file.path}|${file.length()}|${file.lastModified()}"
        entries[key]?.let { return it }
        val read = runCatching { file.open().use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            ?: return null
        // Only the newest version of a location is worth keeping.
        entries.keys.removeAll { it.startsWith(file.path + "|") }
        entries[key] = read
        return read
    }

    private fun cacheable(file: FsFile): Boolean =
        !file.isDirectory && file.lastModified() > 0 && file.length() in 1..MAX_SIZE &&
            file.extension.lowercase() in EXTENSIONS

    private inner class Cached(private val inner: FsFile) : FsFile by inner {
        override val parentFile: FsFile? get() = inner.parentFile?.let { Cached(it) }

        override fun listFiles(): List<FsFile>? = inner.listFiles()?.map { Cached(it) }

        override fun child(name: String): FsFile = Cached(inner.child(name))

        override fun open(offset: Long): InputStream {
            if (!cacheable(inner)) return inner.open(offset)
            val bytes = (text(inner) ?: return inner.open(offset)).toByteArray(Charsets.UTF_8)
            return ByteArrayInputStream(bytes, offset.toInt().coerceAtMost(bytes.size), Int.MAX_VALUE)
        }

        override fun equals(other: Any?): Boolean = other is Cached && other.inner == inner

        override fun hashCode(): Int = inner.hashCode()

        override fun toString(): String = inner.toString()
    }

    companion object {
        private const val MAX_SIZE = 256L * 1024
        private val EXTENSIONS = setOf("nfo", "edl", "xml")
        private val SERIALIZER = MapSerializer(String.serializer(), String.serializer())

        /** A cache kept with [encode]; an empty one when [text] is damaged. */
        fun decode(text: String): SidecarCache = SidecarCache(
            runCatching { Json.decodeFromString(SERIALIZER, text).toMutableMap() }.getOrDefault(HashMap()),
        )
    }
}
