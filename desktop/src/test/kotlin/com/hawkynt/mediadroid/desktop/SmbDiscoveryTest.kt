package com.hawkynt.mediadroid.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmbDiscoveryTest {

    @Test
    fun `an announced server is reached by its first IPv4 address`() {
        assertEquals(
            LanServer("Living room NAS", "192.168.1.10"),
            LanServers.of("Living room NAS", listOf("192.168.1.10", "10.0.0.2"), listOf("fe80::1"), "nas.local."),
        )
    }

    @Test
    fun `without an IPv4 address the server's mDNS name is used, never a bare IPv6 address`() {
        assertEquals(LanServer("NAS", "nas.local"), LanServers.of("NAS", emptyList(), listOf("fe80::1"), "nas.local."))
        assertNull(LanServers.of("NAS", emptyList(), listOf("fe80::1"), null))
        assertNull(LanServers.of("NAS", emptyList(), emptyList(), ""))
    }

    @Test
    fun `the list holds each server once, sorted by name, and loses what goes away`() {
        var list = emptyList<LanServer>()
        list = LanServers.found(list, LanServer("zeta", "192.168.1.3"))
        list = LanServers.found(list, LanServer("Alpha", "192.168.1.1"))
        list = LanServers.found(list, LanServer("zeta", "192.168.1.4"))
        assertEquals(listOf(LanServer("Alpha", "192.168.1.1"), LanServer("zeta", "192.168.1.4")), list)
        assertEquals(listOf(LanServer("zeta", "192.168.1.4")), LanServers.lost(list, "Alpha"))
    }

    @Test
    fun `only usable interfaces are listened on`() {
        assertEquals(true, LanServers.listensOn(up = true, loopback = false, virtual = false, multicast = true))
        assertEquals(false, LanServers.listensOn(up = false, loopback = false, virtual = false, multicast = true))
        assertEquals(false, LanServers.listensOn(up = true, loopback = true, virtual = false, multicast = true))
        assertEquals(false, LanServers.listensOn(up = true, loopback = false, virtual = false, multicast = false))
        assertEquals(false, LanServers.listensOn(up = true, loopback = false, virtual = true, multicast = true))
    }
}
