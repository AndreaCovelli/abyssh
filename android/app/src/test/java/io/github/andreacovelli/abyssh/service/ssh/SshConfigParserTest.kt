package io.github.andreacovelli.abyssh.service.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SshConfigParserTest {
    @Test
    fun `reads a host block`() {
        val config = SshConfigParser.parse(
            """
            # My server
            Host web
                HostName example.org
                Port 2222
                User deploy
                IdentityFile ~/.ssh/id_ed25519
            """.trimIndent(),
        )
        assertEquals(listOf(SshConfigHost("web", "example.org", 2222, "deploy", true)), config.hosts)
        assertTrue(config.skipped.isEmpty())
        assertTrue(config.ignoredOptions.isEmpty())
    }

    @Test
    fun `an alias with no HostName is its own address`() {
        val host = SshConfigParser.parse("Host example.org").hosts.single()
        assertEquals("example.org", host.hostName)
        assertEquals(22, host.port)
        assertNull(host.user)
        assertFalse(host.hasIdentityFile)
    }

    @Test
    fun `keywords ignore case and values can follow an equals sign or be quoted`() {
        val host = SshConfigParser.parse(
            """
            HOST web
            hostname=example.org
            PORT = 2200
            User "deploy"  # trailing comment
            """.trimIndent(),
        ).hosts.single()
        assertEquals(SshConfigHost("web", "example.org", 2200, "deploy", false), host)
    }

    @Test
    fun `each alias on a Host line becomes a host`() {
        val config = SshConfigParser.parse("Host one two\n  User me")
        assertEquals(listOf("one", "two"), config.hosts.map { it.alias })
        assertEquals(listOf("me", "me"), config.hosts.map { it.user })
    }

    @Test
    fun `the first value of an option wins`() {
        val config = SshConfigParser.parse(
            """
            Host web
              User first
              User second
              Port 1
            Host web
              User third
              Port 2
            """.trimIndent(),
        )
        val host = config.hosts.single()
        assertEquals("first", host.user)
        assertEquals(1, host.port)
    }

    @Test
    fun `options above the first Host line win over a block`() {
        val host = SshConfigParser.parse("User global\nPort 2000\nHost web\n  User mine\n  Port 3000").hosts.single()
        assertEquals("global", host.user)
        assertEquals(2000, host.port)
    }

    @Test
    fun `wildcard blocks are listed and their options apply to nothing`() {
        val config = SshConfigParser.parse(
            """
            Host *
              User everyone
            Host *.corp !bad
              Port 99
            Host web
            """.trimIndent(),
        )
        assertEquals(listOf("web"), config.hosts.map { it.alias })
        assertNull(config.hosts.single().user)
        assertEquals(22, config.hosts.single().port)
        assertEquals(listOf("Host *", "Host *.corp !bad"), config.skipped)
    }

    @Test
    fun `a concrete alias beside a wildcard is still imported`() {
        val config = SshConfigParser.parse("Host web *.corp\n  User me")
        assertEquals("me", config.hosts.single().user)
        assertEquals(listOf("Host *.corp"), config.skipped)
    }

    @Test
    fun `Match blocks and Include lines are left out`() {
        val config = SshConfigParser.parse(
            """
            Include config.d/*
            Host web
              User me
            Match host other
              User someone
              ProxyJump bastion
            """.trimIndent(),
        )
        assertEquals("me", config.hosts.single().user)
        assertEquals(listOf("Include config.d/*", "Match host other"), config.skipped)
        // The options of a skipped block say nothing about the hosts imported.
        assertTrue(config.ignoredOptions.isEmpty())
    }

    @Test
    fun `unsupported options are listed once by name`() {
        val config = SshConfigParser.parse("Host a\n  ProxyJump x\n  ForwardAgent yes\nHost b\n  proxyjump y")
        assertEquals(listOf("ForwardAgent", "proxyjump").map(String::lowercase), config.ignoredOptions.map(String::lowercase))
    }

    @Test
    fun `tokens are filled in only where the file alone says what they mean`() {
        val config = SshConfigParser.parse(
            """
            Host alias
              HostName %h.example.org
            Host remote
              HostName %r.example.org
            Host named
              User %L
            """.trimIndent(),
        )
        assertEquals(listOf("alias", "named"), config.hosts.map { it.alias })
        assertEquals("alias.example.org", config.hosts.first().hostName)
        assertNull(config.hosts.last().user)
        assertEquals(listOf("Host remote"), config.skipped)
    }

    @Test
    fun `an invalid port leaves the host out instead of using another port`() {
        val config = SshConfigParser.parse("Host web\n  Port 99999\nHost good")
        assertEquals(listOf("good"), config.hosts.map { it.alias })
        assertEquals(listOf("Host web"), config.skipped)
    }

    @Test
    fun `IdentityFile none means no key`() {
        assertFalse(SshConfigParser.parse("Host web\n  IdentityFile none").hosts.single().hasIdentityFile)
        assertTrue(SshConfigParser.parse("Host web\n  IdentityFile none\n  IdentityFile ~/.ssh/a").hosts.single().hasIdentityFile)
    }

    @Test
    fun `text that isn't a config has no hosts`() {
        val config = SshConfigParser.parse("not a config\n\u0000\u0001\n=\n\"")
        assertTrue(config.hosts.isEmpty())
    }
}
