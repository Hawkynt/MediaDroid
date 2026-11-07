package com.hawkynt.mediadroid.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/** An SMB server announced on the local network: its name, and the address to connect to. */
data class LanServer(val name: String, val host: String)

/**
 * What the sources' screens need of the platform beside the household's memory: where a source's password is kept
 * (the system's own keeper, never the state file), what is let go of when a source changes (its connections) or goes
 * (its last scan too), and the SMB servers announcing themselves on the local network.
 */
interface SourceKeeper {
    /** The password kept for [sourceId], or null. */
    fun secretFor(sourceId: String): String?

    /** Keeps [secret] for [sourceId]; null or empty forgets it. */
    fun saveSecret(sourceId: String, secret: String?)

    /** Lets go of the connections open to [sourceId]: it changed. */
    fun release(sourceId: String)

    /** Lets go of everything kept for [sourceId] — its connections and its last scan: it is gone. */
    fun forget(sourceId: String)

    /** The SMB servers announcing themselves on the local network (mDNS, `_smb._tcp`) while [active]. */
    @Composable
    fun smbServers(active: Boolean): State<List<LanServer>>

    /** Keeps nothing and finds nothing. */
    object None : SourceKeeper {
        override fun secretFor(sourceId: String): String? = null

        override fun saveSecret(sourceId: String, secret: String?) = Unit

        override fun release(sourceId: String) = Unit

        override fun forget(sourceId: String) = Unit

        @Composable
        override fun smbServers(active: Boolean): State<List<LanServer>> = remember { mutableStateOf(emptyList()) }
    }
}

/** The platform's [SourceKeeper]; each app provides its own at the root of its screens. */
val LocalSourceKeeper = staticCompositionLocalOf<SourceKeeper> { SourceKeeper.None }
