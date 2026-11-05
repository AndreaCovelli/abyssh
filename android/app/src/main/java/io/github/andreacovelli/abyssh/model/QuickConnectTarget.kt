package io.github.andreacovelli.abyssh.model

private const val DEFAULT_SSH_PORT = 22
private const val MAX_PORT = 65535

/** Where a typed `user@host:port` address points. The username is blank when it has none. */
data class QuickConnectTarget(val username: String, val host: String, val port: Int = DEFAULT_SSH_PORT) {
    companion object {
        /**
         * Parses `[user@]host[:port]`. An IPv6 host takes a port only in brackets, as in
         * `[::1]:22`; `::1` on its own is a host with no port. Returns null when the
         * address has no usable host or its port is out of range.
         */
        fun parse(input: String): QuickConnectTarget? {
            val text = input.trim()
            if (text.isEmpty() || text.any(Char::isWhitespace)) return null
            // A username can hold an @ of its own, but a host can't.
            val at = text.lastIndexOf('@')
            val username = if (at >= 0) text.substring(0, at) else ""
            val address = text.substring(at + 1)
            val (host, port) = splitAddress(address) ?: return null
            if (host.isEmpty() || '/' in host || '@' in host) return null
            return QuickConnectTarget(username, host, port)
        }

        private fun splitAddress(address: String): Pair<String, Int>? {
            if (address.startsWith('[')) {
                val close = address.indexOf(']')
                if (close < 0) return null
                val rest = address.substring(close + 1)
                val port = when {
                    rest.isEmpty() -> DEFAULT_SSH_PORT
                    rest.startsWith(':') -> validPort(rest.substring(1)) ?: return null
                    else -> return null
                }
                return address.substring(1, close) to port
            }
            return when (address.count { it == ':' }) {
                0 -> address to DEFAULT_SSH_PORT
                1 -> validPort(address.substringAfter(':'))?.let { address.substringBefore(':') to it }
                else -> address to DEFAULT_SSH_PORT
            }
        }

        private fun validPort(text: String): Int? = text.toIntOrNull()?.takeIf { it in 1..MAX_PORT }
    }
}
