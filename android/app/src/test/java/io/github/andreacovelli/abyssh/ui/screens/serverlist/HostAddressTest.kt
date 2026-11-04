package io.github.andreacovelli.abyssh.ui.screens.serverlist

import io.github.andreacovelli.abyssh.model.HostProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class HostAddressTest {

    @Test
    fun `an IPv6 host is shown in brackets`() {
        assertEquals("me@example.org:22", HostProfile(name = "a", host = "example.org", username = "me").address())
        assertEquals("me@[::1]:2222", HostProfile(name = "a", host = "::1", port = 2222, username = "me").address())
        assertEquals("me@[fe80::1]:22", HostProfile(name = "a", host = "[fe80::1]", username = "me").address())
    }
}
