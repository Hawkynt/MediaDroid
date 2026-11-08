package com.hawkynt.mediadroyd.engine.desktop

import com.hawkynt.mediadroyd.player.ModelFolder
import com.hawkynt.mediadroyd.player.NeuralModels
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The networks the desktop carries are the ones NeuralModels names, file for file, digest for digest. */
class CarriedModelsTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `every carried network unpacks into the models folder, every file matching its digest`() {
        val neural = DesktopNeural(folder.root)
        for (download in DesktopNeural.CARRIED) {
            assertTrue(download.folder, neural.ready(download))
            assertTrue(ModelFolder.ready(neural.folder(download), download))
        }
    }

    @Test
    fun `a network to fetch is not ready before it is fetched`() {
        assertTrue(!DesktopNeural(folder.root).ready(NeuralModels.DAIN))
    }
}
