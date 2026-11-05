package com.hawkynt.mediadroid.data

import android.Manifest
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.hawkynt.mediadroid.player.Snapshot
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * A snapshot of the picture saved as a PNG in the system's Pictures collection, in a MediaDroid album, and shared from
 * there. From Android 10 through MediaStore, which needs no permission; before it into the shared Pictures folder,
 * which needs the storage permission (the same group as reading the library, so normally granted without asking).
 */
object SnapshotSaver {

    /** The album the snapshots go into, inside Pictures. */
    private const val ALBUM = "MediaDroid"

    /** Whether saving needs the storage permission asked for first: only before Android 10, and only until given. */
    fun needsPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    /**
     * [bitmap] as `name` (see Snapshot.fileName) in Pictures/MediaDroid; its content address, to share it by — null
     * where the media scanner gave none (saved all the same, but not to be shared).
     */
    suspend fun save(context: Context, bitmap: Bitmap, name: String): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveToMediaStore(context, bitmap, name)
        else saveToFolder(context, bitmap, name)

    // Android 10 and later: an entry made pending, written, then published. A name already taken gets a number from
    // MediaStore itself; nothing is overwritten.
    private fun saveToMediaStore(context: Context, bitmap: Bitmap, name: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: error("the Pictures collection took no new picture")
        try {
            val stream = resolver.openOutputStream(uri) ?: error("the new picture could not be written")
            stream.use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "the picture was not encoded" } }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    // Before Android 10: a file in the shared Pictures folder, under the next free name, then told to the media
    // scanner, which hands back the address the gallery and the share sheet know it by.
    @Suppress("DEPRECATION")
    private suspend fun saveToFolder(context: Context, bitmap: Bitmap, name: String): Uri? {
        val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM)
        folder.mkdirs()
        var file: File
        do {
            file = File(folder, Snapshot.freeName(name) { File(folder, it).exists() })
        } while (!file.createNewFile())
        try {
            file.outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "the picture was not encoded" }
            }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        return suspendCancellableCoroutine { done ->
            MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf("image/png")) { _, uri ->
                done.resume(uri)
            }
        }
    }

    /** The share sheet for the picture at [uri], with read access for whichever app is picked. */
    fun share(context: Context, uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, null).apply {
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
