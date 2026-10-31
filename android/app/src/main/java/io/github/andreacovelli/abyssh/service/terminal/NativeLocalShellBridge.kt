package io.github.andreacovelli.abyssh.service.terminal

class NativeLocalShellBridge {
    companion object {
        private val loadError: Throwable? =
            runCatching { System.loadLibrary("abyssh_jni") }.exceptionOrNull()
    }

    fun isLoaded(): Boolean = loadError == null

    fun nativeStatus(): String = if (loadError == null) {
            "loaded"
        } else {
            val message = loadError.message?.takeIf { it.isNotBlank() } ?: "unknown"
            "not loaded (${loadError::class.simpleName}: $message)"
        }

    external fun nativeCreateSession(): Long

    external fun nativeDestroySession(handle: Long)

    external fun nativeStart(
        handle: Long,
        command: String,
        homeDir: String,
        tempDir: String,
        cols: Int,
        rows: Int,
        widthPx: Int,
        heightPx: Int,
        term: String,
    ): Boolean

    external fun nativeRead(handle: Long, maxBytes: Int): ByteArray?

    external fun nativeWrite(handle: Long, data: ByteArray): Int

    /** Waits for output or for `wakeFd`; returns a `NativeWait` value. */
    external fun nativeWaitReadable(handle: Long, wakeFd: Int, timeoutMs: Int): Int

    external fun nativeResize(
        handle: Long,
        cols: Int,
        rows: Int,
        widthPx: Int,
        heightPx: Int,
    ): Boolean

    external fun nativeGetLastError(handle: Long): String?
}
