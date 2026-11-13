package io.github.andreacovelli.abyssh.ui.screens.addserver

import io.github.andreacovelli.abyssh.data.db.SavedSecrets
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
    fun `switching the transport keeps the chosen multiplexer`() {
        val tmux = AddServerForm().withMultiplexer(MultiplexerType.Tmux)

        val tailscale = tmux.withTransport(Transport.TailscaleSSH)
        assertEquals(MultiplexerType.Tmux, tailscale.effectiveMultiplexer)
        assertEquals(MultiplexerType.Tmux, tailscale.withTransport(Transport.SSH).effectiveMultiplexer)
    }

    @Test
    fun `turning the multiplexer off clears it`() {
        val form =
            AddServerForm()
                .withMultiplexer(MultiplexerType.Tmux)
                .withMultiplexer(null)
                .withTransport(Transport.TailscaleSSH)

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
    fun `a blank port means 22 and an out of range one is invalid`() {
        assertEquals(22, AddServerForm(port = "").portNumber)
        assertEquals(2222, AddServerForm(port = "2222").portNumber)
        assertEquals(65535, AddServerForm(port = "65535").portNumber)
        assertNull(AddServerForm(port = "0").portNumber)
        assertNull(AddServerForm(port = "65536").portNumber)

        val filled = AddServerForm(name = "box", host = "example.org", username = "me", password = "pw")
        assertFalse(filled.copy(port = "70000").canSave())
    }

    @Test
    fun `an unreadable secret is reported until it is replaced`() {
        val form = AddServerForm(unreadableSecrets = SavedSecrets(password = true, keyPassphrase = true))
        assertEquals(SavedSecrets(password = true, keyPassphrase = true), form.unreadableNotices)
        // Key auth doesn't show the password field, and the password is still reported.
        assertTrue(form.copy(authMethod = AuthMethod.Key).unreadableNotices.password)

        val typed = form.copy(password = "new")
        assertEquals(SavedSecrets(keyPassphrase = true), typed.unreadableNotices)
        // Deleting what was typed goes back to keeping the stored value.
        assertEquals(form.unreadableNotices, typed.copy(password = "").unreadableNotices)
    }

    @Test
    fun `clearing one unreadable secret adds to those already cleared`() {
        val form = AddServerForm(unreadableSecrets = SavedSecrets(password = true, keyPassphrase = true))
        val cleared = form.withClearedSecrets(SavedSecrets(password = true))
        assertEquals(SavedSecrets(password = true), cleared.clearedSecrets)
        assertEquals(SavedSecrets(keyPassphrase = true), cleared.unreadableNotices)

        val both = cleared.withClearedSecrets(SavedSecrets(keyPassphrase = true))
        assertEquals(SavedSecrets(password = true, keyPassphrase = true), both.clearedSecrets)
        assertEquals(SavedSecrets(), both.unreadableNotices)
    }
}
