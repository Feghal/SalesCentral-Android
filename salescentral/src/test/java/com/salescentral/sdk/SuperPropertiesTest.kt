package com.salescentral.sdk

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Registered ("super") event properties — port of the Swift SDK's
 * `SuperPropertiesTests.swift`: they merge into every tracked event's
 * properties at track time; per-call properties override; removal/clear
 * work; and the merged set rides through the outbox.
 *
 * Every test drives delivery through `flush()` on a parked outbox scope
 * (see [SalesClientOutboxTest]'s doc for why) and reads the properties back
 * off the recorded recordEvent request body — the wire is the contract.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SuperPropertiesTest {

    private val recordEventSuffix = "/111111111111"

    /** Properties of the [index]-th event in the LAST recordEvent request. */
    private fun eventProps(transport: FakeTransport, index: Int = 0): JSONObject {
        val req = transport.requests.last { it.url.endsWith(recordEventSuffix) }
        return JSONObject(req.body!!).getJSONArray("events").getJSONObject(index).getJSONObject("properties")
    }

    /** A client with an established user, so the next `flush()` actually sends. */
    private suspend fun clientWithUser(transport: FakeTransport): SalesClient {
        val client = TestFixtures.client(transport, outboxScope = TestFixtures.parkedScope())
        transport.enqueue(200, TestFixtures.bundleJson())
        client.ensureUser()
        return client
    }

    @Test
    fun `registered property rides on every tracked event`() = runTest {
        val transport = FakeTransport()
        val client = clientWithUser(transport)

        client.setEventProperties(mapOf("plan" to "premium"))
        client.track("chat_sent")

        transport.enqueue(200, """{ "ok": true }""")
        assertEquals(FlushResult.Delivered(1), client.flush())
        assertEquals("premium", eventProps(transport).getString("plan"))
    }

    @Test
    fun `per-call property overrides a registered key`() = runTest {
        val transport = FakeTransport()
        val client = clientWithUser(transport)

        client.setEventProperty("plan", "free")
        client.track("upgrade_tapped", mapOf("plan" to "premium"))

        transport.enqueue(200, """{ "ok": true }""")
        assertEquals(FlushResult.Delivered(1), client.flush())
        assertEquals("per-call wins over the registered value", "premium", eventProps(transport).getString("plan"))
    }

    @Test
    fun `a per-call null overrides a registered value with JSON null`() = runTest {
        val transport = FakeTransport()
        val client = clientWithUser(transport)

        client.setEventProperties(mapOf("plan" to "premium"))
        client.track("e", mapOf("plan" to null))

        transport.enqueue(200, """{ "ok": true }""")
        assertEquals(FlushResult.Delivered(1), client.flush())
        val props = eventProps(transport)
        // Same encoding a null per-call property gets without any registered
        // properties: the key is present and explicitly null, not dropped and
        // not silently replaced by the registered value.
        assertTrue(props.has("plan"))
        assertTrue(props.isNull("plan"))
    }

    @Test
    fun `removeEventProperty and clearEventProperties stop attaching`() = runTest {
        val transport = FakeTransport()
        val client = clientWithUser(transport)

        client.setEventProperties(mapOf("plan" to "premium", "cohort" to "A"))
        client.removeEventProperty("plan")
        client.track("e1")
        transport.enqueue(200, """{ "ok": true }""")
        assertEquals(FlushResult.Delivered(1), client.flush())
        var props = eventProps(transport)
        assertFalse(props.has("plan"))
        assertEquals("A", props.getString("cohort"))

        client.clearEventProperties()
        client.track("e2")
        transport.enqueue(200, """{ "ok": true }""")
        assertEquals(FlushResult.Delivered(1), client.flush())
        props = eventProps(transport)
        assertFalse(props.has("cohort"))
    }

    @Test
    fun `trackBatch merges registered properties into each event`() = runTest {
        val transport = FakeTransport()
        val client = clientWithUser(transport)

        client.setEventProperties(mapOf("plan" to "premium"))
        client.trackBatch(
            listOf(
                SalesClient.SalesEvent("a"),
                SalesClient.SalesEvent("b", mapOf("plan" to "trial", "score" to 1)),
            ),
        )

        transport.enqueue(200, """{ "ok": true }""")
        assertEquals(FlushResult.Delivered(2), client.flush())
        assertEquals("premium", eventProps(transport, 0).getString("plan"))
        assertEquals("trial", eventProps(transport, 1).getString("plan"))
        assertEquals(1, eventProps(transport, 1).getInt("score"))
    }

    /**
     * Registered at track time → rides with an event that queues (no user
     * yet) and flushes after the user is established: the merge happens at
     * enqueue, not at send.
     */
    @Test
    fun `registered properties are snapshotted at track time and survive the outbox`() = runTest {
        val transport = FakeTransport()
        val client = TestFixtures.client(transport, outboxScope = TestFixtures.parkedScope())

        client.setEventProperties(mapOf("plan" to "premium"))
        client.track("queued_evt") // no token → queued, no HTTP yet
        client.clearEventProperties() // prove the value was snapshotted, not recomputed at flush
        assertEquals(0, transport.requests.size)

        transport.enqueue(200, TestFixtures.bundleJson())
        client.ensureUser()
        transport.enqueue(200, """{ "ok": true }""")
        assertEquals(FlushResult.Delivered(1), client.flush())
        assertEquals("premium", eventProps(transport).getString("plan"))
    }

    @Test
    fun `SalesStore forwards the registered-property calls to its client`() = runTest {
        val transport = FakeTransport()
        val client = clientWithUser(transport)
        val store = SalesStore(client)

        store.setEventProperties(mapOf("plan" to "premium", "cohort" to "A"))
        store.removeEventProperty("cohort")
        store.track("from_store")

        transport.enqueue(200, """{ "ok": true }""")
        assertEquals(FlushResult.Delivered(1), client.flush())
        val props = eventProps(transport)
        assertEquals("premium", props.getString("plan"))
        assertFalse(props.has("cohort"))
    }
}
