package io.github.andreacovelli.abyssh.service.terminal

/** An [OpenTab] without a terminal engine, for tests of labels and tab selection. */
internal data class FakeTab(override val id: String, override val spec: TabSpec, override val number: Int = 1) : OpenTab
