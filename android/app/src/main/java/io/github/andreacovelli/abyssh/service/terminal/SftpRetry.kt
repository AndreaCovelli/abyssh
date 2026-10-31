package io.github.andreacovelli.abyssh.service.terminal

import io.github.andreacovelli.abyssh.R
import io.github.andreacovelli.abyssh.model.UiText
import io.github.andreacovelli.abyssh.model.UiTextException
import io.github.andreacovelli.abyssh.service.ssh.SftpStatusException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal const val SESSION_READY_WAIT_MS = 20_000L

/** How long a failed SFTP call waits for the session to report that it dropped. */
internal const val SFTP_DROP_DETECTION_MS = 1_000L

/**
 * Runs [operation] once the session is connected. If it fails because the session dropped
 * while it ran, waits for the reconnect and runs it once more. A status the server sent
 * back, such as permission denied, fails straight away: the connection still works.
 *
 * Calls that continue an earlier one, such as the chunks of an upload, must not come
 * through here, because the remote handle they need is gone after a reconnect.
 */
@Suppress("TooGenericExceptionCaught") // SFTP calls fail with whatever the transport throws.
internal suspend fun <T> runWhenConnected(
    state: StateFlow<SessionState>,
    readyTimeoutMs: Long = SESSION_READY_WAIT_MS,
    dropDetectionMs: Long = SFTP_DROP_DETECTION_MS,
    operation: suspend () -> T,
): T {
    awaitConnected(state, readyTimeoutMs)
    return try {
        operation()
    } catch (e: CancellationException) {
        throw e
    } catch (e: SftpStatusException) {
        throw e
    } catch (e: Exception) {
        val dropped = withTimeoutOrNull(dropDetectionMs) {
            state.first { it.status != SessionStatus.Connected }
        } != null
        if (!dropped) throw e
        awaitConnected(state, readyTimeoutMs)
        operation()
    }
}

/** Waits while the session connects or reconnects, and fails if it ends anywhere but connected. */
private suspend fun awaitConnected(state: StateFlow<SessionState>, timeoutMs: Long) {
    val settled = withTimeoutOrNull(timeoutMs) {
        state.first { it.status != SessionStatus.Connecting && it.status != SessionStatus.Reconnecting }
    } ?: throw UiTextException(
        UiText.Resource(R.string.error_reconnect_timeout),
        "Timed out waiting for the session to reconnect",
    )
    if (settled.status != SessionStatus.Connected) {
        throw UiTextException(
            settled.error ?: UiText.Resource(R.string.error_session_not_connected),
            "Session is not connected",
        )
    }
}
