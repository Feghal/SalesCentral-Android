package com.salescentral.sdk

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SalesStore.restorePurchasesResult] (SDK 1.4.0): one restore code path
 * that reports THIS call's outcome instead of collapsing every failure into
 * the store-wide `lastError`, and the legacy [SalesStore.restorePurchases]
 * wrapper keeping its never-throws / sets-`lastError` contract on top of it.
 */
class SalesStoreRestoreTest {

    private val receipt = """{"platform":"google_play","purchaseToken":"tok-1"}"""

    private fun restoreResponse(restored: Boolean, userId: String = "u2", tier: String = "pro"): String = """
        {
          "token": "jwt-2",
          "restored": $restored,
          "applied": [ { "ok": true, "productId": "p1" } ],
          "user": { "id": "$userId", "premium": { "tier": "$tier" }, "credits": { "balance": 0 } },
          "products": [ { "productId": "p1", "type": "subscription" } ]
        }
    """.trimIndent()

    private val subscriptionResponse = """{ "subscription": null, "premium": { "tier": "pro" } }"""

    /** A store whose client will hand `restoreUser` the given device receipts. */
    private fun storeWithReceipts(transport: FakeTransport, receipts: List<String>): SalesStore {
        val client = TestFixtures.client(transport)
        client.receiptsProvider = { receipts }
        return SalesStore(client)
    }

    @Test
    fun `success with receipts is Completed and mirrors user products and subscription`() = runTest {
        val transport = FakeTransport()
        val store = storeWithReceipts(transport, listOf(receipt))
        transport.enqueue(200, restoreResponse(restored = true))
        transport.enqueue(200, subscriptionResponse)

        val outcome = store.restorePurchasesResult()

        val completed = outcome as RestorePurchasesOutcome.Completed
        assertTrue("restored is passed through as served", completed.result.restored)
        assertEquals(1, completed.result.applied.size)
        assertNotNull(completed.subscription)
        assertNull(completed.subscriptionRefreshError)
        // Mirrored synchronously, before the call returned.
        assertEquals("u2", store.user.value?.id)
        assertEquals(listOf("p1"), store.products.value.map { it.productId })
        assertEquals("pro", store.subscription.value?.premium?.tier)
        assertNull(store.lastError.value)
        assertTrue(transport.requests[0].url.endsWith("/bbbbbbbbbbbb"))
        assertTrue(transport.requests[1].url.endsWith("/dddddddddddd"))
    }

    @Test
    fun `restored false is still Completed — the no-owner case is a success not a failure`() = runTest {
        val transport = FakeTransport()
        val store = storeWithReceipts(transport, listOf(receipt))
        transport.enqueue(200, restoreResponse(restored = false, tier = "free"))
        transport.enqueue(200, """{ "subscription": null, "premium": { "tier": "free" } }""")

        val outcome = store.restorePurchasesResult()

        val completed = outcome as RestorePurchasesOutcome.Completed
        assertFalse(completed.result.restored)
        assertNull(completed.subscriptionRefreshError)
        assertNull(store.lastError.value)
    }

    @Test
    fun `no receipts on the device falls back to createOrFetch and answers restored false`() = runTest {
        val transport = FakeTransport()
        val store = storeWithReceipts(transport, emptyList())
        transport.enqueue(200, TestFixtures.bundleJson())
        transport.enqueue(200, """{ "subscription": null, "premium": { "tier": "free" } }""")

        val outcome = store.restorePurchasesResult()

        val completed = outcome as RestorePurchasesOutcome.Completed
        assertFalse(completed.result.restored)
        assertTrue(completed.result.applied.isEmpty())
        assertEquals("u1", store.user.value?.id)
        // The one restore-side request went to createOrFetchUser, never restoreUser.
        assertTrue(transport.requests[0].url.endsWith("/aaaaaaaaaaaa"))
        assertTrue(transport.requests.none { it.url.endsWith("/bbbbbbbbbbbb") })
    }

