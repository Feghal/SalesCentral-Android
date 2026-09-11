package com.salescentral.sdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The analytics outbox as driven by [SalesClient]: enqueue-only calls,
 * enqueue-time `occurredAt`, token-checked sends, requeue-at-front on
 * retryable failures, permanent drops, single-flight flushes, and the
 * automatic triggers (user established / next enqueue).
 *
 * Tests that only need `flush()` use [TestFixtures.parkedScope] so the
 * automatic drain never interferes; tests that PROVE the automatic drain
 * pass a scope on `runTest`'s own scheduler ([TestFixtures.scheduledScope])
 * and run it explicitly with `advanceUntilIdle()`. (`backgroundScope` is
 * deliberately not used: `advanceUntilIdle` only drives its tasks while
 * FOREGROUND work is pending, which would leave the drain parked.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SalesClientOutboxTest {

    /** Test clock: `now` is whatever the test last set it to. */
    private class MutableClock(@Volatile var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    private val recordEventSuffix = "/111111111111"
    private val recordSessionSuffix = "/ffffffffffff"

    private fun eventNames(req: FakeTransport.Recorded): List<String> {
        val events = JSONObject(req.body!!).getJSONArray("events")
        return (0 until events.length()).map { events.getJSONObject(it).getString("name") }
    }

    // ------------------------------------------------------------------
    // Enqueue-only
    // ------------------------------------------------------------------

    @Test
    fun `track returns without any transport call even when a user token exists`() = runTest {
        val transport = FakeTransport()
        val client = TestFixtures.client(transport, outboxScope = TestFixtures.parkedScope())
        transport.enqueue(200, TestFixtures.bundleJson())
        client.ensureUser()

        client.track("chat_send", mapOf("model" to "gpt"))
        client.trackBatch(listOf(SalesClient.SalesEvent("a"), SalesClient.SalesEvent("b")))
        client.recordSession(Instant.now(), Instant.now(), 0)

        // Only ensureUser reached the transport; four items wait in the outbox.
        assertEquals(1, transport.requests.size)
        assertEquals(4, client.pendingAnalyticsCount)
    }

    @Test
    fun `track before a user exists queues without a doomed request`() = runTest {
        val transport = FakeTransport()
        val client = TestFixtures.client(transport, outboxScope = TestFixtures.parkedScope())

        client.track("app_launch")
        assertEquals(FlushResult.Retryable("no user token yet"), client.flush())

        assertEquals(0, transport.requests.size)
        assertEquals(1, client.pendingAnalyticsCount)
    }

    // ------------------------------------------------------------------
    // Timestamps
    // ------------------------------------------------------------------

    @Test
    fun `occurredAt is the enqueue time not the flush time`() = runTest {
        val transport = FakeTransport()
        val t0 = Instant.parse("2026-09-11T10:00:00Z")
        val clock = MutableClock(t0)
        val client = TestFixtures.client(transport, clock = clock, outboxScope = TestFixtures.parkedScope())

        client.track("app_launch") // no user yet → queued
        clock.now = t0.plusSeconds(90) // bootstrap takes a while…
        transport.enqueue(200, TestFixtures.bundleJson())
        client.ensureUser()
        clock.now = t0.plusSeconds(120) // …and the flush happens later still
        transport.enqueue(200, """{ "ok": true }""")
        assertEquals(FlushResult.Delivered(1), client.flush())

        val event = JSONObject(transport.requests.last().body!!).getJSONArray("events").getJSONObject(0)
        assertEquals("app_launch", event.getString("name"))
        assertEquals("2026-09-11T10:00:00Z", event.getString("occurredAt"))
    }

    @Test
    fun `trackBatch keeps each event's own occurredAt on the wire`() = runTest {
        val transport = FakeTransport()
        val client = TestFixtures.client(transport, outboxScope = TestFixtures.parkedScope())
        transport.enqueue(200, TestFixtures.bundleJson())
        client.ensureUser()

        client.trackBatch(
            listOf(
                SalesClient.SalesEvent("a", occurredAt = Instant.parse("2026-09-11T10:00:00Z")),
                SalesClient.SalesEvent("b", occurredAt = Instant.parse("2026-09-11T10:00:05Z")),
            ),
        )
        transport.enqueue(200, """{ "ok": true }""")
        assertEquals(FlushResult.Delivered(2), client.flush())

        val events = JSONObject(transport.requests.last().body!!).getJSONArray("events")
        assertEquals("2026-09-11T10:00:00Z", events.getJSONObject(0).getString("occurredAt"))
        assertEquals("2026-09-11T10:00:05Z", events.getJSONObject(1).getString("occurredAt"))
    }

    // ------------------------------------------------------------------
    // Ordering + cap
    // ------------------------------------------------------------------

    @Test
    fun `events and sessions are delivered in FIFO order`() = runTest {
        val transport = FakeTransport()
        val client = TestFixtures.client(transport, outboxScope = TestFixtures.parkedScope())

        client.track("a")
        client.recordSession(Instant.parse("2026-09-11T10:00:00Z"), Instant.parse("2026-09-11T10:01:00Z"), 60)
        client.track("b")

        transport.enqueue(200, TestFixtures.bundleJson())
        client.ensureUser()
        repeat(3) { transport.enqueue(200, """{ "ok": true }""") }
        assertEquals(FlushResult.Delivered(3), client.flush())

        val sent = transport.requests.drop(1)
        assertEquals(3, sent.size)
        assertTrue(sent[0].url.endsWith(recordEventSuffix))
        assertEquals(listOf("a"), eventNames(sent[0]))
        assertTrue(sent[1].url.endsWith(recordSessionSuffix))
        assertTrue(sent[2].url.endsWith(recordEventSuffix))
        assertEquals(listOf("b"), eventNames(sent[2]))
    }

    @Test
    fun `cap 500 drops the oldest and the rest flush in 50-event chunks`() = runTest {
        val transport = FakeTransport()
        val client = TestFixtures.client(transport, outboxScope = TestFixtures.parkedScope())
        transport.enqueue(200, TestFixtures.bundleJson())
        client.ensureUser()

        repeat(501) { client.track("e$it") }
        assertEquals(500, client.pendingAnalyticsCount)

        repeat(10) { transport.enqueue(200, """{ "ok": true }""") }
        assertEquals(FlushResult.Delivered(500), client.flush())

        val sent = transport.requests.drop(1)
        assertEquals(10, sent.size)
        assertEquals("e1", eventNames(sent.first()).first()) // e0 was the oldest → dropped
        assertEquals(50, eventNames(sent.first()).size)
        assertEquals("e500", eventNames(sent.last()).last())
        assertEquals(0, client.pendingAnalyticsCount)
    }

    // ------------------------------------------------------------------
    // Failure handling
    // ------------------------------------------------------------------

    @Test
    fun `5xx requeues the batch at the front and the retry preserves order`() = runTest {
        val transport = FakeTransport()
        val client = TestFixtures.client(transport, outboxScope = TestFixtures.parkedScope())
        transport.enqueue(200, TestFixtures.bundleJson())
        client.ensureUser()

        client.track("a")
        client.track("b")
        transport.enqueue(503, """{ "error": "unavailable" }""")
        assertEquals(FlushResult.Retryable("HTTP 503 unavailable"), client.flush())
        assertEquals(2, client.pendingAnalyticsCount)

        client.track("c") // arrives behind the requeued batch
        transport.enqueue(200, """{ "ok": true }""")
        assertEquals(FlushResult.Delivered(3), client.flush())

        assertEquals(listOf("a", "b", "c"), eventNames(transport.requests.last()))
        assertEquals(0, client.pendingAnalyticsCount)
    }

    @Test
    fun `network failure is retryable and nothing is lost`() = runTest {
        val transport = FakeTransport()
        val client = TestFixtures.client(transport, outboxScope = TestFixtures.parkedScope())
        transport.enqueue(200, TestFixtures.bundleJson())
        client.ensureUser()

        client.track("a")
        transport.enqueueNetworkFailure("offline")
        assertEquals(FlushResult.Retryable("Network error: offline"), client.flush())
        assertEquals(1, client.pendingAnalyticsCount)

        transport.enqueue(200, """{ "ok": true }""")
        assertEquals(FlushResult.Delivered(1), client.flush())
        assertEquals(listOf("a"), eventNames(transport.requests.last()))
    }

    @Test
    fun `permanent 400 drops its batch with the rest still delivered`() = runTest {
        val transport = FakeTransport()
        val client = TestFixtures.client(transport, outboxScope = TestFixtures.parkedScope())
        transport.enqueue(200, TestFixtures.bundleJson())
        client.ensureUser()

        repeat(60) { client.track("e$it") } // two chunks: e0..e49, e50..e59
        transport.enqueue(400, """{ "error": "no_valid_events" }""")
        transport.enqueue(200, """{ "ok": true }""")
        assertEquals(FlushResult.Delivered(10), client.flush())

        val sent = transport.requests.drop(1)
        assertEquals(2, sent.size)
        assertEquals("e50", eventNames(sent[1]).first())
        assertEquals(0, client.pendingAnalyticsCount) // the 400 batch is gone, not retried
    }

    @Test
    fun `a pass that only hit permanent rejections reports Permanent`() = runTest {
        val transport = FakeTransport()
        val client = TestFixtures.client(transport, outboxScope = TestFixtures.parkedScope())
        transport.enqueue(200, TestFixtures.bundleJson())
        client.ensureUser()

        client.track("bad")
        transport.enqueue(400, """{ "error": "no_valid_events" }""")
        assertEquals(FlushResult.Permanent("HTTP 400 no_valid_events"), client.flush())
        assertEquals(0, client.pendingAnalyticsCount)
        assertEquals(FlushResult.NothingToSend, client.flush())
    }

    @Test
    fun `401 requeues and nothing is sent again until a token exists`() = runTest {
        val transport = FakeTransport()
        val store = InMemoryTokenStore()
        val client = TestFixtures.client(transport, store, outboxScope = TestFixtures.parkedScope())
        transport.enqueue(200, TestFixtures.bundleJson())
        client.ensureUser()

        client.track("a")
        transport.enqueue(401, """{ "error": "invalid_user_token" }""")
        assertEquals(FlushResult.Retryable("HTTP 401 invalid_user_token"), client.flush())
        assertNull(store.read()) // the 401 wiped the session token…
        assertEquals(1, client.pendingAnalyticsCount) // …but the event is kept

        // No token → no request at all, not another 401.
        assertEquals(FlushResult.Retryable("no user token yet"), client.flush())
        assertEquals(2, transport.requests.size)

        transport.enqueue(200, TestFixtures.bundleJson())
        client.ensureUser()
        transport.enqueue(200, """{ "ok": true }""")
        assertEquals(FlushResult.Delivered(1), client.flush())
        assertEquals(listOf("a"), eventNames(transport.requests.last()))
        assertEquals("jwt-1", transport.requests.last().headers["x-user-token"])
    }

    @Test
    fun `2xx with an undecodable body counts as delivered`() = runTest {
        val transport = FakeTransport()
        val client = TestFixtures.client(transport, outboxScope = TestFixtures.parkedScope())
        transport.enqueue(200, TestFixtures.bundleJson())
        client.ensureUser()

        client.track("a")
        transport.enqueue(200, "not json")
        assertEquals(FlushResult.Delivered(1), client.flush())
        assertEquals(0, client.pendingAnalyticsCount)
    }

    @Test
    fun `clearUser empties the outbox`() = runTest {
        val transport = FakeTransport()
        val client = TestFixtures.client(transport, outboxScope = TestFixtures.parkedScope())
        client.track("a")
        client.recordSession(Instant.now(), Instant.now(), 0)
        assertEquals(2, client.pendingAnalyticsCount)

        client.clearUser()

        assertEquals(0, client.pendingAnalyticsCount)
        assertEquals(FlushResult.NothingToSend, client.flush())
    }

    // ------------------------------------------------------------------
    // Single-flight
    // ------------------------------------------------------------------

    @Test
    fun `two concurrent flush triggers share one in-flight request`() = runTest {
        val transport = FakeTransport()
        val client = TestFixtures.client(transport, outboxScope = TestFixtures.parkedScope())
        transport.enqueue(200, TestFixtures.bundleJson())
        client.ensureUser()

        client.track("a")
        val gate = CompletableDeferred<Unit>()
        transport.enqueueGated(200, """{ "ok": true }""", gate)

        val first = async { client.flush() }
        val second = async { client.flush() }
        advanceUntilIdle() // first is in flight (parked on the gate); second waits on the mutex

        assertEquals(2, transport.requests.size) // ensureUser + exactly ONE event request
        assertTrue(first.isActive && second.isActive)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(FlushResult.Delivered(1), first.await())
        assertEquals(FlushResult.NothingToSend, second.await())
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `an enqueue during an in-flight pass is delivered by that pass in order`() = runTest {
        val transport = FakeTransport()
        val client = TestFixtures.client(transport, outboxScope = TestFixtures.parkedScope())
        transport.enqueue(200, TestFixtures.bundleJson())
        client.ensureUser()

        client.track("a")
        val gate = CompletableDeferred<Unit>()
        transport.enqueueGated(200, """{ "ok": true }""", gate)
        val pass = async { client.flush() }
        advanceUntilIdle()

        client.track("b") // lands behind the in-flight batch
        transport.enqueue(200, """{ "ok": true }""")
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(FlushResult.Delivered(2), pass.await())
        val sent = transport.requests.drop(1)
        assertEquals(listOf(listOf("a"), listOf("b")), sent.map(::eventNames))
    }

    // ------------------------------------------------------------------
    // Automatic triggers
    // ------------------------------------------------------------------

    @Test
    fun `an enqueue triggers the automatic drain with no explicit flush`() = runTest {
        val transport = FakeTransport()
        val client = TestFixtures.client(transport, outboxScope = TestFixtures.scheduledScope(testScheduler))
        transport.enqueue(200, TestFixtures.bundleJson())
        client.ensureUser()

        transport.enqueue(200, """{ "ok": true }""")
        client.track("a")
        assertEquals(1, transport.requests.size) // still enqueue-only on the caller's path

        advanceUntilIdle() // lets the SDK's drain coroutine run

        assertEquals(2, transport.requests.size)
        assertEquals(listOf("a"), eventNames(transport.requests.last()))
        assertEquals(0, client.pendingAnalyticsCount)
    }

    @Test
    fun `events queued before bootstrap flush automatically once ensureUser succeeds`() = runTest {
        val transport = FakeTransport()
        val client = TestFixtures.client(transport, outboxScope = TestFixtures.scheduledScope(testScheduler))

        client.track("app_launch")
        client.track("first_open")
        advanceUntilIdle() // the drain runs, finds no token, sends nothing
        assertEquals(0, transport.requests.size)
        assertEquals(2, client.pendingAnalyticsCount)

        transport.enqueue(200, TestFixtures.bundleJson())
        transport.enqueue(200, """{ "ok": true }""")
        client.ensureUser()
        advanceUntilIdle() // the post-bootstrap trigger drains both in one batch

        assertEquals(2, transport.requests.size)
        assertEquals(listOf("app_launch", "first_open"), eventNames(transport.requests.last()))
        assertEquals(0, client.pendingAnalyticsCount)
    }

    @Test
    fun `restorePurchases also triggers the drain`() = runTest {
        val transport = FakeTransport()
        val client = TestFixtures.client(transport, outboxScope = TestFixtures.scheduledScope(testScheduler))
        client.track("a")

        transport.enqueue(
            200,
            """
            {
              "token": "jwt-2",
              "restored": true,
              "applied": [ { "ok": true } ],
              "user": { "id": "u2", "premium": { "tier": "pro" }, "credits": { "balance": 0 } }
            }
            """.trimIndent(),
        )
        transport.enqueue(200, """{ "ok": true }""")
        client.restorePurchases(receipts = listOf("""{"platform":"google_play"}"""))
        advanceUntilIdle()

        assertEquals(2, transport.requests.size)
        assertEquals("jwt-2", transport.requests.last().headers["x-user-token"])
        assertEquals(0, client.pendingAnalyticsCount)
    }

    // ------------------------------------------------------------------
    // Reconnect monitor
    // ------------------------------------------------------------------

    /**
     * Records start/stop calls so a test can prove the monitor's lifecycle
     * without a real `ConnectivityManager` (see [ConnectivityRegistrar]'s
     * doc for why one can't be constructed in this module's unit tests).
     */
    private class SpyReconnectMonitor : ReconnectMonitor {
        override var onReconnect: (() -> Unit)? = null
        var startCalls = 0
        var stopCalls = 0

        override fun start() {
            startCalls++
        }

        override fun stop() {
            stopCalls++
        }
    }

    // These tests seed the token store directly (`InMemoryTokenStore("jwt-1")`)
    // instead of calling `ensureUser()`: with a non-null `androidContext` (needed
    // so `startOutboxReconnectMonitorIfNeeded` runs past its `androidContext ?:
    // return` guard), `ensureUser()` collects a `UserContext` from it, and
    // `DeviceContext.isEmulator()` dereferences `Build.FINGERPRINT` — always null
    // under this module's plain-JVM `unitTests.isReturnDefaultValues` stub jar,
    // regardless of this fix. Unrelated to the reconnect monitor; sidestepped
    // rather than fixed here.

    @Test
    fun `a working transport never starts the reconnect monitor`() = runTest {
        val transport = FakeTransport()
        val spy = SpyReconnectMonitor()
        var factoryCalls = 0
        val client = TestFixtures.client(
            transport,
            InMemoryTokenStore("jwt-1"),
            outboxScope = TestFixtures.parkedScope(),
            androidContext = TestFixtures.fakeAndroidContext(),
            networkMonitorFactory = { factoryCalls++; spy },
        )

        client.track("a")
        // The caller's path (enqueue) never touches the monitor — proven
        // before any drain has even run.
        assertEquals(0, factoryCalls)
        assertEquals(0, spy.startCalls)

        transport.enqueue(200, """{ "ok": true }""")
        assertEquals(FlushResult.Delivered(1), client.flush())

        // A successful drain never hit a Retryable return, so it still
        // never touched the monitor.
        assertEquals(0, factoryCalls)
        assertEquals(0, spy.startCalls)
    }

    @Test
    fun `a 5xx pass starts the reconnect monitor`() = runTest {
        val transport = FakeTransport()
        val spy = SpyReconnectMonitor()
        val client = TestFixtures.client(
            transport,
            InMemoryTokenStore("jwt-1"),
            outboxScope = TestFixtures.parkedScope(),
            androidContext = TestFixtures.fakeAndroidContext(),
            networkMonitorFactory = { spy },
        )

        client.track("a")
        transport.enqueue(503, """{ "error": "unavailable" }""")
        assertEquals(FlushResult.Retryable("HTTP 503 unavailable"), client.flush())

        assertEquals(1, spy.startCalls)
        assertEquals(0, spy.stopCalls)
    }

    @Test
    fun `a subsequent successful drain stops the reconnect monitor`() = runTest {
        val transport = FakeTransport()
        val spy = SpyReconnectMonitor()
        val client = TestFixtures.client(
            transport,
            InMemoryTokenStore("jwt-1"),
            outboxScope = TestFixtures.parkedScope(),
            androidContext = TestFixtures.fakeAndroidContext(),
            networkMonitorFactory = { spy },
        )

        client.track("a")
        transport.enqueue(503, """{ "error": "unavailable" }""")
        assertEquals(FlushResult.Retryable("HTTP 503 unavailable"), client.flush())
        assertEquals(1, spy.startCalls)
        assertEquals(0, spy.stopCalls)

        transport.enqueue(200, """{ "ok": true }""")
        assertEquals(FlushResult.Delivered(1), client.flush())

        assertEquals(1, spy.startCalls) // not started again — already running
        assertEquals(1, spy.stopCalls)
    }

    @Test
    fun `no user token yet is retryable and also starts the reconnect monitor`() = runTest {
        val transport = FakeTransport()
        val spy = SpyReconnectMonitor()
        val client = TestFixtures.client(
            transport,
            outboxScope = TestFixtures.parkedScope(),
            androidContext = TestFixtures.fakeAndroidContext(),
            networkMonitorFactory = { spy },
        )

        client.track("app_launch")
        assertEquals(FlushResult.Retryable("no user token yet"), client.flush())

        assertEquals(1, spy.startCalls)
        assertEquals(0, transport.requests.size)
    }
}
