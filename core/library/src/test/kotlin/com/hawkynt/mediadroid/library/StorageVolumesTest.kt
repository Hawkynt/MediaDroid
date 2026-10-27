package com.hawkynt.mediadroid.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StorageVolumesTest {

    private val primary = "/storage/emulated/0"

    private fun path(documentId: String) = StorageVolumes.pathForTreeDocumentId(documentId, primary)

    @Test
    fun primaryVolumeMapsOntoTheEmulatedStorageRoot() {
        assertEquals("/storage/emulated/0/Movies", path("primary:Movies"))
        assertEquals("/storage/emulated/0/Media/TV Shows", path("primary:Media/TV Shows"))
        assertEquals("/storage/emulated/0", path("primary:"))
    }

    @Test
    fun sdCardAndUsbVolumesAreNamedByTheirFilesystemUuid() {
        assertEquals("/storage/1A2B-3C4D/Movies", path("1A2B-3C4D:Movies"))
        assertEquals("/storage/1A2B-3C4D", path("1A2B-3C4D:"))
    }

    @Test
    fun legacyHomeRootIsTheDocumentsFolder() {
        assertEquals("/storage/emulated/0/Documents/Films", path("home:Films"))
    }

    @Test
    fun rawIdsCarryTheAbsolutePathThemselves() {
        assertEquals("/storage/emulated/0/Download/Films", path("raw:/storage/emulated/0/Download/Films"))
    }

    @Test
    fun trailingSlashesAreDropped() {
        assertEquals("/storage/1A2B-3C4D/Movies", path("1A2B-3C4D:Movies/"))
    }

    @Test
    fun idsWithoutAVolumeAreNotOnLocalStorage() {
        assertNull(path("Movies"))
        assertNull(path(":Movies"))
        assertNull(path("raw:relative/path"))
        assertNull(path(""))
    }
}
