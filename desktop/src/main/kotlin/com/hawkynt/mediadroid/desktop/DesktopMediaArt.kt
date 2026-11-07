package com.hawkynt.mediadroid.desktop

import com.hawkynt.mediadroid.ui.platform.MediaArt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * The shared screens' pictures on the desktop: a picture on this computer as it is, one on a network source copied
 * once into [cache] (the app-data folder's `art/`), named by its location and kept while the source's file stays
 * the same size. Stills from the videos and probing the files are not offered here yet.
 */
class DesktopMediaArt(private val places: DesktopLibrary, private val cache: File) : MediaArt {

    override suspend fun picture(location: String): File? = withContext(Dispatchers.IO) {
        File(location).takeIf { it.isFile }?.let { return@withContext it }
        runCatching {
            val remote = places.remoteFile(location) ?: return@runCatching null
            val extension = location.substringAfterLast('.', "img").takeIf { it.length <= 4 } ?: "img"
            val target = File(cache, "${keyOf(location)}-${remote.length()}.$extension")
            if (!target.isFile) {
                cache.mkdirs()
                val aside = File(cache, target.name + ".part")
                remote.open().use { input -> aside.outputStream().use { input.copyTo(it) } }
                aside.renameTo(target)
            }
            target
        }.getOrNull()
    }

    private fun keyOf(location: String): String =
        MessageDigest.getInstance("SHA-1").digest(location.toByteArray()).joinToString("") { "%02x".format(it) }
}
