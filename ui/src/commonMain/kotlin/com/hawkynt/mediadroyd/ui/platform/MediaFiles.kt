package com.hawkynt.mediadroyd.ui.platform

import androidx.compose.runtime.staticCompositionLocalOf
import com.hawkynt.mediadroyd.library.ContainerTitle
import com.hawkynt.mediadroyd.library.FsFile
import com.hawkynt.mediadroyd.library.LocalFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** A storage volume or source the Files browser starts from: its [label], its [path] and whether it can be removed. */
data class StorageRoot(val label: String, val path: String, val removable: Boolean)

/**
 * What the Files browser needs of the platform: the folder or file behind any location — this device's storage, a
 * share, a WebDAV or cloud folder — and how long a video runs.
 */
interface MediaFiles {
    /** The folder or file at [location] on whichever source holds it; null where it cannot be reached. Blocks. */
    fun resolve(location: String): FsFile? = File(location).takeIf { it.exists() }?.let(::LocalFile)

    /** How long [video] runs, probed once and cached; null before, or where it cannot be told. */
    suspend fun durationMs(video: String): Long? = null

    /** This device's own files only. */
    object None : MediaFiles
}

/** The platform's [MediaFiles]; each app provides its own at the root of its screens. */
val LocalMediaFiles = staticCompositionLocalOf<MediaFiles> { MediaFiles.None }

/**
 * The title and description videos carry inside their containers, for the Files browser's rows and
 * their About sheet: read when a row without an NFO first shows, kept for the session. Reading is
 * lazy as it reads the start of each file, which over a network share is not free.
 */
object ContainerTags {
    private val known = ConcurrentHashMap<String, ContainerTitle.Tags>()

    /** What the video at [location] says about itself, read once through [files]. */
    suspend fun of(files: MediaFiles, location: String): ContainerTitle.Tags = known[location]
        ?: withContext(Dispatchers.IO) {
            val file = if (location.startsWith("/")) LocalFile(File(location)) else files.resolve(location)
            (file?.let(ContainerTitle::tags) ?: ContainerTitle.Tags(null, null)).also { known[location] = it }
        }

    /** What is known so far about the video at [location], without reading it. */
    fun peek(location: String): ContainerTitle.Tags? = known[location]
}
