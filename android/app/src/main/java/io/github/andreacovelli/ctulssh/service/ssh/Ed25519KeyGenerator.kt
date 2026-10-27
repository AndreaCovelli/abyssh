package io.github.andreacovelli.ctulssh.service.ssh

import io.github.andreacovelli.ctulssh.model.SshKey

class Ed25519KeyGenerator(private val bridge: NativeSshBridge = NativeSshBridge()) {
    fun generate(name: String, passphrase: String = ""): SshKey {
        val normalizedName = name.trim().ifBlank { "android-ed25519" }
        val comment = "$normalizedName@ctulssh"
        val effectivePassphrase = passphrase.ifBlank { null }

        val result = bridge.nativeGenerateEd25519Key(comment, effectivePassphrase)
            ?: throw IllegalStateException("Native Ed25519 key generation failed")

        return SshKey(
            name = normalizedName,
            algorithm = "Ed25519",
            privateKeyPem = result[0],
            publicKeyOpenSsh = result[1].trimEnd(),
            createdAtEpochMs = System.currentTimeMillis(),
        )
    }
}
