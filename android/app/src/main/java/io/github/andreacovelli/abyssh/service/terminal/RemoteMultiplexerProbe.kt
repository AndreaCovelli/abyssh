package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.model.AuthMethod
import io.github.andreacovelli.abyssh.model.MultiplexerType
import io.github.andreacovelli.abyssh.model.Transport
import io.github.andreacovelli.abyssh.model.UiText
import io.github.andreacovelli.abyssh.model.UiTextException
import io.github.andreacovelli.abyssh.model.toUiText
import io.github.andreacovelli.abyssh.service.multiplexer.Multiplexer
import io.github.andreacovelli.abyssh.service.multiplexer.MULTIPLEXER_MISSING_EXIT_CODE
import io.github.andreacovelli.abyssh.service.multiplexer.MultiplexerAvailability
import io.github.andreacovelli.abyssh.service.multiplexer.MultiplexerCommandResult
import io.github.andreacovelli.abyssh.service.multiplexer.MultiplexerRegistry
import io.github.andreacovelli.abyssh.service.multiplexer.MultiplexerSessionAllocator
import io.github.andreacovelli.abyssh.service.multiplexer.RemoteMultiplexerSession
import io.github.andreacovelli.abyssh.service.multiplexer.tmux.TmuxControlCommands
import io.github.andreacovelli.abyssh.service.ssh.HostKeyPolicy
import io.github.andreacovelli.abyssh.service.ssh.NativeSshService

private const val COMMAND_TIMEOUT_MS = 20_000L
private const val EXEC_READ_BYTES = 4096
private const val EXEC_POLL_MS = 25L
private const val EXIT_NO_CHANNEL = 1
private const val EXIT_TIMED_OUT = 124
private const val EXIT_NO_MARKER = 125
private val EXIT_MARKER = Regex("(?:^|\\n)ABYSSH_EXIT:(\\d+)\\s*$")

/**
 * Runs multiplexer commands on a host, each over its own short-lived SSH exec channel:
 * whether the multiplexer is installed, which sessions it has, and which one a tab should
 * attach to. The calls block, so run them off the main thread. A multiplexer found in the
 * last few minutes, per [availabilityCache], isn't checked again.
 */
internal class RemoteMultiplexerProbe(
    private val hostKeyPolicy: HostKeyPolicy,
    private val availabilityCache: MultiplexerAvailabilityCache = MultiplexerAvailabilityCache.shared,
) {
    fun availability(spec: TabSpec): MultiplexerAvailability {
        val type = spec.multiplexer ?: return MultiplexerAvailability.Available
        val multiplexer =
            MultiplexerRegistry.forType(type) ?: return MultiplexerAvailability.UnsupportedMultiplexer(type)
        if (!spec.transport.runsRemoteCommands) return MultiplexerAvailability.UnsupportedTransport(spec.transport)
        if (availabilityCache.isAvailable(spec, type)) return MultiplexerAvailability.Available
        return runCatching {
            if (runCommand(spec, multiplexer.availabilityCommand()).isSuccess) {
                availabilityCache.recordAvailable(spec, type)
                MultiplexerAvailability.Available
            } else {
                MultiplexerAvailability.Missing(type)
            }
        }.getOrElse { error ->
            MultiplexerAvailability.Error(message = error.toUiText(UiText.Resource(R.string.multiplexer_check_failed, type.label)))
        }
    }

    fun listSessions(spec: TabSpec): List<RemoteMultiplexerSession> {
        val type = spec.multiplexer ?: error("No multiplexer selected")
        val multiplexer = supportedMultiplexer(type, spec.transport)
        // A control-mode host also reports its tmux version, which says whether control mode works there.
        val withVersion = type == MultiplexerType.Tmux && spec.usesTmuxControlMode
        val listCommand = multiplexer.listSessionsCommand()
        val result = runCommand(
            spec,
            if (withVersion) TmuxControlCommands.listSessionsWithVersion(listCommand) else listCommand,
        )
        if (result.exitCode == MULTIPLEXER_MISSING_EXIT_CODE) {
            throw UiTextException(UiText.Resource(R.string.multiplexer_missing, type.label), "${type.label} unavailable")
        }
        if (!result.isSuccess) {
            val stderr = result.stderr.trim()
            val text = if (stderr.isEmpty()) UiText.Resource(R.string.multiplexer_list_failed) else UiText.Raw(stderr)
            throw UiTextException(text, "Failed to list ${type.label} sessions")
        }
        availabilityCache.recordAvailable(spec, type)
        if (!withVersion) return multiplexer.parseSessions(result.stdout)
        val (version, sessions) = TmuxControlCommands.splitVersion(result.stdout)
        TmuxControlSupport.record(spec, version)
        return multiplexer.parseSessions(sessions)
    }

    /**
     * The session a tab for [spec] attaches to: its own name when it still applies, a
     * detached Abyssh session when [reuseDetachedAbysshSession] allows one, or a new name
     * that no remote session and none of [localSessionNames] uses.
     */
    fun resolveSessionName(
        spec: TabSpec,
        localSessionNames: Collection<String>,
        reuseDetachedAbysshSession: Boolean = false,
    ): String {
        val type = spec.multiplexer ?: MultiplexerRegistry.defaultType
        val multiplexer = supportedMultiplexer(type, spec.transport)
        // Listing fails with the same message when the multiplexer isn't installed.
        val remoteSessions = listSessions(spec.copy(multiplexer = type))
        val existingName = spec.multiplexerSessionName?.takeIf { it.isNotBlank() }
        if (existingName != null) {
            check(spec.multiplexerCreateIfMissing || remoteSessions.any { it.name == existingName }) {
                "${type.label} session \"$existingName\" is no longer available"
            }
            return existingName
        }
        val reusable =
            if (reuseDetachedAbysshSession) {
                MultiplexerSessionAllocator.reusableDetachedAbysshSessionName(remoteSessions, localSessionNames)
            } else {
                null
            }
        return reusable ?: multiplexer.defaultSessionName(remoteSessions, localSessionNames)
    }

    private fun runCommand(spec: TabSpec, command: String): MultiplexerCommandResult =
        NativeSshService(hostKeyPolicy = hostKeyPolicy).use { ssh ->
            ssh.connect(spec)
            if (ssh.openExec(withExitEnvelope(command))) {
                readExecOutput(ssh)
            } else {
                MultiplexerCommandResult(EXIT_NO_CHANNEL, "", "Remote server did not open an exec channel")
            }
        }

    private fun readExecOutput(ssh: NativeSshService): MultiplexerCommandResult {
        val output = StringBuilder()
        val deadline = System.currentTimeMillis() + COMMAND_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val chunk = ssh.read(EXEC_READ_BYTES)
            when {
                chunk != null && chunk.isNotEmpty() -> output.append(String(chunk, Charsets.UTF_8))
                ssh.isChannelEof() -> return parseCommandEnvelope(output.toString())
                else -> Thread.sleep(EXEC_POLL_MS)
            }
        }
        return MultiplexerCommandResult(EXIT_TIMED_OUT, output.toString(), "Command timed out")
    }
}

