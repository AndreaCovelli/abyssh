package io.github.andreacovelli.abyssh.service.terminal

/** The eventfd that wakes a session thread blocked in a native wait for output. */
class NativeWakeBridge {
    companion object {
        private val loadError: Throwable? =
            runCatching { System.loadLibrary("abyssh_jni") }.exceptionOrNull()
    }

    fun isLoaded(): Boolean = loadError == null

    /** A new eventfd, or -1 when none could be made. */
    external fun nativeCreate(): Int

    external fun nativeSignal(fd: Int)

    external fun nativeClose(fd: Int)
}
