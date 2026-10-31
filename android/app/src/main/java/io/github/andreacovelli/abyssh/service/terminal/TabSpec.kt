package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.model.UiText

import io.github.andreacovelli.abyssh.model.AuthMethod
import io.github.andreacovelli.abyssh.model.HostProfile
import io.github.andreacovelli.abyssh.model.MultiplexerType
import io.github.andreacovelli.abyssh.model.Transport

data class TabSpec(
    val hostId: Long? = null,
    val displayName: String = "",
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val password: String = "",
    val authMethod: AuthMethod = AuthMethod.Password,
    val publicKeyOpenSsh: String = "",
    val privateKeyPem: String = "",
    val keyPassphrase: String = "",
    val transport: Transport = Transport.SSH,
    val postConnectCommand: String? = null,
    val multiplexer: MultiplexerType? = null,
    val multiplexerSessionName: String? = null,
    val multiplexerCreateIfMissing: Boolean = true,
    val tmuxControlMode: Boolean = false,
) {
    /**
     * The same for every tab to one host: a saved host, the local shell, or an unsaved
     * target. Tabs that share it are numbered apart; see [sessionLabel].
     */
    val sessionKey: String
        get() =
            when (transport) {
                Transport.LocalShell -> "local-shell"
                else -> hostId?.let { "host:$it" } ?: "${transport.name}:$username@$host:$port"
            }

    /** The notification text for a tab: its name, then [number] when its host has several tabs. */
    fun notificationLabel(number: Int? = null): UiText {
        val suffix = number?.let { " · $it" }.orEmpty()
        if (transport == Transport.LocalShell) {
            val name = displayName.takeIf { it.isNotBlank() }?.let(UiText::Raw)
                ?: UiText.Resource(R.string.session_notification_local_shell)
            return if (number == null) name else UiText.Joined(listOf(name, UiText.Raw("$number")), " · ")
        }
        val target = "$username@$host:$port"
        return UiText.Raw(if (displayName.isNotBlank()) "$displayName$suffix  ·  $target" else "$target$suffix")
    }

    val tabLabel: String
        get() =
            when (transport) {
                Transport.LocalShell -> displayName.takeIf { it.isNotBlank() } ?: "local shell"
                else -> displayName.takeIf { it.isNotBlank() } ?: "$username@$host"
            }

    val usesRuntimeMultiplexer: Boolean
        get() =
            multiplexer?.runtimeSupported == true &&
                transport != Transport.Mosh &&
                transport != Transport.LocalShell

    /** Whether this tab attaches to tmux in control mode, where each tmux window is a tab. */
    val usesTmuxControlMode: Boolean
        get() = tmuxControlMode && multiplexer == MultiplexerType.Tmux && usesRuntimeMultiplexer

    companion object {
        fun fromHostProfile(
            host: HostProfile,
            publicKeyOpenSsh: String = "",
            privateKeyPem: String = "",
            keyPassphrase: String = "",
        ): TabSpec = TabSpec(
            hostId = host.id,
            displayName = host.name,
            host = host.host,
            port = host.port,
            username = host.username,
            password = host.password,
            authMethod = host.authMethod,
            publicKeyOpenSsh = publicKeyOpenSsh,
            privateKeyPem = privateKeyPem,
            keyPassphrase = keyPassphrase,
            transport = host.transport,
            postConnectCommand = host.postConnectCommand,
            multiplexer = host.multiplexer?.takeIf {
                it.runtimeSupported &&
                    host.transport != Transport.Mosh &&
                    host.transport != Transport.LocalShell
            },
            tmuxControlMode = host.tmuxControlMode,
        )
    }
}

/**
 * This tab's spec after its host was edited. [updated] comes from the saved host,
 * so it lacks what each tab picks at connect time: the multiplexer session it
 * attached to and the key passphrase the user typed. Keep those when they still apply.
 */
internal fun TabSpec.withHostUpdate(updated: TabSpec): TabSpec {
    val sameMultiplexer = updated.multiplexer != null && updated.multiplexer == multiplexer
    val sameKey =
        updated.privateKeyPem == privateKeyPem && updated.authMethod == authMethod
    return updated.copy(
        multiplexerSessionName = if (sameMultiplexer) multiplexerSessionName else null,
        multiplexerCreateIfMissing = if (sameMultiplexer) multiplexerCreateIfMissing else true,
        keyPassphrase = updated.keyPassphrase.ifEmpty { if (sameKey) keyPassphrase else "" },
    )
}

/** Whether a running session would connect differently with [other]. The name doesn't count. */
internal fun TabSpec.connectsDifferentlyThan(other: TabSpec): Boolean =
    copy(displayName = other.displayName) != other
