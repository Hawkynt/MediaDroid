package com.hawkynt.mediadroid.library

/**
 * Maps a folder picked in Android's folder picker back onto the file system the scanner walks.
 *
 * The picker answers with a tree URI whose document id is `<rootId>:<path below the root>`, as built
 * by AOSP's `ExternalStorageProvider.getDocIdForFileMaybeCreate`: the root id is `primary` for the
 * built-in storage and the filesystem UUID for an SD card or USB drive, which Android mounts at
 * `/storage/<UUID>`. Older releases also exposed the Documents folder as the root `home`, and the
 * Downloads provider hands out `raw:<absolute path>`.
 */
object StorageVolumes {

    /** `DocumentsContract.EXTERNAL_STORAGE_PRIMARY_EMULATED_ROOT_ID`. */
    const val PRIMARY_ROOT_ID = "primary"

    private const val HOME_ROOT_ID = "home"
    private const val HOME_DIRECTORY = "Documents"
    private const val RAW_PREFIX = "raw:"
    private const val VOLUMES_ROOT = "/storage"

    /**
     * The absolute path of [documentId] — [primaryRoot] stands for the built-in storage — or null
     * when the id names nothing on local storage.
     */
    fun pathForTreeDocumentId(documentId: String, primaryRoot: String): String? {
        if (documentId.startsWith(RAW_PREFIX)) {
            return documentId.removePrefix(RAW_PREFIX).takeIf { it.startsWith("/") }?.trimEnd('/')
        }
        val colon = documentId.indexOf(':')
        if (colon <= 0) return null
        val rootId = documentId.substring(0, colon)
        val relative = documentId.substring(colon + 1).trim('/')
        val base = when {
            rootId.equals(PRIMARY_ROOT_ID, ignoreCase = true) -> primaryRoot.trimEnd('/')
            rootId.equals(HOME_ROOT_ID, ignoreCase = true) -> primaryRoot.trimEnd('/') + "/" + HOME_DIRECTORY
            else -> "$VOLUMES_ROOT/$rootId"
        }
        return if (relative.isEmpty()) base else "$base/$relative"
    }
}
