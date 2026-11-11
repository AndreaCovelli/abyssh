package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.model.AuthMethod
import io.github.andreacovelli.abyssh.model.MultiplexerType
import io.github.andreacovelli.abyssh.model.Transport
import java.io.Closeable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IdleSshLoginsTest {
    private class FakeLogin : Closeable {
        var closed = false

        override fun close() {
            closed = true
        }
    }

    private val scope = TestScope()
    private val logins = IdleSshLogins<FakeLogin>(ttlMs = 1_000, scope = scope)
    private val spec = TabSpec(host = "example.com", username = "me", password = "secret")

    @Test
    fun handsAParkedLoginToOneTaker() {
        val login = FakeLogin()
        logins.park(spec, login)

        assertSame(login, logins.take(spec))
        assertNull(logins.take(spec))
        assertFalse(login.closed)
    }

    @Test
    fun closesALoginNobodyTakesInTime() {
        val login = FakeLogin()
        logins.park(spec, login)

        scope.advanceTimeBy(999)
        scope.runCurrent()
        assertFalse(login.closed)
        scope.advanceTimeBy(1)
        scope.runCurrent()
        assertTrue(login.closed)
        assertNull(logins.take(spec))
    }

    @Test
    fun aTakenLoginOutlivesItsExpiry() {
        val login = FakeLogin()
        logins.park(spec, login)
        logins.take(spec)

        scope.advanceTimeBy(2_000)
        scope.runCurrent()
        assertFalse(login.closed)
    }

    @Test
    fun parkingAgainReplacesAndClosesTheOlderLogin() {
        val older = FakeLogin()
        val newer = FakeLogin()
        logins.park(spec, older)
        scope.advanceTimeBy(600)
        logins.park(spec, newer)

        assertTrue(older.closed)
        // The newer login's own time runs from when it was parked.
        scope.advanceTimeBy(600)
        scope.runCurrent()
        assertFalse(newer.closed)
        assertSame(newer, logins.take(spec))
    }

    @Test
    fun keepsLoginsForOtherHostsUsersAndCredentialsApart() {
        logins.park(spec, FakeLogin())

        assertNull(logins.take(spec.copy(host = "other.example.com")))
        assertNull(logins.take(spec.copy(port = 2222)))
        assertNull(logins.take(spec.copy(username = "root")))
        assertNull(logins.take(spec.copy(password = "other")))
        assertNull(logins.take(spec.copy(authMethod = AuthMethod.Key)))
        assertNull(logins.take(spec.copy(transport = Transport.TailscaleSSH)))
        // What the tab shows doesn't change how it logs in.
        val tab = spec.copy(displayName = "renamed", multiplexer = MultiplexerType.Tmux, multiplexerSessionName = "main")
        assertTrue(logins.take(tab) != null)
    }

    @Test
    fun closeAllClosesEveryLogin() {
        val first = FakeLogin()
        val second = FakeLogin()
        logins.park(spec, first)
        logins.park(spec.copy(host = "other.example.com"), second)

        logins.closeAll()
        scope.runCurrent()

        assertTrue(first.closed)
        assertTrue(second.closed)
        assertNull(logins.take(spec))
    }
}
