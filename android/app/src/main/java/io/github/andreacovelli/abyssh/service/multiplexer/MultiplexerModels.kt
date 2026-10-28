package io.github.andreacovelli.abyssh.service.multiplexer

import io.github.andreacovelli.abyssh.model.MultiplexerType
import io.github.andreacovelli.abyssh.model.Transport
import io.github.andreacovelli.abyssh.model.UiText

data class RemoteMultiplexerSession(
    val name: String,
    val attached: Boolean,
)

sealed interface MultiplexerAvailability {
    data object Available : MultiplexerAvailability
    data class Missing(val multiplexer: MultiplexerType) : MultiplexerAvailability
    data class UnsupportedMultiplexer(val multiplexer: MultiplexerType) : MultiplexerAvailability
    data class UnsupportedTransport(val transport: Transport) : MultiplexerAvailability
    data class Error(val message: UiText, val output: String = "") : MultiplexerAvailability
}

data class MultiplexerCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val output: String
        get() = listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString("\n")

    val isSuccess: Boolean
        get() = exitCode == 0
}
