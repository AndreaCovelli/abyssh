package io.github.andreacovelli.abyssh.service.terminal

enum class SessionStatus {
    Disconnected,
    Connecting,
    Reconnecting,
    Connected,
    Error,
    ;

    /** Connected, or on its way there. */
    val isLive: Boolean
        get() = this == Connecting || this == Reconnecting || this == Connected
}

data class SessionState(
    val status: SessionStatus = SessionStatus.Disconnected,
    val sessionKey: String? = null,
    val snapshot: TerminalSnapshot? = null,
    val title: String? = null,
    val pwd: String? = null,
    val bellCount: Int = 0,
    val nativeVersion: String? = null,
    val reconnectAttempt: Int = 0,
    val error: String? = null,
    val handle: Long = 0,
)

data class HostKeyPrompt(
    val host: String,
    val port: Int,
    val algorithm: String,
    val fingerprint: String,
    val previousFingerprint: String?,
)
