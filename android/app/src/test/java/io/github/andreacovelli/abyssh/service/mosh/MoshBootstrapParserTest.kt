package io.github.andreacovelli.abyssh.service.mosh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoshBootstrapParserTest {
    // What `mosh-server new` prints, stdout and stderr together.
    private val output =
        "\r\nMOSH CONNECT 60001 AbCdEfGhIjKlMnOpQrStUv\r\n\r\n" +
            "mosh-server (mosh 1.4.0) [build mosh 1.4.0]\r\n" +
            "Copyright 2012 Keith Winstein <mosh-devel@mit.edu>\r\n\r\n" +
            "[mosh-server detached, pid = 31337]\r\n"

    @Test
    fun readsTheServerPid() {
        assertEquals(31337, MoshBootstrapParser.serverPid(output))
    }

    @Test
    fun hasNoPidBeforeTheServerDetaches() {
        assertNull(MoshBootstrapParser.serverPid(output.substringBefore("[mosh-server")))
        assertNull(MoshBootstrapParser.serverPid("[mosh-server detached, pid = 0]"))
        assertNull(MoshBootstrapParser.serverPid("[mosh-server detached, pid = 99999999999]"))
    }

    @Test
    fun readsTheEndpointBeforeThePid() {
        assertEquals(
            MoshBootstrapParser.ParseResult.Success(MoshBootstrapParser.MoshEndpoint("192.0.2.7", 60001, "AbCdEfGhIjKlMnOpQrStUv")),
            MoshBootstrapParser.parse("192.0.2.7", output),
        )
    }
}
