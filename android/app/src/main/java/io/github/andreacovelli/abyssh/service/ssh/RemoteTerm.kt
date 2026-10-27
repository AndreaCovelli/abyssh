package io.github.andreacovelli.abyssh.service.ssh

/**
 * The TERM value sent to SSH hosts.
 *
 * Abyssh renders with Ghostty, but few servers have an `xterm-ghostty` terminfo
 * entry, and stock shell profiles such as Ubuntu's `.bashrc` only color the prompt
 * for names they recognize. Like Ghostty's own ssh integration, every SSH session
 * sends `xterm-256color`, which every server knows.
 */
object RemoteTerm {
    const val SSH = "xterm-256color"
}
