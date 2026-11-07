package com.hawkynt.mediadroid.data

import android.content.Context
import com.hawkynt.mediadroid.library.FsFile
import com.hawkynt.mediadroid.ui.platform.MediaFiles

/** The Files browser's files on Android: any location through SourceAccess, a video's length through QualityStore. */
class AndroidMediaFiles(context: Context) : MediaFiles {
    private val context = context.applicationContext

    override fun resolve(location: String): FsFile? = SourceAccess.resolve(context, location)

    override suspend fun durationMs(video: String): Long? = QualityStore.of(context).durationMsFor(video)
}
