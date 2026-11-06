package com.hawkynt.mediadroid.data

import android.content.Context
import com.hawkynt.mediadroid.player.ModelFolder
import com.hawkynt.mediadroid.player.NeuralModels
import java.io.File

/**
 * The downloaded neural networks (see NeuralModels), one folder each in the app's own files: a file is kept only once
 * its size and digest are right, so a broken or interrupted download never counts.
 */
object ModelStore {
    /** Where [download]'s files are kept. */
    fun folder(context: Context, download: NeuralModels.Download): File =
        File(context.filesDir, "models/${download.folder}")

    /** Whether all of [download]'s files are there at their sizes (checked in full when they were fetched). */
    fun ready(context: Context, download: NeuralModels.Download): Boolean =
        ModelFolder.ready(folder(context, download), download)

    /**
     * Fetches whatever of [download] is missing, telling [progress] how far (0..1); false where a file could not be
     * fetched or did not match. Blocks; not on the main thread.
     */
    fun fetch(context: Context, download: NeuralModels.Download, progress: (Float) -> Unit): Boolean =
        ModelFolder.fetch(folder(context, download), download, progress)
}
