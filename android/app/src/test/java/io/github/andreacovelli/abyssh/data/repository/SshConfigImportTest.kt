package io.github.andreacovelli.abyssh.data.repository

import io.github.andreacovelli.abyssh.model.AuthMethod
import io.github.andreacovelli.abyssh.model.HostProfile
import io.github.andreacovelli.abyssh.service.ssh.SshConfig
import io.github.andreacovelli.abyssh.service.ssh.SshConfigHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SshConfigImportTest {
    private fun config(vararg hosts: SshConfigHost) = SshConfig(hosts.toList(), listOf("Host *"), listOf("ProxyJump"))

    private val existing = HostProfile(id = 1, name = "web", host = "example.org", port = 22, username = "me")

    @Test
    fun `a host with an identity file waits for a key to be chosen`() {
        val plan = planSshConfigImport(config(SshConfigHost("db", "db.example.org", 22, "me", true)), emptyList())
        val host = plan.hosts.single()
        assertEquals(AuthMethod.Key, host.authMethod)
        assertNull(host.keyId)
        assertEquals(1, plan.needsSetup)
    }

    @Test
    fun `a host with neither a key nor a user needs only the user`() {
        val plan = planSshConfigImport(config(SshConfigHost("db", "db.example.org", 22, null, false)), emptyList())
        assertEquals(AuthMethod.Password, plan.hosts.single().authMethod)
        assertEquals("", plan.hosts.single().username)
        assertEquals(1, plan.needsSetup)
    }

    @Test
    fun `hosts saved already, or twice in the file, are counted and not added`() {
        val plan = planSshConfigImport(
            config(
                SshConfigHost("saved", "example.org", 22, "me", false),
                SshConfigHost("a", "new.example.org", 22, "me", false),
                SshConfigHost("b", "new.example.org", 22, "me", false),
            ),
            listOf(existing),
        )
        assertEquals(listOf("a"), plan.hosts.map { it.name })
        assertEquals(2, plan.alreadySaved)
    }

    @Test
    fun `a name in use gets a number`() {
        val plan = planSshConfigImport(config(SshConfigHost("web", "other.example.org", 22, "me", false)), listOf(existing))
        assertEquals("web-2", plan.hosts.single().name)
    }

    @Test
    fun `the plan keeps what the file left out`() {
        val plan = planSshConfigImport(config(), emptyList())
        assertEquals(listOf("Host *"), plan.skipped)
        assertEquals(listOf("ProxyJump"), plan.ignoredOptions)
    }
}
