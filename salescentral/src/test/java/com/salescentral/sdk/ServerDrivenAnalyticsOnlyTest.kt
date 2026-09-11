package com.salescentral.sdk

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Server-driven analytics-only (SDK 1.3.0): the bootstrap bundle's
 * `analyticsOnly` is absorbed, cached in the TokenStore, and gates the
 * client's transaction APIs. Android has no client-side key — the server is
 * the only source.
 */
class ServerDrivenAnalyticsOnlyTest {

    private fun bundle(analyticsOnly: Boolean?): String = TestFixtures.bundleJson(
        extras = if (analyticsOnly == null) "" else """, "analyticsOnly": $analyticsOnly""",
    )

    @Test
    fun `in-memory store round-trips the server flag and identity wipes leave it alone`() {
        val store = InMemoryTokenStore()
        assertNull(store.readServerAnalyticsOnly())
        store.writeServerAnalyticsOnly(true)
        assertEquals(true, store.readServerAnalyticsOnly())
        store.writeServerAnalyticsOnly(false)
        assertEquals(false, store.readServerAnalyticsOnly())
        store.clear(); store.clearClientId()
        assertEquals(false, store.readServerAnalyticsOnly())
    }

    @Test
    fun `server true is absorbed, cached, and gates transaction APIs`() = runTest {
        val transport = FakeTransport()
        val store = InMemoryTokenStore()
        val client = TestFixtures.client(transport, store)
        assertFalse("nothing cached, no plist key on Android", client.analyticsOnly)

        transport.enqueue(200, bundle(analyticsOnly = true))
        client.ensureUser()
        assertTrue(client.analyticsOnly)
        assertEquals(true, store.readServerAnalyticsOnly())

        // No request must leave the client for a guarded call.
        val before = transport.requests.size
        for ((name, call) in listOf<Pair<String, suspend () -> Unit>>(
            "currentSubscription" to { client.currentSubscription() },
            "applyReceipts" to { client.applyReceipts(listOf("r")) },
            "spendCredits" to { client.spendCredits(1, "t") },
            "claimReward" to { client.claimReward() },
            "restorePurchases" to { client.restorePurchases(listOf("r")) },
        )) {
            val err = runCatching { call() }.exceptionOrNull()
            assertTrue("$name should throw InvalidState", err is SalesError.InvalidState)
            // SalesError.InvalidState(detail) formats its message as "Invalid state: <detail>".
            assertEquals("$name reason", "Invalid state: analytics_only", err?.message)
        }
        assertEquals("guards never hit the network", before, transport.requests.size)
    }

    @Test
    fun `absent field leaves the cached value alone`() = runTest {
        val transport = FakeTransport()
        val store = InMemoryTokenStore().apply { writeServerAnalyticsOnly(true) }
        val client = TestFixtures.client(transport, store)
        assertTrue("seeded from the cache before any request", client.analyticsOnly)
        transport.enqueue(200, bundle(analyticsOnly = null))
        client.ensureUser()
        assertTrue("older server: not a reset", client.analyticsOnly)
    }

    @Test
    fun `server false clears an earlier true`() = runTest {
        val transport = FakeTransport()
        val store = InMemoryTokenStore()
        val client = TestFixtures.client(transport, store)
        transport.enqueue(200, bundle(analyticsOnly = true))
        client.ensureUser()
        assertTrue(client.analyticsOnly)
        transport.enqueue(200, bundle(analyticsOnly = false))
        client.ensureUser()
        assertFalse(client.analyticsOnly)
        assertEquals(false, store.readServerAnalyticsOnly())
        // Machinery is back: currentSubscription goes to the network again.
        transport.enqueue(200, """{ "ok": true, "active": false, "premium": {} }""")
        client.currentSubscription()
        assertTrue(transport.requests.last().url.endsWith("/dddddddddddd"))
    }

    @Test
    fun `a fresh client over the same store reads the cache`() {
        val cached = InMemoryTokenStore().apply { writeServerAnalyticsOnly(true) }
        assertTrue(TestFixtures.client(FakeTransport(), cached).analyticsOnly)
        assertFalse(TestFixtures.client(FakeTransport(), InMemoryTokenStore()).analyticsOnly)
    }
}
