package io.github.andreacovelli.abyssh.data.repository

import io.github.andreacovelli.abyssh.data.backup.uniqueName
import io.github.andreacovelli.abyssh.model.AuthMethod
import io.github.andreacovelli.abyssh.model.HostProfile
import io.github.andreacovelli.abyssh.model.needsSetup
import io.github.andreacovelli.abyssh.service.ssh.SshConfig

/** What importing an `ssh_config` file would do. */
data class SshConfigImportPlan(
    val hosts: List<HostProfile>,
    /** Hosts in the file with the same address and user as a saved host. */
    val alreadySaved: Int,
    val skipped: List<String>,
    val ignoredOptions: List<String>,
) {
    val needsSetup: Int get() = hosts.count { it.needsSetup }
}

/**
 * Plans new hosts for [config] alongside the [existing] ones. A host with an `IdentityFile`
 * uses key authentication with no key yet, for the user to pick from the keys in the app.
 */
fun planSshConfigImport(config: SshConfig, existing: List<HostProfile>): SshConfigImportPlan {
    val usedNames = existing.mapTo(mutableSetOf()) { it.name }
    val seen = existing.mapTo(mutableSetOf()) { Triple(it.host, it.port, it.username) }
    var alreadySaved = 0
    val hosts = config.hosts.mapNotNull { host ->
        val username = host.user.orEmpty()
        if (!seen.add(Triple(host.hostName, host.port, username))) {
            alreadySaved += 1
            return@mapNotNull null
        }
        HostProfile(
            name = uniqueName(host.alias, usedNames),
            host = host.hostName,
            port = host.port,
            username = username,
            authMethod = if (host.hasIdentityFile) AuthMethod.Key else AuthMethod.Password,
        )
    }
    return SshConfigImportPlan(hosts, alreadySaved, config.skipped, config.ignoredOptions)
}
