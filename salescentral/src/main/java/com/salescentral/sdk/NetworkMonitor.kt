package com.salescentral.sdk

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/**
 * Minimal reachability watcher used to recover a bootstrap that failed
 * because the device was offline at first launch. Fires [onReconnect] on
 * the transition into an available network — [SalesCentral.start] uses it
 * to retry once the network returns.
 *
 * (Like the iOS `NWPathMonitor` version, registering while already online
 * fires immediately — which is desirable here, since the monitor is only
 * started after a FAILED bootstrap and an immediate retry is wanted.)
 */
internal class NetworkMonitor(context: Context) {

    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /**
     * Called each time connectivity transitions from unavailable to
     * available. Invoked on a binder thread.
     */
    var onReconnect: (() -> Unit)? = null

    private var registered = false
    private var wasAvailable = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!wasAvailable) onReconnect?.invoke()
            wasAvailable = true
        }

        override fun onLost(network: Network) {
            wasAvailable = false
        }
    }

    fun start() {
        val manager = cm ?: return
        if (registered) return
        try {
            manager.registerDefaultNetworkCallback(callback)
            registered = true
        } catch (e: Exception) {
            SalesLog.warn(SalesLog.Category.SDK, "NetworkMonitor.start failed: ${e.message}")
        }
    }

    fun stop() {
        val manager = cm ?: return
        if (!registered) return
        try {
            manager.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
        registered = false
    }
}
