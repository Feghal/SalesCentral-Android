package com.salescentral.sdk

import android.net.ConnectivityManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [NetworkMonitor]'s start/stop state machine, exercised through a fake
 * [ConnectivityRegistrar] — a real `ConnectivityManager` can't be
 * constructed (package-private constructor) or mocked (no Robolectric /
 * Mockito in this module's test dependencies) from a plain JVM unit test, so
 * the seam sits one layer in from the OS call. See [NetworkMonitor]'s class
 * doc for the publish-then-register race this covers.
 */
class NetworkMonitorTest {

    private class FakeRegistrar : ConnectivityRegistrar {
        var registerCalls = 0
        var unregisterCalls = 0

        override fun register(callback: ConnectivityManager.NetworkCallback) {
            registerCalls++
        }

        override fun unregister(callback: ConnectivityManager.NetworkCallback) {
            unregisterCalls++
        }
    }

    private fun monitor(registrar: ConnectivityRegistrar): NetworkMonitor =
        NetworkMonitor(TestFixtures.fakeAndroidContext(), registrar)

    @Test
    fun `start then stop registers then unregisters once each`() {
        val registrar = FakeRegistrar()
        val m = monitor(registrar)

        m.start()
        m.stop()

        assertEquals(1, registrar.registerCalls)
        assertEquals(1, registrar.unregisterCalls)
    }

    @Test
    fun `start is idempotent — a second call does not re-register`() {
        val registrar = FakeRegistrar()
        val m = monitor(registrar)

        m.start()
        m.start()

        assertEquals(1, registrar.registerCalls)
    }

    @Test
    fun `stop is idempotent — a second call does not double-unregister`() {
        val registrar = FakeRegistrar()
        val m = monitor(registrar)

        m.start()
        m.stop()
        m.stop()

        assertEquals(1, registrar.unregisterCalls)
    }

    @Test
    fun `stop before start makes the later start a no-op — the publish-then-register race`() {
        val registrar = FakeRegistrar()
        val m = monitor(registrar)

        // SalesClient.startOutboxReconnectMonitorIfNeeded publishes a
        // freshly-created monitor under `lock`, then calls start() OUTSIDE
        // it (registering is a binder call). A concurrent drain pass that
        // empties the outbox in that same window calls stop() on the same
        // instance, also outside `lock` — reproduced here by simply calling
        // stop() first. Before this fix, stop() would no-op (nothing
        // registered yet) and the later start() would register a callback
        // nothing would ever unregister.
        m.stop()
        m.start()

        assertEquals(0, registrar.registerCalls)
        assertEquals(0, registrar.unregisterCalls)
    }

    @Test
    fun `stop before start leaves a truly later start still a no-op`() {
        val registrar = FakeRegistrar()
        val m = monitor(registrar)

        m.stop()
        m.start()
        m.start() // even a retry attempt must not resurrect a stopped monitor

        assertEquals(0, registrar.registerCalls)
    }
}
