package com.hawkynt.mediadroyd.ui.platform

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.hawkynt.mediadroyd.state.StateFile
import com.hawkynt.mediadroyd.state.StateStore
import com.hawkynt.mediadroyd.state.StateWatcher

/**
 * The household's memory kept in [file], watched by the screens: a screen that reads [StateStore.data] is drawn again
 * when it is saved, on every platform.
 */
fun observedStateStore(file: StateFile): StateStore = StateStore(file, ComposeWatcher())

/** Bumps a snapshot state on every save; reading the memory reads it, which subscribes the reader. */
private class ComposeWatcher : StateWatcher {
    private var revision by mutableIntStateOf(0)

    override fun read() {
        revision
    }

    override fun changed() {
        revision++
    }
}
