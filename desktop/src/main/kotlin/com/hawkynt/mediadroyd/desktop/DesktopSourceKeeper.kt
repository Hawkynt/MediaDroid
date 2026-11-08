package com.hawkynt.mediadroyd.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import com.hawkynt.mediadroyd.ui.platform.LanServer
import com.hawkynt.mediadroyd.ui.platform.SourceKeeper

/**
 * The sources' keeping on the desktop: passwords with the system's keeper (DesktopSecrets: the Secret Service on
 * Linux, DPAPI on Windows), connections in [places], SMB servers found over mDNS (SmbDiscovery). The desktop keeps no
 * scan of a source beside the library, so forgetting one is letting go of its connection.
 */
class DesktopSourceKeeper(private val places: DesktopLibrary, private val secrets: DesktopSecrets) : SourceKeeper {

    override fun secretFor(sourceId: String): String? = secrets.secretFor(sourceId)

    override fun saveSecret(sourceId: String, secret: String?) =
        secrets.store(sourceId, secret?.takeIf { it.isNotEmpty() })

    override fun release(sourceId: String) = places.forget(sourceId)

    override fun forget(sourceId: String) = places.forget(sourceId)

    @Composable
    override fun smbServers(active: Boolean): State<List<LanServer>> = rememberSmbServers(active)
}
