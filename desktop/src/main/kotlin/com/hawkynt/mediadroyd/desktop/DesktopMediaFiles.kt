package com.hawkynt.mediadroyd.desktop

import com.hawkynt.mediadroyd.library.FsFile
import com.hawkynt.mediadroyd.library.LocalFile
import com.hawkynt.mediadroyd.ui.platform.MediaFiles
import com.hawkynt.mediadroyd.ui.platform.StorageRoot
import java.io.File

/**
 * The Files browser's files on the desktop: this computer's folders and the network sources' through [places], how
 * long a video runs as FFmpeg reads it ([probe]).
 */
class DesktopMediaFiles(private val places: DesktopLibrary, private val probe: DesktopMediaProbe? = null) : MediaFiles {

    override fun resolve(location: String): FsFile? = runCatching {
        places.remoteFile(location) ?: File(location).takeIf { it.exists() }?.let(::LocalFile)
    }.getOrNull()

    override suspend fun durationMs(video: String): Long? = probe?.durationMs(video)

    companion object {
        /**
         * Where the Files browser starts on this computer: the media folder, the home folder, every drive or the
         * file system's root, then each network source.
         */
        fun roots(store: DesktopStore): List<StorageRoot> {
            val media = store.libraryFolder?.let { StorageRoot("Media folder", it, removable = false) }
            val home = System.getProperty("user.home")?.let { StorageRoot("Home", it, removable = false) }
            val drives = File.listRoots().orEmpty().map { StorageRoot(it.path, it.path, removable = false) }
            val network = store.sources.filter { it.kind != com.hawkynt.mediadroyd.library.SourceKind.LOCAL }
                .map { StorageRoot(it.name, it.rootUri, removable = false) }
            val local = store.sources.filter { it.kind == com.hawkynt.mediadroyd.library.SourceKind.LOCAL }
                .map { StorageRoot(it.name, it.path, removable = false) }
            return (listOfNotNull(media, home) + local + drives + network).distinctBy { it.path }
        }
    }
}
