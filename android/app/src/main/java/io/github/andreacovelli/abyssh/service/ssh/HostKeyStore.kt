package io.github.andreacovelli.abyssh.service.ssh

import android.content.SharedPreferences
import androidx.core.content.edit
import android.util.Base64
import java.security.MessageDigest

class HostKeyStore(
    private val prefs: SharedPreferences,
) {
    companion object {
        const val PREFS_NAME = "host_keys"
    }

    fun loadKey(host: String, port: Int, algorithm: String): ByteArray? {
        val encoded = prefs.getString(key(host, port, algorithm), null) ?: return null
        return Base64.decode(encoded, Base64.NO_WRAP)
    }

    /**
     * The key types trusted for [host]:[port], such as `ED25519`, in the order the
     * handshake should ask for them.
     */
    fun storedAlgorithms(host: String, port: Int): List<String> {
        val prefix = "$host:$port:"
        // A host that itself contains ":" (IPv6, or "a:22" next to "a") leaves a ":" in
        // what follows the prefix, so it can't be mistaken for an algorithm.
        return prefs.all.keys
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .filter { it.isNotEmpty() && ':' !in it }
            .sorted()
    }

    /**
     * Trusts [keyBytes] as the key of [host]:[port]. Keys of other types stored for the
     * host are dropped, so a server whose key the user replaced isn't asked for the old
     * one again.
     */
    fun saveKey(host: String, port: Int, algorithm: String, keyBytes: ByteArray) {
        val encoded = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
        val stale = storedAlgorithms(host, port) - algorithm
        prefs.edit {
            stale.forEach { remove(key(host, port, it)) }
            putString(key(host, port, algorithm), encoded)
        }
    }

    fun check(
        host: String,
        port: Int,
        algorithm: String,
        keyBytes: ByteArray,
    ): HostKeyCheck {
        // A key of another type counts as a changed key, not a new host: a server that
        // suddenly offers only a different type is what an impostor without the trusted
        // key would do.
        val existing =
            loadKey(host, port, algorithm)
                ?: storedAlgorithms(host, port).firstNotNullOfOrNull { loadKey(host, port, it) }
        return when {
            existing == null -> HostKeyCheck.Unknown(fingerprint = fingerprintSha256(keyBytes))
            existing.contentEquals(keyBytes) -> HostKeyCheck.Match
            else ->
                HostKeyCheck.Changed(
                    previousFingerprint = fingerprintSha256(existing),
                    fingerprint = fingerprintSha256(keyBytes),
                )
        }
    }

    private fun key(host: String, port: Int, algorithm: String): String =
        "$host:$port:$algorithm"

    private fun fingerprintSha256(keyBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(keyBytes)
        val encoded = Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
        return "SHA256:$encoded"
    }
}

sealed interface HostKeyCheck {
    data object Match : HostKeyCheck
    data class Unknown(val fingerprint: String) : HostKeyCheck
    data class Changed(
        val previousFingerprint: String,
        val fingerprint: String,
    ) : HostKeyCheck
}
