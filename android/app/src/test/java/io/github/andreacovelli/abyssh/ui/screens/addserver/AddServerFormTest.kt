package io.github.andreacovelli.abyssh.ui.screens.addserver

import io.github.andreacovelli.abyssh.model.AuthMethod
import io.github.andreacovelli.abyssh.model.MultiplexerType
import io.github.andreacovelli.abyssh.model.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddServerFormTest {

    @Test
    fun `switching to mosh and back restores the chosen multiplexer`() {
        val tmux = AddServerForm().withMultiplexer(MultiplexerType.Tmux)

        val mosh = tmux.withTransport(Transport.Mosh)
        assertNull(mosh.effectiveMultiplexer)

        assertEquals(MultiplexerType.Tmux, mosh.withTransport(Transport.SSH).effectiveMultiplexer)
    }

    @Test
    fun `selecting off under mosh keeps the earlier choice`() {
        val form =
            AddServerForm()
                .withMultiplexer(MultiplexerType.Zmx)
                .withTransport(Transport.Mosh)
                .withMultiplexer(null)
                .withTransport(Transport.TailscaleSSH)

        assertEquals(MultiplexerType.Zmx, form.effectiveMultiplexer)
    }

    @Test
    fun `turning the multiplexer off over ssh clears it`() {
        val form =
            AddServerForm()
                .withMultiplexer(MultiplexerType.Tmux)
                .withMultiplexer(null)
                .withTransport(Transport.Mosh)
                .withTransport(Transport.SSH)

        assertNull(form.effectiveMultiplexer)
    }

    @Test
    fun `an unsupported multiplexer is never chosen`() {
        assertNull(AddServerForm().withMultiplexer(MultiplexerType.Zellij).effectiveMultiplexer)
        assertNull(AddServerForm(multiplexer = MultiplexerType.Zellij).effectiveMultiplexer)
    }

    @Test
    fun `leaving tailscale ssh without credentials falls back to a password`() {
        val form = AddServerForm(transport = Transport.TailscaleSSH, authMethod = AuthMethod.None)

        assertEquals(AuthMethod.Password, form.withTransport(Transport.SSH).authMethod)
        assertEquals(AuthMethod.Key, form.copy(authMethod = AuthMethod.Key).withTransport(Transport.SSH).authMethod)
    }

    @Test
    fun `the keepalive interval defaults when blank and stops at a day`() {
        assertEquals(30, AddServerForm(keepaliveInterval = "").keepaliveIntervalSeconds)
        assertEquals(0, AddServerForm(keepaliveInterval = "0").keepaliveIntervalSeconds)
        assertEquals(15, AddServerForm(keepaliveInterval = "15").keepaliveIntervalSeconds)
        assertEquals(86_400, AddServerForm(keepaliveInterval = "99999").keepaliveIntervalSeconds)
    }

    @Test
    fun `only ssh transports show the keepalive`() {
        assertTrue(AddServerForm().withTransport(Transport.SSH).usesKeepalive)
        assertTrue(AddServerForm().withTransport(Transport.TailscaleSSH).usesKeepalive)
        assertFalse(AddServerForm().withTransport(Transport.Mosh).usesKeepalive)
    }
}
