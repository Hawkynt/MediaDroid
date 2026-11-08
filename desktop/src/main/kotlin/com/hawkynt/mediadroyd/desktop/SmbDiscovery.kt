package com.hawkynt.mediadroyd.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.hawkynt.mediadroyd.ui.platform.LanServer
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

/** What an mDNS answer becomes, and how the list of servers on the network changes: pure, for the tests. */
object LanServers {

    /**
     * The server [name] announced with [ipv4] and [ipv6] addresses and its mDNS host name [server] (`nas.local.`):
     * reached by its first IPv4 address, else by its host name (Windows 10 and Linux with nss-mdns resolve `.local`);
     * a bare IPv6 address is never offered, as a share's address cannot carry one cleanly. Null when there is no way.
     */
    fun of(name: String, ipv4: List<String>, ipv6: List<String>, server: String?): LanServer? {
        val host = ipv4.firstOrNull() ?: server?.trimEnd('.')?.takeIf { it.isNotEmpty() }
        return host?.let { LanServer(name, it) }
    }

    /** [list] with [server] in it once, replacing an earlier answer under its name, sorted by name. */
    fun found(list: List<LanServer>, server: LanServer): List<LanServer> =
        (list.filter { it.name != server.name } + server).sortedBy { it.name.lowercase() }

    /** [list] without the server called [name]. */
    fun lost(list: List<LanServer>, name: String): List<LanServer> = list.filter { it.name != name }

    /** Whether an interface is worth listening on: up, a real one, not the loopback, able to multicast. */
    fun listensOn(up: Boolean, loopback: Boolean, virtual: Boolean, multicast: Boolean): Boolean =
        up && !loopback && !virtual && multicast
}

/**
 * The SMB servers announcing themselves on the local network over mDNS (`_smb._tcp`) — Samba with Avahi, macOS, most
 * NAS boxes — through JmDNS, the same on Windows and Linux: one listener per IPv4 address of every usable interface,
 * each server resolved to an address as it is found. [onChange] gets the whole list, on JmDNS's threads.
 */
class SmbDiscovery(private val onChange: (List<LanServer>) -> Unit) : AutoCloseable {

    private val lock = Any()
    private var servers = emptyList<LanServer>()
    private val listeners = mutableListOf<JmDNS>()

    @Volatile private var closed = false

    private val listener = object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) = event.dns.requestServiceInfo(event.type, event.name)

        override fun serviceRemoved(event: ServiceEvent) = update { LanServers.lost(it, event.name) }

        override fun serviceResolved(event: ServiceEvent) {
            val info = event.info
            val server = LanServers.of(
                event.name,
                info.inet4Addresses.map { it.hostAddress },
                info.inet6Addresses.map { it.hostAddress },
                info.server,
            ) ?: return
            update { LanServers.found(it, server) }
        }
    }

    init {
        // Starting JmDNS takes a moment per interface: never on the window's thread.
        Thread({
            addresses().forEach { address ->
                if (closed) return@forEach
                runCatching { JmDNS.create(address, "mediadroyd") }.getOrNull()?.let { dns ->
                    synchronized(lock) { listeners += dns }
                    dns.addServiceListener(SMB_SERVICE, listener)
                }
            }
            if (closed) stop()
        }, "mediadroyd-mdns").apply { isDaemon = true }.start()
    }

    private fun update(how: (List<LanServer>) -> List<LanServer>) {
        val now = synchronized(lock) {
            servers = how(servers)
            servers
        }
        if (!closed) onChange(now)
    }

    override fun close() {
        closed = true
        // JmDNS takes its time to close too.
        Thread(::stop, "mediadroyd-mdns-close").apply { isDaemon = true }.start()
    }

    private fun stop() {
        val all = synchronized(lock) { listeners.toList().also { listeners.clear() } }
        all.forEach { runCatching { it.close() } }
    }

    private fun addresses(): List<Inet4Address> = runCatching {
        NetworkInterface.networkInterfaces().toList().filter {
            runCatching { LanServers.listensOn(it.isUp, it.isLoopback, it.isVirtual, it.supportsMulticast()) }
                .getOrDefault(false)
        }.flatMap { it.inetAddresses().toList() }.filterIsInstance<Inet4Address>()
    }.getOrDefault(emptyList())

    private companion object {
        const val SMB_SERVICE = "_smb._tcp.local."
    }
}

/** The SMB servers on the local network while [active], as SmbDiscovery finds them. */
@Composable
fun rememberSmbServers(active: Boolean): State<List<LanServer>> {
    val found = remember { mutableStateOf(emptyList<LanServer>()) }
    DisposableEffect(active) {
        val discovery = if (active) SmbDiscovery { found.value = it } else null
        onDispose { discovery?.close() }
    }
    return found
}
