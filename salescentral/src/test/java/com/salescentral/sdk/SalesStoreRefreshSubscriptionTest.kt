package com.salescentral.sdk

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SalesStore.refreshSubscription] (SDK 1.4.1): the store's `user` is
 * rebuilt from the client's snapshot, not just patched with `premium`, so a
 * grant applied on the Play observer path reaches `user.credits`.
 *
 * The observer path (`PlayBillingConnector.handleObservedPurchase`) is
 * `client.uploadObservedTransaction` → acknowledge → `store.refreshSubscription()`;
 * the upload updates `SalesClient.currentUser` and nothing in between writes
 * the store — which is exactly what these tests replay, minus Play.
 */
class SalesStoreRefreshSubscriptionTest {

    private val receipt = """{"platform":"google_play","purchaseToken":"tok-1"}"""
    private val freeSubscription = """{ "subscription": null, "premium": { "tier": "free" } }"""
    private val proSubscription = """{ "subscription": null, "premium": { "tier": "pro" } }"""

    private fun appliedResponse(balance: Int, tier: String = "free"): String = """
        {
          "applied": [ { "ok": true, "productId": "tokens_500", "transactionId": "tok-1" } ],
          "user": { "id": "u1", "premium": { "tier": "$tier" }, "credits": { "balance": $balance } }
        }
    """.trimIndent()

    /** A bootstrapped store: user u1 with 100 credits, free tier. */
    private suspend fun bootstrappedStore(transport: FakeTransport): SalesStore {
        val store = SalesStore(TestFixtures.client(transport))
        transport.enqueue(200, TestFixtures.bundleJson(balance = 100))
        transport.enqueue(200, freeSubscription)
        store.bootstrap(UserContext())
        assertEquals(100, store.creditBalance)
        return store
    }

    @Test
    fun `an observer-path grant reaches user credits on the next refresh`() = runTest {
        val transport = FakeTransport()
        val store = bootstrappedStore(transport)

        // Pending consumable resolving / launch sweep / purchase completing after
        // its screen is gone: the receipt is uploaded on the observer path.
        transport.enqueue(200, appliedResponse(balance = 600))
        assertTrue(store.client.uploadObservedTransaction("tok-1", receipt))
        assertEquals("the upload updates the client only", 600, store.client.currentUser?.credits?.balance)
        assertEquals("…the store has not been told yet", 100, store.creditBalance)

        transport.enqueue(200, freeSubscription)
        store.refreshSubscription()

        assertEquals("the grant is published", 600, store.creditBalance)
        assertEquals("u1", store.user.value?.id)
        assertEquals("free", store.subscription.value?.premium?.tier)
    }

    @Test
    fun `premium comes from the refresh GET, never from the client snapshot`() = runTest {
        val transport = FakeTransport()
        val store = bootstrappedStore(transport)
        // The client's snapshot still says free (the apply response predates the
        // server-side upgrade); the subscription read is the source of truth.
        transport.enqueue(200, appliedResponse(balance = 600, tier = "free"))
        assertTrue(store.client.uploadObservedTransaction("tok-1", receipt))

        transport.enqueue(200, proSubscription)
        store.refreshSubscription()

        assertEquals(600, store.creditBalance)
        assertEquals("pro", store.user.value?.premium?.tier)
        assertTrue(store.isPaid)
        assertEquals("pro", store.subscription.value?.premium?.tier)
    }

    @Test
    fun `a failed refresh leaves user and subscription untouched`() = runTest {
        val transport = FakeTransport()
        val store = bootstrappedStore(transport)
        transport.enqueue(200, appliedResponse(balance = 600))
        assertTrue(store.client.uploadObservedTransaction("tok-1", receipt))

        transport.enqueue(500, """{ "ok": false, "error": "internal_error" }""")
        store.refreshSubscription()

        assertEquals("not adopted on a failed GET — premium would regress with it", 100, store.creditBalance)
        assertEquals("free", store.subscription.value?.premium?.tier)
        assertNull(store.lastError.value)
    }

    @Test
    fun `without a client snapshot the store user is kept and only premium applied`() = runTest {
        val transport = FakeTransport()
        val store = bootstrappedStore(transport)
        store.client.clearUser()
        assertNull(store.client.currentUser)

        transport.enqueue(200, proSubscription)
        store.refreshSubscription()

        assertEquals("u1", store.user.value?.id)
        assertEquals(100, store.creditBalance)
        assertEquals("pro", store.user.value?.premium?.tier)
    }

    @Test
    fun `analyticsOnly short-circuits before any request`() = runTest {
        val transport = FakeTransport()
        val tokenStore = InMemoryTokenStore().apply { writeServerAnalyticsOnly(true) }
        val store = SalesStore(TestFixtures.client(transport, tokenStore))

        store.refreshSubscription()

        assertTrue(transport.requests.isEmpty())
        assertFalse(store.isPaid)
        assertNull(store.user.value)
    }
}
