package io.github.andreacovelli.abyssh.service.multiplexer

/**
 * [value] in single quotes, with each `'` in it closed, escaped, and reopened, so a POSIX
 * shell reads it as one word. tmux's command parser reads it the same way.
 */
internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
