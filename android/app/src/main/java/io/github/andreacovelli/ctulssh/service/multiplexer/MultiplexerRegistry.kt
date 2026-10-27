package io.github.andreacovelli.ctulssh.service.multiplexer

import io.github.andreacovelli.ctulssh.model.MultiplexerType

object MultiplexerRegistry {
    val defaultType: MultiplexerType = MultiplexerType.Tmux

    fun forType(type: MultiplexerType): Multiplexer? = when (type) {
        MultiplexerType.Tmux -> TmuxMultiplexer
        MultiplexerType.Zmx -> ZmxMultiplexer
        MultiplexerType.Zellij -> null
    }
}
