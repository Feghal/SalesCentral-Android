package com.salescentral.sdk

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SalesClientTest {

    // ------------------------------------------------------------------
    // ensureUser / bundle absorption
    // ------------------------------------------------------------------

    @Test
    fun `ensureUser sends app key and unattested signal and stores the token`() = runTest {
        val transport = FakeTransport()
        val store = InMemoryTokenStore()
        val client = TestFixtures.client(transport, store)
        transport.enqueue(
            200,
            TestFixtures.bundleJson(
                extras = """
                    ,
                    "products": [ { "productId": "com.foo.pro", "type": "subscription" } ],
                    "paywalls": [ { "key": "main", "name": "Main", "productIds": ["com.foo.pro"] } ],
                    "remoteConfig": { "cta": "Go" },
                    "experimentAssignments": { "exp1": "variantB" },
                    "retention": { "enabled": true, "available": true }
                """.trimIndent(),
            ),
        )

        val user = client.ensureUser()

        assertEquals("u1", user.id)
        assertEquals("jwt-1", store.read())
        val req = transport.requests.single()
        assertEquals("POST", req.method)
        assertTrue(req.url.endsWith("/aaaaaaaaaaaa"))
        assertEquals("csk_test", req.headers["x-app-key"])
        // No user token on first launch.
        assertNull(req.headers["x-user-token"])
        // createOrFetchUser is an asserted endpoint; without a platform
        // attest service the SDK signals unattested explicitly.
        assertEquals("1", req.headers["x-attest-unsupported"])
        // The stable clientId rides along for idempotent creation.
        val body = JSONObject(req.body!!)
        assertEquals(store.readClientId(), body.getString("clientId"))

        // Bundle absorbed into the caches.
        assertEquals(listOf("com.foo.pro"), client.configuredProductIds)
        assertEquals("Main", client.paywallsByKey["main"]?.name)
        assertEquals("Go", client.remoteConfig("cta", "fallback"))
        assertEquals(mapOf("exp1" to "variantB"), client.activeExperiments())
        assertEquals(true, client.retentionStatus?.available)
    }

    // Mirrors OfflineUserCreationTests.swift — a failed create keeps the
    // clientId so the retry resolves to the SAME server user.
    @Test
    fun `offline first launch retries with the same clientId`() = runTest {
        val transport = FakeTransport()
        val store = InMemoryTokenStore()
        val client = TestFixtures.client(transport, store)

        transport.enqueueNetworkFailure()
        val thrown = runCatching { client.ensureUser() }.exceptionOrNull()
        assertTrue(thrown is SalesError.Network)
        val firstClientId = JSONObject(transport.requests[0].body!!).getString("clientId")
        assertNull(store.read())

        transport.enqueue(200, TestFixtures.bundleJson())
        client.ensureUser()
        val secondClientId = JSONObject(transport.requests[1].body!!).getString("clientId")
        assertEquals(firstClientId, secondClientId)
        assertEquals("jwt-1", store.read())
    }

    @Test
    fun `lean bundle does not wipe existing caches`() = runTest {
        val transport = FakeTransport()
        val client = TestFixtures.client(transport)
        transport.enqueue(
            200,
            TestFixtures.bundleJson(
                extras = """, "paywalls": [ { "key": "main", "name": "Main", "productIds": [] } ], "remoteConfig": { "a": 1 }""",
            ),
        )
        client.ensureUser()
        assertEquals(1, client.paywallsByKey.size)

        // Older/leaner response with no paywall / remoteConfig blocks.
        transport.enqueue(200, TestFixtures.bundleJson())
        client.ensureUser()
        assertEquals(1, client.paywallsByKey.size)
        assertEquals(1, client.remoteConfig("a", 0))
    }

    // ------------------------------------------------------------------
    // Credits (mirrors SpendIdempotencyTests.swift)
    // ------------------------------------------------------------------

    @Test
    fun `spendCredits passes the idempotency key and updates the cached user`() = runTest {
        val transport = FakeTransport()
        val client = TestFixtures.client(transport)
        transport.enqueue(200, TestFixtures.bundleJson(balance = 100))
        client.ensureUser()

        transport.enqueue(
            200,
            """{ "balance": 50, "locked": 0, "transactionId": "txn-9", "receipt": "jws-receipt" }""",
        )
        val credits = client.spendCredits(50, reason = "render", idempotencyKey = "intent-1")

        assertEquals(50, credits.balance)
        assertEquals("txn-9", credits.transactionId)
        assertEquals("jws-receipt", credits.receipt)
        assertEquals(50, client.currentUser?.credits?.balance)

        val body = JSONObject(transport.requests.last().body!!)
        assertEquals(50, body.getInt("amount"))
        assertEquals("render", body.getString("reason"))
        assertEquals("intent-1", body.getString("idempotencyKey"))
        assertEquals("jwt-1", transport.requests.last().headers["x-user-token"])
    }

    @Test
    fun `insufficient credits surfaces the machine code`() = runTest {
        val transport = FakeTransport()
        val client = TestFixtures.client(transport)
        transport.enqueue(200, TestFixtures.bundleJson())
        client.ensureUser()

        transport.enqueue(402, """{ "error": "insufficient_credits", "message": "not enough" }""")
        val thrown = runCatching { client.spendCredits(500, reason = "render") }.exceptionOrNull()

        val err = thrown as SalesError.Http
        assertEquals(402, err.status)
        assertEquals("insufficient_credits", err.code)
        assertTrue(err.isClientError)
    }

    // ------------------------------------------------------------------
    // User properties
    // ------------------------------------------------------------------

    @Test
    fun `null property values encode as JSON null deletes`() = runTest {
        val transport = FakeTransport()
        val client = TestFixtures.client(transport)
        transport.enqueue(200, TestFixtures.bundleJson())
        client.ensureUser()

        transport.enqueue(200, TestFixtures.bundleJson())
        client.setUserProperties(
            mapOf(
                "email" to SalesPropertyValue.of("a@b.c"),
                "orders" to SalesPropertyValue.of(3),
                "stale_key" to null,
            ),
        )

        val props = JSONObject(transport.requests.last().body!!).getJSONObject("properties")
        assertEquals("a@b.c", props.getString("email"))
        // Integer-valued numbers round-trip as integers, not 3.0.
        assertEquals(3, props.get("orders"))
        assertTrue(props.has("stale_key") && props.isNull("stale_key"))
    }

    @Test
    fun `setUserProperties without a token throws invalid state`() = runTest {
        val client = TestFixtures.client(FakeTransport())
        val thrown = runCatching {
            client.setUserProperties(mapOf("a" to SalesPropertyValue.of(1)))
        }.exceptionOrNull()
        assertTrue(thrown is SalesError.InvalidState)
    }

    // ------------------------------------------------------------------
    // 401 semantics
    // ------------------------------------------------------------------

    @Test
    fun `a 401 wipes the session but keeps the clientId`() = runTest {
        val transport = FakeTransport()
        val store = InMemoryTokenStore()
        val client = TestFixtures.client(transport, store)
        transport.enqueue(200, TestFixtures.bundleJson(extras = """, "remoteConfig": { "a": 1 }"""))
        client.ensureUser()
        val clientId = store.readClientId()
        assertNotNull(client.currentUser)

        transport.enqueue(401, """{ "error": "invalid_user_token" }""")
        val thrown = runCatching { client.currentSubscription() }.exceptionOrNull()

        assertEquals("invalid_user_token", (thrown as SalesError.Http).code)
        assertNull(store.read())
        assertNull(client.currentUser)
        assertEquals(0, client.remoteConfigCache.size)
        // A 401 is an expired session, not an identity reset.
        assertEquals(clientId, store.readClientId())
    }

    @Test
    fun `attest 401 codes do not wipe the user session`() = runTest {
        val transport = FakeTransport()
        val store = InMemoryTokenStore()
        val client = TestFixtures.client(transport, store)
        transport.enqueue(200, TestFixtures.bundleJson())
        client.ensureUser()

        transport.enqueue(401, """{ "error": "attestation_required" }""")
        runCatching { client.currentSubscription() }

        assertEquals("jwt-1", store.read())
        assertNotNull(client.currentUser)
    }

    // ------------------------------------------------------------------
    // Retention rewards
    // ------------------------------------------------------------------

    @Test
    fun `claimReward parses grant and refreshes retention plus credits`() = runTest {
        val transport = FakeTransport()
        val client = TestFixtures.client(transport)
        transport.enqueue(200, TestFixtures.bundleJson(balance = 10))
        client.ensureUser()

        transport.enqueue(
            200,
            """
            {
              "granted": { "amount": 25, "bonus": 0, "total": 25, "streakDay": 2 },
              "retention": { "enabled": true, "available": false, "reason": "already_claimed" },
              "balance": 35,
              "locked": 0
            }
            """.trimIndent(),
        )
        val result = client.claimReward()

        assertEquals(25, result.granted.total)
        assertEquals(35, client.currentUser?.credits?.balance)
        assertEquals(false, client.retentionStatus?.available)
    }

    @Test
    fun `already_claimed error body still refreshes retention status`() = runTest {
        val transport = FakeTransport()
        val client = TestFixtures.client(transport)
        transport.enqueue(200, TestFixtures.bundleJson())
        client.ensureUser()

        transport.enqueue(
            409,
            """
            {
              "error": "already_claimed",
              "retention": { "enabled": true, "available": false, "nextClaimAt": "2999-01-02T00:00:00Z" }
            }
            """.trimIndent(),
        )
        val thrown = runCatching { client.claimReward() }.exceptionOrNull()

        assertEquals("already_claimed", (thrown as SalesError.Http).code)
        // The 409 body carried authoritative state — it must be absorbed.
        assertEquals(false, client.retentionStatus?.available)
        assertNotNull(client.retentionStatus?.nextClaimAt)
    }

    @Test
    fun `claimReward without a configured token throws a descriptive error`() = runTest {
        val config = SalesConfig(
            baseUrl = "https://sales.example.com",
            apiKey = "csk_test",
            tokens = TestFixtures.tokens.copy(claimReward = null),
            tokenStore = InMemoryTokenStore(),
        )
        val client = SalesClient(config, FakeTransport())
        val thrown = runCatching { client.claimReward() }.exceptionOrNull()
        assertTrue(thrown is SalesError.InvalidState)
        assertTrue(thrown!!.message!!.contains("claimReward"))
    }

    // ------------------------------------------------------------------
    // Transaction claims (mirrors ObservedUploadTests.swift)
    // ------------------------------------------------------------------

    @Test
    fun `claimTransaction is exclusive and unclaim releases`() = runTest {
        val client = TestFixtures.client(FakeTransport())
        assertTrue(client.claimTransaction("t1"))
        assertFalse(client.claimTransaction("t1"))
        client.unclaimTransaction("t1")
        assertTrue(client.claimTransaction("t1"))
    }

    @Test
    fun `claim cap prunes oldest ids first`() = runTest {
        val client = TestFixtures.client(FakeTransport())
        for (i in 0 until 513) assertTrue(client.claimTransaction("t$i"))
        // t0 was pruned (oldest), so it is claimable again; t512 is not.
        assertTrue(client.claimTransaction("t0"))
        assertFalse(client.claimTransaction("t512"))
    }

    @Test
    fun `uploadObservedTransaction releases the claim on failure and keeps it on success`() = runTest {
        val transport = FakeTransport()
        val client = TestFixtures.client(transport)
        transport.enqueue(200, TestFixtures.bundleJson())
        client.ensureUser()

        // Failure path: server rejects → claim released for redelivery retry.
        transport.enqueue(500, """{ "error": "server_error" }""")
        assertFalse(client.uploadObservedTransaction("txn-1", "{}"))
        assertTrue(client.claimTransaction("txn-1"))
        client.unclaimTransaction("txn-1")

        // Success path: claim sticks so a duplicate delivery is skipped.
        transport.enqueue(200, """{ "applied": [ { "ok": true } ] }""")
        assertTrue(client.uploadObservedTransaction("txn-1", "{}"))
        assertFalse(client.uploadObservedTransaction("txn-1", "{}"))
    }

    @Test
    fun `applyReceipts refreshes the cached user`() = runTest {
        val transport = FakeTransport()
        val client = TestFixtures.client(transport)
        transport.enqueue(200, TestFixtures.bundleJson())
        client.ensureUser()

        transport.enqueue(
            200,
            """
            {
              "applied": [ { "ok": true, "transactionId": "t1" } ],
              "user": { "id": "u1", "premium": { "tier": "pro" }, "credits": { "balance": 500 } }
            }
            """.trimIndent(),
        )
        val result = client.applyReceipt("""{"platform":"google_play"}""")
        assertTrue(result.applied[0].ok)
        assertEquals("pro", client.currentUser?.premium?.tier)
        assertEquals(500, client.currentUser?.credits?.balance)
    }

    // ------------------------------------------------------------------
    // Paywalls / remote config / restore
    // ------------------------------------------------------------------

    @Test
    fun `paywall cache miss refreshes the bundle once`() = runTest {
        val transport = FakeTransport()
        val client = TestFixtures.client(transport)
        transport.enqueue(200, TestFixtures.bundleJson())
        client.ensureUser()

        transport.enqueue(
            200,
            TestFixtures.bundleJson(
                extras = """, "paywalls": [ { "key": "main", "name": "Main", "productIds": ["a"] } ]""",
            ),
        )
        val pw = client.paywall("main")
        assertEquals("Main", pw.name)
        assertEquals(2, transport.requests.size)

        // Still missing after refresh → InvalidState.
        transport.enqueue(200, TestFixtures.bundleJson())
        val thrown = runCatching { client.paywall("nope") }.exceptionOrNull()
        assertTrue(thrown is SalesError.InvalidState)
    }

    @Test
    fun `remoteConfig coerces types and falls back on mismatch`() = runTest {
        val transport = FakeTransport()
        val client = TestFixtures.client(transport)
        transport.enqueue(
            200,
            TestFixtures.bundleJson(
                extras = """, "remoteConfig": { "s": "hi", "i": 4, "d": 1.5, "b": true }""",
            ),
        )
        client.ensureUser()

        assertEquals("hi", client.remoteConfig("s", "x"))
        assertEquals(4, client.remoteConfig("i", 0))
        assertEquals(1.5, client.remoteConfig("d", 0.0), 0.0)
        assertEquals(true, client.remoteConfig("b", false))
        assertEquals(4.0, client.remoteConfig("i", 0.0), 0.0) // int → double coercion
        assertEquals("x", client.remoteConfig("missing", "x"))
        assertEquals(0, client.remoteConfig("s", 0)) // type mismatch → fallback
    }

    @Test
    fun `restore without receipts falls back to ensureUser`() = runTest {
        val transport = FakeTransport()
        val client = TestFixtures.client(transport)
        transport.enqueue(200, TestFixtures.bundleJson())

        val result = client.restorePurchases()

        assertFalse(result.restored)
        assertEquals("u1", result.user.id)
        // The single request went to createOrFetchUser, not restoreUser.
        assertTrue(transport.requests.single().url.endsWith("/aaaaaaaaaaaa"))
    }

    @Test
    fun `restore with receipts posts to restoreUser without a user token`() = runTest {
        val transport = FakeTransport()
        val store = InMemoryTokenStore(initial = "old-jwt")
        val client = TestFixtures.client(transport, store)

        transport.enqueue(
            200,
            """
            {
              "token": "jwt-2",
              "restored": true,
              "applied": [ { "ok": true } ],
              "user": { "id": "u2", "premium": { "tier": "pro" }, "credits": { "balance": 0 } },
              "products": [ { "productId": "p1", "type": "subscription" } ]
            }
            """.trimIndent(),
        )
        val result = client.restorePurchases(receipts = listOf("""{"platform":"google_play"}"""))

        assertTrue(result.restored)
        assertEquals("u2", result.user.id)
        assertEquals("jwt-2", store.read())
        assertEquals(listOf("p1"), client.configuredProductIds)
        val req = transport.requests.single()
        assertTrue(req.url.endsWith("/bbbbbbbbbbbb"))
        assertNull(req.headers["x-user-token"])
        assertEquals(1, JSONObject(req.body!!).getJSONArray("receipts").length())
    }

    @Test
    fun `clearUser resets identity and caches`() = runTest {
        val transport = FakeTransport()
        val store = InMemoryTokenStore()
        val client = TestFixtures.client(transport, store)
        transport.enqueue(200, TestFixtures.bundleJson(extras = """, "remoteConfig": { "a": 1 }"""))
        client.ensureUser()
        val oldClientId = store.readClientId()
        client.claimTransaction("t1")

        client.clearUser()

        assertNull(store.read())
        assertNull(store.readClientId())
        assertNull(client.currentUser)
        assertEquals(0, client.remoteConfigCache.size)
        assertTrue(client.claimTransaction("t1")) // claim set cleared

        // Next ensureUser mints a NEW clientId — a genuine identity reset.
        transport.enqueue(200, TestFixtures.bundleJson(userId = "u9"))
        client.ensureUser()
        val newClientId = JSONObject(transport.requests.last().body!!).getString("clientId")
        assertFalse(newClientId == oldClientId)
    }

    // ------------------------------------------------------------------
    // Events (SDK 1.2.0: enqueue-only — delivery is awaited via flush();
    // the outbox semantics themselves are covered in SalesClientOutboxTest)
    // ------------------------------------------------------------------

    @Test
    fun `track enqueues and flush sends name properties occurredAt in the batch form`() = runTest {
        val transport = FakeTransport()
        val client = TestFixtures.client(transport, outboxScope = TestFixtures.parkedScope())
        transport.enqueue(200, TestFixtures.bundleJson())
        client.ensureUser()

        client.track("level_completed", mapOf("score" to 8420, "won" to true))
        // Enqueue-only: the caller's path never touched the transport.
        assertEquals(1, transport.requests.size)
        assertEquals(1, client.pendingAnalyticsCount)

        // A 5xx keeps the event queued (it used to be swallowed and lost).
        transport.enqueue(500, """{ "error": "server_error" }""")
        assertEquals(FlushResult.Retryable("HTTP 500 server_error"), client.flush())
        assertEquals(1, client.pendingAnalyticsCount)

        transport.enqueue(200, """{ "ok": true }""")
        assertEquals(FlushResult.Delivered(1), client.flush())
        assertEquals(0, client.pendingAnalyticsCount)
        val events = JSONObject(transport.requests.last().body!!).getJSONArray("events")
        assertEquals(1, events.length())
        val body = events.getJSONObject(0)
        assertEquals("level_completed", body.getString("name"))
        assertEquals(8420, body.getJSONObject("properties").getInt("score"))
        assertNotNull(JsonUtil.parseDate(body.getString("occurredAt")))
        assertEquals("jwt-1", transport.requests.last().headers["x-user-token"])
    }

    @Test
    fun `trackBatch wraps events in an events array`() = runTest {
        val transport = FakeTransport()
        val client = TestFixtures.client(transport, outboxScope = TestFixtures.parkedScope())
        transport.enqueue(200, TestFixtures.bundleJson())
        client.ensureUser()

        client.trackBatch(
            listOf(
                SalesClient.SalesEvent("a"),
                SalesClient.SalesEvent("b", mapOf("k" to "v")),
            ),
        )
        transport.enqueue(200, """{ "ok": true }""")
        assertEquals(FlushResult.Delivered(2), client.flush())
        val events = JSONObject(transport.requests.last().body!!).getJSONArray("events")
        assertEquals(2, events.length())
        assertEquals("b", events.getJSONObject(1).getString("name"))
        assertEquals("v", events.getJSONObject(1).getJSONObject("properties").getString("k"))
    }

    @Test
    fun `recordSession posts start end and duration`() = runTest {
        val transport = FakeTransport()
        val client = TestFixtures.client(transport, outboxScope = TestFixtures.parkedScope())
        transport.enqueue(200, TestFixtures.bundleJson())
        client.ensureUser()

        val start = java.time.Instant.parse("2026-07-09T10:00:00Z")
        val end = java.time.Instant.parse("2026-07-09T10:05:00Z")
        client.recordSession(start, end, durationSec = 300)
        transport.enqueue(200, """{ "ok": true }""")
        assertEquals(FlushResult.Delivered(1), client.flush())

        val req = transport.requests.last()
        assertTrue(req.url.endsWith("/ffffffffffff"))
        val body = JSONObject(req.body!!)
        assertEquals("2026-07-09T10:00:00Z", body.getString("startedAt"))
        assertEquals("2026-07-09T10:05:00Z", body.getString("endedAt"))
        assertEquals(300, body.getInt("durationSec"))
    }

    // ------------------------------------------------------------------
    // Store single-flight bootstrap
    // ------------------------------------------------------------------

    @Test
    fun `concurrent ensureBootstrapped shares one createOrFetch request`() = runTest {
        val transport = FakeTransport()
        val client = TestFixtures.client(transport)
        val store = SalesStore(client)
        // One user create + one currentSubscription — a second create would
        // hit the "no response queued" assertion.
        transport.enqueue(200, TestFixtures.bundleJson())
        transport.enqueue(200, """{ "subscription": null, "premium": { "tier": "free" } }""")

        val a = async { store.ensureBootstrapped(UserContext()) }
        val b = async { store.ensureBootstrapped(UserContext()) }
        a.await(); b.await()

        assertTrue(store.didBootstrap)
        assertEquals("u1", store.user.value?.id)
        assertEquals(1, transport.requests.count { it.url.endsWith("/aaaaaaaaaaaa") })
    }

    @Test
    fun `failed bootstrap stays retryable`() = runTest {
        val transport = FakeTransport()
        val client = TestFixtures.client(transport)
        val store = SalesStore(client)

        transport.enqueueNetworkFailure()
        store.ensureBootstrapped(UserContext())
        assertFalse(store.didBootstrap)
        assertTrue(store.lastError.value is SalesError.Network)

        transport.enqueue(200, TestFixtures.bundleJson())
        transport.enqueue(200, """{ "subscription": null, "premium": { "tier": "free" } }""")
        store.ensureBootstrapped(UserContext())
        assertTrue(store.didBootstrap)
    }
}
