package io.github.andreacovelli.abyssh.service.ssh

private const val MAX_PORT = 65535

/** A concrete host from an `ssh_config` file. No private key files are read. */
data class SshConfigHost(
    val alias: String,
    val hostName: String,
    val port: Int,
    /** Null when the file names no user for this host. */
    val user: String?,
    val hasIdentityFile: Boolean,
)

data class SshConfig(
    val hosts: List<SshConfigHost>,
    /** The directives left out, as written: wildcard hosts, `Include`, `Match`, and invalid hosts. */
    val skipped: List<String>,
    /** The names of the options nothing reads, such as `ProxyJump`, each once. */
    val ignoredOptions: List<String>,
)

/**
 * Reads the hosts from the text of an `ssh_config` file, with OpenSSH's rules for what
 * applies: keywords in any case, `key value` or `key=value`, and the first value found for
 * an option wins, which makes options above the first `Host` line win over any block.
 */
object SshConfigParser {
    private class Builder(val alias: String) {
        var hostName: String? = null
        var port: Int? = null
        var portInvalid = false
        var user: String? = null
        var identity: Boolean? = null
    }

    private class Parse {
        val builders = LinkedHashMap<String, Builder>()
        val global = Builder("")
        var current: List<Builder> = listOf(global)
        val skipped = LinkedHashSet<String>()
        val ignored = LinkedHashMap<String, String>()
    }

    fun parse(text: String): SshConfig {
        val parse = Parse()
        text.lineSequence().forEach { line -> parseLine(parse, line) }
        val hosts = mutableListOf<SshConfigHost>()
        parse.builders.values.forEach { builder ->
            val host = build(builder, parse.global)
            if (host != null) hosts += host else parse.skipped += "Host ${builder.alias}"
        }
        return SshConfig(hosts, parse.skipped.toList(), parse.ignored.values.sortedBy(String::lowercase))
    }

    private fun parseLine(parse: Parse, line: String) {
        val tokens = tokenize(line)
        val keyword = tokens.firstOrNull() ?: return
        val args = tokens.drop(1)
        when (keyword.lowercase()) {
            "host" -> startHostBlock(parse, args)
            "match" -> {
                parse.skipped += "Match ${args.joinToString(" ")}"
                parse.current = emptyList()
            }
            "include" -> parse.skipped += "Include ${args.joinToString(" ")}"
            "hostname" -> applyOption(parse, args) { value -> if (hostName == null) hostName = value }
            "user" -> applyOption(parse, args) { value -> if (user == null) user = value }
            "port" -> applyOption(parse, args, ::setPort)
            "identityfile" -> applyOption(parse, args) { value ->
                if (!value.equals("none", ignoreCase = true)) {
                    identity = true
                } else if (identity == null) {
                    identity = false
                }
            }
            else -> if (parse.current.isNotEmpty()) parse.ignored.putIfAbsent(keyword.lowercase(), keyword)
        }
    }

    private fun startHostBlock(parse: Parse, patterns: List<String>) {
        val (concrete, wildcard) = patterns.partition { pattern -> pattern.none { it == '*' || it == '?' || it == '!' } }
        if (wildcard.isNotEmpty()) parse.skipped += "Host ${wildcard.joinToString(" ")}"
        parse.current = concrete.map { alias -> parse.builders.getOrPut(alias) { Builder(alias) } }
    }

    private fun applyOption(parse: Parse, args: List<String>, set: Builder.(String) -> Unit) {
        val value = args.firstOrNull() ?: return
        parse.current.forEach { it.set(value) }
    }

    private fun setPort(builder: Builder, value: String) {
        if (builder.port != null || builder.portInvalid) return
        val port = value.toIntOrNull()?.takeIf { it in 1..MAX_PORT }
        builder.port = port
        builder.portInvalid = port == null
    }

    private fun build(builder: Builder, global: Builder): SshConfigHost? {
        // The options above the first Host line came first, so they win.
        val hostName = (global.hostName ?: builder.hostName ?: builder.alias).replace("%h", builder.alias)
        if (global.portInvalid || builder.portInvalid || !validHostName(hostName)) return null
        return SshConfigHost(
            alias = builder.alias,
            hostName = hostName,
            port = global.port ?: builder.port ?: DEFAULT_PORT,
            user = (global.user ?: builder.user)?.takeUnless { '%' in it },
            hasIdentityFile = (global.identity ?: false) || (builder.identity ?: false),
        )
    }

    // A remaining % is a token that only the machine running ssh can fill in.
    private fun validHostName(name: String): Boolean =
        name.isNotEmpty() && name.none { it == '%' || it == '/' || it.isWhitespace() || it.isISOControl() }

    /** The keyword and arguments of a line, without comments, `=`, or quotes. */
    private fun tokenize(line: String): List<String> {
        val tokens = mutableListOf<String>()
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char.isWhitespace() -> index += 1
                // Only the keyword can be followed by =, as in Port=22.
                char == '=' && tokens.size == 1 -> index += 1
                char == '#' -> return tokens
                else -> index = readToken(line, index, tokens)
            }
        }
        return tokens
    }

    private fun readToken(line: String, start: Int, tokens: MutableList<String>): Int {
        if (line[start] == '"') {
            val close = line.indexOf('"', start + 1).let { if (it < 0) line.length else it }
            tokens += line.substring(start + 1, close)
            return close + 1
        }
        var end = start
        while (end < line.length && !line[end].isWhitespace() && !(tokens.isEmpty() && line[end] == '=')) end += 1
        tokens += line.substring(start, end)
        return end
    }

    private const val DEFAULT_PORT = 22
}
