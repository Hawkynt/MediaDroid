package com.hawkynt.mediadroid.data

import android.content.Context
import com.hawkynt.mediadroid.library.Library
import com.hawkynt.mediadroid.library.LibraryCodec
import com.hawkynt.mediadroid.library.SidecarCache
import java.io.File

/**
 * Each source's last scan and the sidecar files it read, kept on the device: the walls show at once
 * on start and while a source is away, and a rescan reads again only the NFOs that changed.
 */
class LibraryStore(context: Context) {
    private val dir = File(context.filesDir, "libraries").apply { mkdirs() }

    /** The library [sourceId] had on its last good scan; null when it was never scanned. */
    fun load(sourceId: String): Library? =
        File(dir, "$sourceId.json").takeIf { it.isFile }?.let { LibraryCodec.decode(it.readText()) }

    fun save(sourceId: String, library: Library) {
        File(dir, "$sourceId.json").writeText(LibraryCodec.encode(library))
    }

    /** [sourceId]'s sidecar cache, to wrap its root with for a scan. */
    fun sidecars(sourceId: String): SidecarCache =
        File(dir, "$sourceId.sidecars.json").takeIf { it.isFile }?.let { SidecarCache.decode(it.readText()) }
            ?: SidecarCache()

    fun saveSidecars(sourceId: String, cache: SidecarCache) {
        File(dir, "$sourceId.sidecars.json").writeText(cache.encode())
    }

    /** Forgets a removed source. */
    fun forget(sourceId: String) {
        File(dir, "$sourceId.json").delete()
        File(dir, "$sourceId.sidecars.json").delete()
    }
}