/** Whether a transport can run remote commands, which multiplexers need. */
internal val Transport.runsRemoteCommands: Boolean
    get() = this != Transport.Mosh && this != Transport.LocalShell

/** Why a session can't use [selected], or null when it can. */
internal fun MultiplexerAvailability.errorMessage(selected: MultiplexerType?): UiText? = when (this) {
    MultiplexerAvailability.Available -> null
    is MultiplexerAvailability.Missing -> UiText.Resource(R.string.multiplexer_missing, multiplexer.label)
    is MultiplexerAvailability.UnsupportedMultiplexer -> UiText.Resource(R.string.multiplexer_unsupported, multiplexer.label)
    is MultiplexerAvailability.UnsupportedTransport -> UiText.Resource(R.string.multiplexer_unsupported_transport,
        selected?.label ?: UiText.Resource(R.string.multiplexer_generic),
        transport.name,
    )
    is MultiplexerAvailability.Error -> message
}

/** The multiplexer for [type], or a failure the user can read when [transport] can't run it. */
private fun supportedMultiplexer(type: MultiplexerType, transport: Transport): Multiplexer {
    val multiplexer = MultiplexerRegistry.forType(type) ?: throw UiTextException(
        UiText.Resource(R.string.multiplexer_unsupported, type.label),
        "${type.label} is not supported yet",
    )
    if (!transport.runsRemoteCommands) {
        throw UiTextException(
            UiText.Resource(R.string.multiplexer_unsupported_transport, type.label, transport.name),
            "${type.label} is not supported for $transport connections",
        )
    }
    return multiplexer
}


/** Logs in over [spec]'s credentials; the password only goes out for password auth. */
internal fun NativeSshService.connect(spec: TabSpec) = connect(
    host = spec.host,
    port = spec.port,
    username = spec.username,
    authMethod = spec.authMethod,
    password = if (spec.authMethod == AuthMethod.Password) spec.password else "",
    publicKeyOpenSsh = spec.publicKeyOpenSsh,
    privateKeyPem = spec.privateKeyPem,
    keyPassphrase = spec.keyPassphrase,
)

/** The command that starts a tab's multiplexer session, or null when it has none. */
internal fun TabSpec.multiplexerStartupCommand(): String? {
    val type = multiplexer ?: return null
    if (!type.runtimeSupported || !transport.runsRemoteCommands) return null
    val sessionName = multiplexerSessionName?.takeIf { it.isNotBlank() } ?: return null
    val runtime = MultiplexerRegistry.forType(type) ?: return null
    return runtime.launchCommand(
        sessionName = sessionName,
        createIfMissing = multiplexerCreateIfMissing,
        trustedRemoteName = true,
    )
}

/** Appends a marker with the exit code, since an exec channel doesn't report it. */
internal fun withExitEnvelope(command: String): String = "$command; printf '\nABYSSH_EXIT:%s\n' \"\$?\""

internal fun parseCommandEnvelope(output: String): MultiplexerCommandResult {
    val marker =
        EXIT_MARKER.find(output)
            ?: return MultiplexerCommandResult(EXIT_NO_MARKER, output, "Missing command exit marker")
    val exitCode = marker.groupValues.getOrNull(1)?.toIntOrNull() ?: EXIT_NO_MARKER
    return MultiplexerCommandResult(exitCode, output.substring(0, marker.range.first).trimEnd(), "")
}
