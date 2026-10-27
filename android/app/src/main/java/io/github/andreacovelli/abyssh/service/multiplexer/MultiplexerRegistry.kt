package io.github.andreacovelli.abyssh.service.multiplexer

import io.github.andreacovelli.abyssh.model.MultiplexerType

object MultiplexerRegistry {
    val defaultType: MultiplexerType = MultiplexerType.Tmux

    fun forType(type: MultiplexerType): Multiplexer? = when (type) {
        MultiplexerType.Tmux -> TmuxMultiplexer
        MultiplexerType.Zmx -> ZmxMultiplexer
        MultiplexerType.Zellij -> null
    }
}
