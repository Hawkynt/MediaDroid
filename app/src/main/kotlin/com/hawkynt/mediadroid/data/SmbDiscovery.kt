package com.hawkynt.mediadroid.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.concurrent.Executors

/** An SMB server announced on the local network: its name, and the address to connect to. */
data class LanServer(val name: String, val host: String)

/**
 * The SMB servers announcing themselves on the local network over mDNS (`_smb._tcp`) — Samba with
 * Avahi, macOS, most NAS boxes — while [active]. Each is resolved to an address as it is found.
 */
@Composable
fun rememberSmbServers(active: Boolean): State<List<LanServer>> {
    val context = LocalContext.current
    val found = remember { mutableStateOf(emptyList<LanServer>()) }
    DisposableEffect(active) {
        if (!active) return@DisposableEffect onDispose { }
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(service: NsdServiceInfo) = resolve(nsd, service) { server ->
                found.value = (found.value.filter { it.name != server.name } + server).sortedBy { it.name.lowercase() }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                found.value = found.value.filter { it.name != service.serviceName }
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        val started = runCatching { nsd.discoverServices(SMB_SERVICE, NsdManager.PROTOCOL_DNS_SD, listener) }.isSuccess
        onDispose { if (started) runCatching { nsd.stopServiceDiscovery(listener) } }
    }
    return found
}

private const val SMB_SERVICE = "_smb._tcp"

private val resolving = Executors.newSingleThreadExecutor()

/** Resolves [service] to an address; Android 14 has a callback for it, older ones resolve once. */
private fun resolve(nsd: NsdManager, service: NsdServiceInfo, onFound: (LanServer) -> Unit) {
    fun found(info: NsdServiceInfo) {
        val host = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.hostAddresses.firstOrNull()?.hostAddress
        } else {
            @Suppress("DEPRECATION")
            info.host?.hostAddress
        }
        if (host != null) onFound(LanServer(service.serviceName, host))
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        nsd.registerServiceInfoCallback(service, resolving, object : NsdManager.ServiceInfoCallback {
            override fun onServiceUpdated(info: NsdServiceInfo) {
                found(info)
                runCatching { nsd.unregisterServiceInfoCallback(this) }
            }

            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit

            override fun onServiceLost() = Unit

            override fun onServiceInfoCallbackUnregistered() = Unit
        })
    } else {
        @Suppress("DEPRECATION")
        nsd.resolveService(service, object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) = found(info)

            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        })
    }
}
