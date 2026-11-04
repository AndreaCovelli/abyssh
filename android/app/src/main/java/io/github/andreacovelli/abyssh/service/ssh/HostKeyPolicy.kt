package io.github.andreacovelli.abyssh.service.ssh

fun interface HostKeyPolicy {
    fun verify(host: String, port: Int, algorithm: String, keyBytes: ByteArray): Boolean

    /**
     * The key types already trusted for [host]:[port], such as `ED25519`. The handshake
     * asks for them first, so a server with several keys shows the one the user verified.
     */
    fun trustedAlgorithms(host: String, port: Int): List<String> = emptyList()
}

/** A policy that decides with [verify] and asks the handshake for the key types this store trusts. */
fun HostKeyStore.policy(verify: (host: String, port: Int, algorithm: String, keyBytes: ByteArray) -> Boolean): HostKeyPolicy =
    object : HostKeyPolicy {
        override fun verify(host: String, port: Int, algorithm: String, keyBytes: ByteArray): Boolean =
            verify(host, port, algorithm, keyBytes)

        override fun trustedAlgorithms(host: String, port: Int): List<String> = storedAlgorithms(host, port)
    }
