package com.hawkynt.mediadroid.data

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import com.hawkynt.mediadroid.ui.platform.LanServer
import com.hawkynt.mediadroid.ui.platform.SourceKeeper

/**
 * The sources' keeping on Android: passwords in the Keystore-backed SecretStore, connections in SourceAccess, the
 * last scans in LibraryStore, SMB servers found through Android's network service discovery.
 */
class AndroidSourceKeeper(context: Context) : SourceKeeper {
    private val context = context.applicationContext

    override fun secretFor(sourceId: String): String? = SecretStore.of(context).secretFor(sourceId)

    override fun saveSecret(sourceId: String, secret: String?) = SecretStore.of(context).save(sourceId, secret)

    override fun release(sourceId: String) = SourceAccess.of(context).forget(sourceId)

    override fun forget(sourceId: String) {
        release(sourceId)
        LibraryStore(context).forget(sourceId)
    }

    @Composable
    override fun smbServers(active: Boolean): State<List<LanServer>> = rememberSmbServers(active)
}
