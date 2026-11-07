package com.hawkynt.mediadroid.data

import android.content.Context
import com.hawkynt.mediadroid.state.BUILT_IN_SCREEN
import com.hawkynt.mediadroid.state.StateFile
import com.hawkynt.mediadroid.state.StateStore
import com.hawkynt.mediadroid.ui.platform.observedStateStore
import java.io.File

/**
 * The household's memory (StateStore, the model every platform keeps) in the app's private storage: `state.json`,
 * survives app updates.
 */
fun stateStoreOf(context: Context): StateStore {
    val file = File(context.filesDir, "state.json")
    return observedStateStore(object : StateFile {
        override fun read(): String? = file.takeIf { it.exists() }?.readText()

        override fun write(text: String) = file.writeText(text)
    })
}

/**
 * The key an output screen's setup is kept under: the device's own screen as one, every other by its name — the TV
 * or projector it is — so each keeps its own lens and projector setup.
 */
fun screenKey(display: android.view.Display?): String =
    if (display == null || display.displayId == android.view.Display.DEFAULT_DISPLAY) BUILT_IN_SCREEN
    else "display:${display.name}"
