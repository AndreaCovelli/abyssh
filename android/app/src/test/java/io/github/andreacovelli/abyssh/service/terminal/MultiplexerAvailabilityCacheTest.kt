package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.model.MultiplexerType
import io.github.andreacovelli.abyssh.model.Transport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiplexerAvailabilityCacheTest {
    private var now = 0L
    private val cache = MultiplexerAvailabilityCache(clock = { now }, ttlMs = 1_000)
    private val spec = TabSpec(host = "example.com", username = "me")

    @Test
    fun remembersAFoundMultiplexerUntilItExpires() {
        assertFalse(cache.isAvailable(spec, MultiplexerType.Tmux))
        cache.recordAvailable(spec, MultiplexerType.Tmux)

        now = 999
        assertTrue(cache.isAvailable(spec, MultiplexerType.Tmux))
        now = 1_000
        assertFalse(cache.isAvailable(spec, MultiplexerType.Tmux))
    }

    @Test
    fun keepsHostsUsersTransportsAndMultiplexersApart() {
        cache.recordAvailable(spec, MultiplexerType.Tmux)

        assertFalse(cache.isAvailable(spec, MultiplexerType.Zmx))
        assertFalse(cache.isAvailable(spec.copy(host = "other.example.com"), MultiplexerType.Tmux))
        assertFalse(cache.isAvailable(spec.copy(port = 2222), MultiplexerType.Tmux))
        assertFalse(cache.isAvailable(spec.copy(username = "root"), MultiplexerType.Tmux))
        assertFalse(cache.isAvailable(spec.copy(transport = Transport.TailscaleSSH), MultiplexerType.Tmux))
        assertTrue(cache.isAvailable(spec.copy(displayName = "renamed"), MultiplexerType.Tmux))
    }
}
