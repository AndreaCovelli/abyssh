package io.github.andreacovelli.abyssh.service.terminal

import java.io.Closeable
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.yield

/** Native wait results, as `abyssh_wake.zig` returns them. */
internal object NativeWait {
    const val CANNOT_WAIT = -1
    const val TIMEOUT = 0
    const val READY = 1
    const val WOKEN = 2
}

/** Creates, signals, and closes the file descriptor that wakes a native wait. */
internal interface WakeSignal {
    /** A new wake descriptor, or -1 when there is none. */
    fun create(): Int

    fun signal(fd: Int)

    fun close(fd: Int)
}

/** The eventfd in `abyssh_wake.zig`, or no wake descriptor without the native library. */
internal class NativeWakeSignal(private val bridge: NativeWakeBridge = NativeWakeBridge()) : WakeSignal {
    override fun create(): Int = if (bridge.isLoaded()) bridge.nativeCreate() else -1

    override fun signal(fd: Int) = bridge.nativeSignal(fd)

    override fun close(fd: Int) = bridge.nativeClose(fd)
}

/** Blocks the session thread until output arrives or other work is dispatched to it. */
internal interface OutputWaiter {
    /**
     * Calls [wait] with the wake descriptor, which is signalled when work is dispatched to
     * the session thread, or yields at once when work is already queued. Returns false
     * when there is no wake descriptor or [wait] returned [NativeWait.CANNOT_WAIT], so the
     * caller polls instead. Must run on the session thread.
     */
    suspend fun awaitOutput(wait: (wakeFd: Int) -> Int): Boolean
}

/**
 * The single thread a session's work runs on. A read loop can block it in a native wait
 * for output through [awaitOutput]; [dispatcher] wakes that wait whenever it queues work,
 * so input, resizes, snapshots, and disconnects still run at once.
 */
internal class SessionThread(private val wakeSignal: WakeSignal = NativeWakeSignal()) :
    OutputWaiter,
    Closeable {
    private val queue = LinkedBlockingQueue<Runnable>()
    private val executor =
        ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, queue) { r ->
            Thread(r, "terminal-session").apply { isDaemon = true }
        }
    private val executorDispatcher = executor.asCoroutineDispatcher()

    private val lock = Any()

    // Written only under lock; read without it on the session thread, which is the only
    // thread that closes it.
    @Volatile private var wakeFd = wakeSignal.create()

    // True while the session thread is in, or about to enter, a native wait. Set before
    // the queue is checked, and read after work is queued, so either the waiter sees the
    // work or the dispatcher sees the waiter.
    @Volatile private var blocked = false

    val dispatcher: CoroutineDispatcher =
        object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                executorDispatcher.dispatch(context, block)
                wakeIfBlocked()
            }

            override fun toString(): String = "SessionThread"
        }

    override suspend fun awaitOutput(wait: (wakeFd: Int) -> Int): Boolean {
        val fd = wakeFd
        if (fd < 0) return false
        blocked = true
        if (queue.isNotEmpty()) {
            blocked = false
            yield()
            return true
        }
        val result =
            try {
                wait(fd)
            } finally {
                blocked = false
            }
        return result != NativeWait.CANNOT_WAIT
    }

    /** Runs what is already queued, then closes the wake descriptor and the thread. */
    override fun close() {
        try {
            executor.execute(::closeWakeFd)
            wakeIfBlocked()
        } catch (_: RejectedExecutionException) {
            closeWakeFd()
        }
        executorDispatcher.close()
    }

    private fun wakeIfBlocked() {
        if (!blocked) return
        synchronized(lock) {
            val fd = wakeFd
            if (fd >= 0) wakeSignal.signal(fd)
        }
    }

    private fun closeWakeFd() {
        synchronized(lock) {
            val fd = wakeFd
            wakeFd = -1
            if (fd >= 0) wakeSignal.close(fd)
        }
    }
}
