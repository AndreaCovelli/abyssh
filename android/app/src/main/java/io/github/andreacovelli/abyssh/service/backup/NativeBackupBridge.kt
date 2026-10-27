package io.github.andreacovelli.abyssh.service.backup

class NativeBackupBridge {
    companion object {
        private val loadError: Throwable? =
            runCatching { System.loadLibrary("abyssh_jni") }.exceptionOrNull()

        const val STATUS_OK: Int = 0
        const val STATUS_FORMAT_ERROR: Int = 1
        const val STATUS_INVALID_PASSPHRASE: Int = 2
        const val STATUS_INTERNAL_ERROR: Int = 3
    }

    fun isLoaded(): Boolean = loadError == null

    external fun nativeEncrypt(plaintext: ByteArray, passphrase: CharArray): ByteArray?

    external fun nativeDecrypt(ciphertext: ByteArray, passphrase: CharArray): ByteArray?
}
