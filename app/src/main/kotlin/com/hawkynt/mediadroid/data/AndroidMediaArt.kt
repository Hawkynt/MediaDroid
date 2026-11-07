package com.hawkynt.mediadroid.data

import android.content.Context
import com.hawkynt.mediadroid.nfo.StreamDetails
import com.hawkynt.mediadroid.ui.platform.MediaArt
import java.io.File

/** The screens' pictures and facts on Android: network art through ArtCache, stills and probes from the device. */
class AndroidMediaArt(context: Context) : MediaArt {
    private val context = context.applicationContext

    override suspend fun picture(location: String): File? = ArtCache.fileFor(context, location)

    override suspend fun still(video: String): File? = ThumbnailStore.of(context).thumbnailFor(video)

    override suspend fun streamDetails(video: String): StreamDetails? = QualityStore.of(context).detailsFor(video)
}
