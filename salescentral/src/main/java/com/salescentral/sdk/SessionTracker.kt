package com.salescentral.sdk

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Foreground-time tracker. Listens to the process lifecycle and records a
 * finished session every time the app moves to the background — into the
 * client's analytics outbox, which delivers it (see [SalesClient.recordSession]).
 *
 * `ON_STOP` fires only on TRUE backgrounding — configuration changes and
 * transient interruptions (permission dialogs, notification shade) don't
 * stop the process lifecycle, so session counts aren't inflated (the
 * equivalent of iOS ignoring `willResignActive`).
 *
 * Owned by [SalesStore]; you don't normally construct this directly.
 */
class SessionTracker internal constructor(
    private val client: SalesClient,
    /**
     * Where the process lifecycle comes from. Production reads
     * [ProcessLifecycleOwner]; the JVM tests hand in a counting fake, which
     * is the only way to observe how many observers [start] registered.
     */
    private val lifecycleProvider: () -> Lifecycle,
) {

    constructor(client: SalesClient) : this(client, { ProcessLifecycleOwner.get().lifecycle })

    private var startedAt: Instant? = null

    /**
     * The registered observer, or null. Guarded by [observerLock]: two
     * concurrent [start] calls (a bootstrap racing an app's own defensive
     * one) used to both pass an unsynchronised null check and register two
     * observers, i.e. record every session twice (1.4.1).
     */
    private var observer: LifecycleEventObserver? = null
    private val observerLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Fired when the app returns to the foreground. [SalesStore] wires this
     * to re-sync subscription / premium on resume; left null it's a no-op.
     */
    var onForeground: (suspend () -> Unit)? = null

    /**
     * Begin tracking. Idempotent and thread-safe: concurrent callers
     * register exactly one observer. Callable from any thread.
     */
    fun start() {
        synchronized(observerLock) {
            if (observer != null) return
            val obs = LifecycleEventObserver { _: LifecycleOwner, event: Lifecycle.Event ->
                when (event) {
                    Lifecycle.Event.ON_START -> foreground()
                    Lifecycle.Event.ON_STOP -> background()
                    else -> {}
                }
            }
            try {
                val lifecycle = lifecycleProvider()
                runOnMainThread {
                    try {
                        lifecycle.addObserver(obs)
                        // If the app boots with the process already foregrounded,
                        // count from now.
                        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                            startedAt = Instant.now()
                        }
                    } catch (t: Throwable) {
                        SalesLog.warn(SalesLog.Category.SESSION, "session tracking unavailable: ${t.message}")
                    }
                }
                observer = obs
            } catch (t: Throwable) {
                // No process lifecycle here (e.g. plain-JVM unit tests) —
                // sessions just aren't tracked.
                SalesLog.warn(SalesLog.Category.SESSION, "session tracking unavailable: ${t.message}")
            }
        }
    }

    fun stop() {
        val obs = synchronized(observerLock) {
            val current = observer ?: return
            observer = null
            current
        }
        startedAt = null
        try {
            val lifecycle = lifecycleProvider()
            runOnMainThread {
                try {
                    lifecycle.removeObserver(obs)
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    /** Lifecycle registries are main-thread-only; hop there when needed. */
    private fun runOnMainThread(block: () -> Unit) {
        val main = android.os.Looper.getMainLooper()
        if (main == null || android.os.Looper.myLooper() == main) {
            block()
        } else {
            android.os.Handler(main).post { block() }
        }
    }

    private fun foreground() {
        if (startedAt == null) startedAt = Instant.now()
        onForeground?.let { hook -> scope.launch { hook() } }
    }

    private fun background() {
        val start = startedAt ?: return
        startedAt = null
        val end = Instant.now()
        val duration = maxOf(0, (end.epochSecond - start.epochSecond).toInt())
        // Enqueue-only (no network on this lifecycle callback); the outbox
        // drain delivers it, or retries after a reconnect / next launch's user.
        client.recordSession(start, end, duration)
    }
}
