package io.github.andreacovelli.abyssh.service.mosh

import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoshEndpointResolverTest {
    @Test
    fun preservesNumericIpv4Literal() {
        assertEquals("100.72.23.13", MoshEndpointResolver.resolve("100.72.23.13"))
    }

    @Test
    fun resolvesDnsNameToIpv4Literal() {
        val address = InetAddress.getByName("100.72.23.13")

        val result =
            MoshEndpointResolver.resolve("solom-vps.tail36346.ts.net") { arrayOf(address) }

        assertEquals("100.72.23.13", result)
    }

    @Test
    fun preservesIpv6AsAnUnbracketedConfigLiteral() {
        val address = InetAddress.getByName("2001:db8::13")

        val result = MoshEndpointResolver.resolve("vps.example") { arrayOf(address) }

        assertEquals(address.hostAddress, result)
        assertTrue(!result.startsWith("["))
        assertTrue(!result.endsWith("]"))
    }

    @Test
    fun prefersTheAddressSshConnectedTo() {
        val result =
            MoshEndpointResolver.resolve("vps.example", connectedAddress = "2001:db8::7") {
                throw AssertionError("DNS must not be queried when SSH reported its peer")
            }

        assertEquals("2001:db8::7", result)
    }

    @Test
    fun fallsBackToDnsWithoutAConnectedAddress() {
        val address = InetAddress.getByName("192.0.2.7")

        val result = MoshEndpointResolver.resolve("vps.example", connectedAddress = " ") { arrayOf(address) }

        assertEquals("192.0.2.7", result)
    }

    @Test
    fun keepsALinkLocalScopeAsAnInterfaceIndex() {
        val address = Inet6Address.getByAddress(null, InetAddress.getByName("fe80::1").address, 3)

        val result = MoshEndpointResolver.resolve("printer.local") { arrayOf(address) }

        assertEquals("fe80:0:0:0:0:0:0:1%3", result)
    }

    @Test
    fun namesHostWhenResolutionFails() {
        val error =
            try {
                MoshEndpointResolver.resolve("missing.example") {
                    throw UnknownHostException("missing.example")
                }
                throw AssertionError("Expected resolution to fail")
            } catch (error: IllegalStateException) {
                error
            }

        assertTrue(error.message.orEmpty().contains("missing.example"))
    }
}
