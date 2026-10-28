package io.github.andreacovelli.abyssh.service.network

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalNetworkAccessTest {
    private fun isLocal(literal: String) = isLocalNetworkAddress(InetAddress.getByName(literal))

    @Test
    fun privateLinkLocalAndCarrierNatAddressesAreLocal() {
        val local = listOf(
            "10.0.0.5", "172.16.1.1", "172.31.255.254", "192.168.1.26", "169.254.10.1",
            "100.64.0.1", "100.127.255.254", "224.0.0.251", "255.255.255.255",
            "fe80::1", "fd7a:115c:a1e0::1", "ff02::fb",
        )
        assertEquals(local, local.filter(::isLocal))
    }

    @Test
    fun publicAndLoopbackAddressesAreNot() {
        val remote = listOf(
            "8.8.8.8", "172.32.0.1", "100.63.255.255", "100.128.0.1", "127.0.0.1", "0.0.0.0",
            "2001:4860:4860::8888", "::1",
        )
        assertEquals(emptyList<String>(), remote.filter(::isLocal))
    }
}
