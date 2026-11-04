package io.github.andreacovelli.abyssh.service.mosh

/**
 * Parses the output of `mosh-server new` to extract the connection endpoint.
 *
 * Expected line format: `MOSH CONNECT <port> <key>`
 * - `port`: 1 to 65535
 * - `key`: exactly 22 chars, charset [A-Za-z0-9+/-_]
 *
 * Handles noisy output before/after the connect line. If several connect lines exist,
 * the first valid one is accepted; if none is valid, the error is the first line's.
 */
object MoshBootstrapParser {

    data class MoshEndpoint(
        val host: String,
        val port: Int,
        val key: String,
    )

    sealed class ParseResult {
        data class Success(val endpoint: MoshEndpoint) : ParseResult()
        data class Error(val reason: String) : ParseResult()
    }

    // mosh-server prints this to stderr after the connect line, as it detaches.
    private val DETACHED_LINE_REGEX = Regex("""\[mosh-server detached, pid = (\d+)]""")

    // The key is taken whole and checked afterwards, so a longer token isn't cut to 22.
    private val CONNECT_LINE_REGEX = Regex("""MOSH CONNECT\s+(\S+)\s+(\S+)""")
    private val KEY_CHARS_REGEX = Regex("""[A-Za-z0-9+/_-]+""")
    private const val KEY_LENGTH = 22
    private const val MAX_PORT = 65535

    /** The pid mosh-server reported in [output], or null before it has. */
    fun serverPid(output: CharSequence): Int? =
        DETACHED_LINE_REGEX.find(output)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }

    fun parse(host: String, output: String): ParseResult {
        // Normalize CRLF to LF before scanning
        val normalized = output.replace("\r\n", "\n")

        var firstError: ParseResult.Error? = null
        for (line in normalized.split('\n')) {
            val match = CONNECT_LINE_REGEX.find(line.trim()) ?: continue
            when (val result = endpoint(host, match.groupValues[1], match.groupValues[2])) {
                is ParseResult.Success -> return result
                is ParseResult.Error -> firstError = firstError ?: result
            }
        }
        firstError?.let { return it }

        val lower = normalized.lowercase()
        return ParseResult.Error(
            when {
                lower.contains("command not found") && lower.contains("mosh-server") -> {
                    "mosh-server not found on remote host"
                }
                normalized.isBlank() -> {
                    "No MOSH bootstrap output received"
                }
                else -> {
                    "Server output missing valid MOSH CONNECT line"
                }
            }
        )
    }

    // The errors never include the key, which is the session secret.
    private fun endpoint(host: String, portStr: String, key: String): ParseResult {
        val port = portStr.toIntOrNull() ?: return ParseResult.Error("Invalid port: '$portStr'")
        return when {
            port <= 0 || port > MAX_PORT -> ParseResult.Error("Port out of range: $port")
            key.length != KEY_LENGTH -> ParseResult.Error("Key length invalid: ${key.length}")
            !KEY_CHARS_REGEX.matches(key) -> ParseResult.Error("Key has invalid characters")
            else -> ParseResult.Success(MoshEndpoint(host = host, port = port, key = key))
        }
    }
}
