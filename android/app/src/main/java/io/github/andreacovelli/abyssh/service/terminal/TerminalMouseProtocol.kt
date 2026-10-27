package io.github.andreacovelli.abyssh.service.terminal

// Names mirror the native mouse action and button values.
@Suppress("ktlint:standard:property-naming")
object TerminalMouseAction {
    const val Press = 0
    const val Release = 1
    const val Motion = 2
}

@Suppress("ktlint:standard:property-naming")
object TerminalMouseButton {
    const val Left = 1
    const val Right = 2
    const val Middle = 3
}
