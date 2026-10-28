package com.hawkynt.mediadroid.data

import android.content.Context
import android.graphics.BitmapFactory
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pictures from network sources, fetched once and kept on the device: the poster or thumb of a
 * stored location is copied into the app's cache — as it is when small, scaled down to at most
 * [MAX_SIZE] pixels otherwise — and shown from there, also while the source is away. A picture that
 * changed on the source is fetched again (the key holds its modification time).
 */
object ArtCache {
    private const val MAX_SIZE = 720
    private const val SMALL_BYTES = 256 * 1024

    /** A local file showing [location]: the location itself on the device, else the cached copy. */
    suspend fun fileFor(context: Context, location: String): File? = withContext(Dispatchers.IO) {
        if (location.startsWith("/")) return@withContext File(location).takeIf { it.isFile }
        val root = File(context.cacheDir, "art").apply { mkdirs() }
        // Offline, the newest copy of this location is still good.
        val remote = SourceAccess.resolve(context, location)
            ?: return@withContext root.listFiles { f -> f.name.startsWith(hash(location)) }
                ?.maxByOrNull { it.lastModified() }
        val target = File(root, hash(location) + "-" + remote.lastModified() + ".img")
        if (target.isFile && target.length() > 0) return@withContext target
        runCatching {
            val bytes = remote.open().use { it.readBytes() }
            if (bytes.size <= SMALL_BYTES) {
                target.writeBytes(bytes)
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIZE) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("not a picture")
                target.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
                bitmap.recycle()
            }
            target
        }.getOrElse {
            target.delete()
            null
        }
    }

    private fun hash(location: String): String =
        MessageDigest.getInstance("SHA-1").digest(location.toByteArray()).joinToString("") { "%02x".format(it) }
}
