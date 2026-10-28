package com.hawkynt.mediadroid.data

import android.content.Context
import com.hawkynt.mediadroid.library.FsFile
import com.hawkynt.mediadroid.library.Source
import com.hawkynt.mediadroid.library.SourceKind
import com.hawkynt.mediadroid.sources.SourceFiles

/**
 * The one [SourceFiles] of the app, so the scanner, the player and the pictures share connections,
 * and the saved sources it resolves stored locations against.
 */
object SourceAccess {
    @Volatile
    private var files: SourceFiles? = null

    /** The saved sources; the app points this at its [StateStore]. */
    @Volatile
    var sources: () -> List<Source> = { emptyList() }

    fun of(context: Context): SourceFiles =
        files ?: synchronized(this) {
            files ?: run {
                val resolver = context.applicationContext.contentResolver
                // Document providers are Android's own; the plain-JVM SourceFiles gets them from here.
                SourceFiles(SecretStore.of(context)) { source ->
                    if (source.kind == SourceKind.DOCUMENTS) DocumentTreeFs(resolver, source) else null
                }.also { files = it }
            }
        }

    /** The file behind a stored [location], wherever it lives; null when its source is not reachable. */
    fun resolve(context: Context, location: String): FsFile? =
        runCatching { of(context).resolve(sources(), location) }.getOrNull()
}
