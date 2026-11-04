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

    @Test
    fun readsAConnectLineAmongOtherOutput() {
        val noisy = "Warning: locale not set\nMOSH CONNECT 60002 ab+/cd-_efGHIJKLMNOPqr \nbye"
        assertEquals(
            MoshBootstrapParser.ParseResult.Success(MoshBootstrapParser.MoshEndpoint("h", 60002, "ab+/cd-_efGHIJKLMNOPqr")),
            MoshBootstrapParser.parse("h", noisy),
        )
    }

    @Test
    fun takesTheFirstConnectLine() {
        val twice = "MOSH CONNECT 60001 AAAAAAAAAAAAAAAAAAAAAA\nMOSH CONNECT 60002 BBBBBBBBBBBBBBBBBBBBBB\n"
        val result = MoshBootstrapParser.parse("h", twice) as MoshBootstrapParser.ParseResult.Success
        assertEquals(60001, result.endpoint.port)
    }

    @Test
    fun rejectsPortsOutsideTheUdpRange() {
        assertError("Port out of range: 0", "MOSH CONNECT 0 AAAAAAAAAAAAAAAAAAAAAA")
        assertError("Port out of range: 65536", "MOSH CONNECT 65536 AAAAAAAAAAAAAAAAAAAAAA")
        assertError("Invalid port: '99999999999'", "MOSH CONNECT 99999999999 AAAAAAAAAAAAAAAAAAAAAA")
    }

    @Test
    fun skipsAnInvalidConnectLineForALaterValidOne() {
        val twice = "MOSH CONNECT 0 AAAAAAAAAAAAAAAAAAAAAA\nMOSH CONNECT 60002 BBBBBBBBBBBBBBBBBBBBBB\n"
        val result = MoshBootstrapParser.parse("h", twice) as MoshBootstrapParser.ParseResult.Success
        assertEquals(60002, result.endpoint.port)
    }

    @Test
    fun reportsTheFirstErrorWhenNoConnectLineIsValid() {
        assertError("Port out of range: 0", "MOSH CONNECT 0 AAAAAAAAAAAAAAAAAAAAAA\nMOSH CONNECT 60001 short\n")
    }

    @Test
    fun rejectsAKeyOfTheWrongLength() {
        assertError("Key length invalid: 8", "MOSH CONNECT 60001 tooShort")
        assertError("Key length invalid: 23", "MOSH CONNECT 60001 AAAAAAAAAAAAAAAAAAAAAAB")
    }

    @Test
    fun rejectsAKeyWithOtherCharacters() {
        assertError("Key has invalid characters", "MOSH CONNECT 60001 AAAAAAAAAAAAAAAAAAAAA!")
    }

    @Test
    fun explainsWhyThereIsNoConnectLine() {
        assertError("mosh-server not found on remote host", "bash: mosh-server: command not found\n")
        assertError("No MOSH bootstrap output received", " \r\n ")
        assertError("Server output missing valid MOSH CONNECT line", "Permission denied\n")
    }

    private fun assertError(reason: String, output: String) {
        assertEquals(MoshBootstrapParser.ParseResult.Error(reason), MoshBootstrapParser.parse("h", output))
    }
}
