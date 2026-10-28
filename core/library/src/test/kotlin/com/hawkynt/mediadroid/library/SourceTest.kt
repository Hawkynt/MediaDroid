package com.hawkynt.mediadroid.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceTest {

    private val nas = Source(
        id = "nas",
        name = "Living-room NAS",
        kind = SourceKind.SMB,
        host = "nas.local",
        path = "/media/movies",
        user = "family",
    )

    @Test
    fun aSourceSaysWhereItsRootIs() {
        assertEquals("smb://nas.local/media/movies", nas.rootUri)
        assertEquals("ftp://nas.local:2121/pub", nas.copy(kind = SourceKind.FTP, port = 2121, path = "pub").rootUri)
        assertEquals("sftp://box/home/me/videos", nas.copy(kind = SourceKind.SFTP, host = "box", path = "/home/me/videos").rootUri)
        assertEquals("nfs://nas.local/export/video", nas.copy(kind = SourceKind.NFS, path = "/export/video").rootUri)
        assertEquals("/storage/emulated/0/Movies", Source.local("phone", "Phone", "/storage/emulated/0/Movies").rootUri)
    }

    @Test
    fun webDavKeepsItsOwnUrlAndCloudsTheirDocumentTree() {
        val dav = Source("cloud", "Nextcloud", SourceKind.WEBDAV, url = "https://cloud.example.org/remote.php/dav/files/me/Videos")
        assertEquals("https://cloud.example.org/remote.php/dav/files/me/Videos", dav.rootUri)
        val drive = Source("drive", "Drive", SourceKind.DOCUMENTS, url = "content://com.google.android.apps.docs.storage/tree/abc")
        assertEquals("content://com.google.android.apps.docs.storage/tree/abc", drive.rootUri)
    }

    @Test
    fun aLocationIsFoundBackToTheSourceItLivesOn() {
        val phone = Source.local("phone", "Phone", "/storage/emulated/0")
        val sources = listOf(phone, nas)
        assertEquals(nas, Source.owning(sources, "smb://nas.local/media/movies/Heat (1995)/Heat.mkv"))
        assertEquals(phone, Source.owning(sources, "/storage/emulated/0/Movies/Coco.mkv"))
        assertNull(Source.owning(sources, "smb://nas.local/media/music/song.mp3"))
        // A root that is the prefix of another folder's name does not own it.
        assertNull(Source.owning(sources, "smb://nas.local/media/movies-old/x.mkv"))
    }

    @Test
    fun sourcesAreSavedWithoutAnySecret() {
        val line = Source.encode(nas)
        assertEquals(nas, Source.decode(line))
        assert("password" !in line.lowercase())
    }
}
