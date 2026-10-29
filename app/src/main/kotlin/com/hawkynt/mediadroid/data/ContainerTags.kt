package com.hawkynt.mediadroid.data

import android.content.Context
import com.hawkynt.mediadroid.library.ContainerTitle
import com.hawkynt.mediadroid.library.LocalFile
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The title and description videos carry inside their containers, for the Files browser's rows and
 * their About sheet: read when a row without an NFO first shows, kept for the session. Reading is
 * lazy as it reads the start of each file, which over a network share is not free.
 */
object ContainerTags {
    private val known = ConcurrentHashMap<String, ContainerTitle.Tags>()

    /** What the video at [location] says about itself, read once. */
    suspend fun of(context: Context, location: String): ContainerTitle.Tags = known[location]
        ?: withContext(Dispatchers.IO) {
            val file = if (location.startsWith("/")) {
                LocalFile(File(location))
            } else {
                SourceAccess.resolve(context, location)
            }
            (file?.let(ContainerTitle::tags) ?: ContainerTitle.Tags(null, null)).also { known[location] = it }
        }

    /** What is known so far about the video at [location], without reading it. */
    fun peek(location: String): ContainerTitle.Tags? = known[location]
}
