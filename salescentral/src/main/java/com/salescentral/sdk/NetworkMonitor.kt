package com.salescentral.sdk

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/**
 * [SalesClient]'s dependency on a reconnect watcher, factored out so tests
 * can inject a fake and assert on its start/stop lifecycle without a real
 * `ConnectivityManager` (see [ConnectivityRegistrar] below for why that
 * can't be faked directly in this module's plain JVM unit tests).
 */
internal interface ReconnectMonitor {
    var onReconnect: (() -> Unit)?
    fun start()
    fun stop()
}

/**
 * The OS calls [NetworkMonitor] wraps, pulled behind a seam of their own.
 * `ConnectivityManager`'s constructor is package-private (verified against
 * the compileSdk 37 stub jar) and this module has no Robolectric/Mockito in
 * its test dependencies, so a real or mocked `ConnectivityManager` cannot be
 * constructed from `src/test`. Faking at this one-layer-in seam instead lets
 * [NetworkMonitorTest] exercise [NetworkMonitor]'s actual start/stop state
 * machine — including the race [NetworkMonitor.stop] documents — without
 * needing one.
 */
internal interface ConnectivityRegistrar {
    fun register(callback: ConnectivityManager.NetworkCallback)
    fun unregister(callback: ConnectivityManager.NetworkCallback)
}

private class SystemConnectivityRegistrar(context: Context) : ConnectivityRegistrar {
    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    override fun register(callback: ConnectivityManager.NetworkCallback) {
        cm?.registerDefaultNetworkCallback(callback)
    }

    override fun unregister(callback: ConnectivityManager.NetworkCallback) {
        cm?.unregisterNetworkCallback(callback)
    }
}

/**
 * Minimal reachability watcher used to recover a bootstrap that failed
 * because the device was offline at first launch, and — since the 1.2.0
 * outbox — to resume a backlogged analytics drain on reconnect. Fires
 * [onReconnect] on the transition into an available network.
 *
 * (Like the iOS `NWPathMonitor` version, registering while already online
 * fires immediately — desirable both for the original bootstrap-retry use
 * and for the outbox: [SalesClient] only starts this after a retryable
 * drain failure, so an immediate re-check on registration is exactly what's
 * wanted.)
 *
 * [SalesClient] publishes a freshly-created instance under its own `lock`
 * and then calls [start] OUTSIDE that lock — registering a callback is a
 * binder call it doesn't want to hold a lock across. A drain pass that
 * empties the outbox in that same window calls [stop] on the very same
 * instance, also outside `lock` (see `stopOutboxReconnectMonitorIfDrained`).
 * Which of the two physically executes first is a genuine race between two
 * threads, independent of `@Synchronized` — that only stops [start] and
 * [stop] from INTERLEAVING, it does not impose an order between two
 * separate calls made by two different threads. [stopped] is what makes the
 * order not matter: once a [stop] has run — even one that arrived before
 * [start] and so found nothing registered yet to unregister — this instance
 * must never register a callback, or a `stop()` that wins the race leaks a
 * registration nothing will ever undo (one of the process's limited
 * `registerDefaultNetworkCallback` slots).
 */
internal class NetworkMonitor(
    context: Context,
    private val registrar: ConnectivityRegistrar = SystemConnectivityRegistrar(context),
) : ReconnectMonitor {

    /**
     * Called each time connectivity transitions from unavailable to
     * available. Invoked on a binder thread.
     */
    override var onReconnect: (() -> Unit)? = null

    private var registered = false
    private var wasAvailable = false

    /**
     * Set the first time [stop] runs on this instance and never cleared —
     * each backlog episode gets a brand-new [NetworkMonitor], never a
     * restarted one, so "stopped" is terminal for the instance's lifetime.
     * Checked (and set) only inside the `@Synchronized` [start] / [stop]
     * pair, so the two calls agree on it regardless of which physically
     * runs first.
     */
    private var stopped = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!wasAvailable) onReconnect?.invoke()
            wasAvailable = true
        }

        override fun onLost(network: Network) {
            wasAvailable = false
        }
    }

    @Synchronized
    override fun start() {
        if (stopped || registered) return
        try {
            registrar.register(callback)
            registered = true
        } catch (e: Exception) {
            SalesLog.warn(SalesLog.Category.SDK, "NetworkMonitor.start failed: ${e.message}")
        }
    }

    @Synchronized
    override fun stop() {
        stopped = true
        if (!registered) return
        try {
            registrar.unregister(callback)
        } catch (_: Exception) {
        }
        registered = false
    }
}