    @Test
    fun `subscription refresh failure after a successful restore is Completed with the error attached`() = runTest {
        val transport = FakeTransport()
        val store = storeWithReceipts(transport, listOf(receipt))
        transport.enqueue(200, restoreResponse(restored = true))
        transport.enqueue(500, """{ "ok": false, "error": "internal_error" }""")

        val outcome = store.restorePurchasesResult()

        val completed = outcome as RestorePurchasesOutcome.Completed
        assertTrue(completed.result.restored)
        assertNull("a failed GET is not a 'no subscription' answer", completed.subscription)
        val err = completed.subscriptionRefreshError as SalesError.Http
        assertEquals(500, err.status)
        assertEquals("internal_error", err.errorCode)
        // The restore itself still landed: user mirrored, subscription nulled as before,
        // and nothing recorded in lastError — the restore did not fail.
        assertEquals("u2", store.user.value?.id)
        assertNull(store.subscription.value)
        assertNull(store.lastError.value)
    }

    @Test
    fun `restoreUser 4xx is Failed(Http) with user untouched and lastError set`() = runTest {
        val transport = FakeTransport()
        val store = storeWithReceipts(transport, listOf(receipt))
        // Establish a user first so "untouched" is observable.
        transport.enqueue(200, TestFixtures.bundleJson(userId = "u1"))
        transport.enqueue(200, """{ "subscription": null, "premium": { "tier": "free" } }""")
        store.bootstrap(UserContext())
        assertEquals("u1", store.user.value?.id)
        transport.enqueue(400, """{ "ok": false, "error": "invalid_receipt", "message": "bad signature" }""")

        val outcome = store.restorePurchasesResult()

        val failed = outcome as RestorePurchasesOutcome.Failed
        val err = failed.error as SalesError.Http
        assertEquals(400, err.status)
        assertEquals("invalid_receipt", err.code)
        assertTrue(err.isClientError)
        assertEquals("u1", store.user.value?.id)
        assertEquals("the same error is visible on lastError", failed.error, store.lastError.value)
    }

    @Test
    fun `transport failure is Failed(Network) and lastError set`() = runTest {
        val transport = FakeTransport()
        val store = storeWithReceipts(transport, listOf(receipt))
        transport.enqueueNetworkFailure("offline")

        val outcome = store.restorePurchasesResult()

        val failed = outcome as RestorePurchasesOutcome.Failed
        assertTrue(failed.error is SalesError.Network)
        assertEquals(failed.error, store.lastError.value)
        assertNull(store.user.value)
    }

    @Test
    fun `server analyticsOnly cached true is Failed(InvalidState analytics_only) without any request`() = runTest {
        val transport = FakeTransport()
        val tokenStore = InMemoryTokenStore().apply { writeServerAnalyticsOnly(true) }
        val store = SalesStore(TestFixtures.client(transport, tokenStore))

        val outcome = store.restorePurchasesResult()

        val failed = outcome as RestorePurchasesOutcome.Failed
        assertTrue(failed.error is SalesError.InvalidState)
        assertEquals("Invalid state: analytics_only", failed.error.message)
        assertEquals(failed.error, store.lastError.value)
        assertTrue("the guard never hits the network", transport.requests.isEmpty())
    }

    @Test
    fun `legacy restorePurchases returns normally on failure and sets lastError`() = runTest {
        val transport = FakeTransport()
        val store = storeWithReceipts(transport, listOf(receipt))
        transport.enqueue(400, """{ "ok": false, "error": "invalid_receipt" }""")

        store.restorePurchases()

        val err = store.lastError.value as SalesError.Http
        assertEquals("invalid_receipt", err.errorCode)
        assertNull(store.user.value)
    }

    @Test
    fun `legacy restorePurchases still mirrors state on success`() = runTest {
        val transport = FakeTransport()
        val store = storeWithReceipts(transport, listOf(receipt))
        transport.enqueue(200, restoreResponse(restored = true))
        transport.enqueue(200, subscriptionResponse)

        store.restorePurchases()

        assertEquals("u2", store.user.value?.id)
        assertEquals("pro", store.subscription.value?.premium?.tier)
        assertNull(store.lastError.value)
    }
}
