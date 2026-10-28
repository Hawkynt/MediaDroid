package com.hawkynt.mediadroid.data

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

/** A place the Files browser starts from: the internal storage, an SD card, a USB drive. */
data class StorageRoot(val label: String, val path: String, val removable: Boolean)

/** The device's mounted storage volumes, the internal storage first. */
object StorageRoots {

    fun list(context: Context): List<StorageRoot> {
        val manager = context.getSystemService(StorageManager::class.java)
        val volumes = manager?.storageVolumes.orEmpty()
            .filter { it.state == Environment.MEDIA_MOUNTED || it.state == Environment.MEDIA_MOUNTED_READ_ONLY }
        val roots = volumes.mapNotNull { volume ->
            val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                volume.directory
            } else {
                // Before Android 11 the path is only reachable by its UUID under /storage.
                if (volume.isPrimary) {
                    Environment.getExternalStorageDirectory()
                } else {
                    volume.uuid?.let { File("/storage/$it") }
                }
            }
            dir?.takeIf { it.canRead() }?.let {
                StorageRoot(volume.getDescription(context), it.absolutePath, volume.isRemovable)
            }
        }
        return roots.ifEmpty {
            listOf(StorageRoot("Internal storage", Environment.getExternalStorageDirectory().absolutePath, false))
        }.sortedBy { it.removable }
    }
}
