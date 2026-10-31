package io.github.andreacovelli.abyssh.service.ssh

/**
 * An SFTP call the server refused with a status, such as permission denied or no such file.
 * The connection still works, so running the call again won't help.
 */
class SftpStatusException(message: String) : IllegalStateException(message)
