package com.salescentral.sdk

import kotlinx.coroutines.CompletableDeferred
import java.io.IOException
import java.util.Collections

/**
 * Scriptable [HttpTransport] for unit tests — the analog of the injected
 * URLSession/mocked attest service in the Swift test suite. Responses are
 * dequeued in FIFO order; every request is recorded for assertions.
 *
 * [requests] and the response queue are synchronised because the client's
 * analytics drain coroutine may call [execute] from a test-scheduler thread
 * while the test body reads [requests].
 */
class FakeTransport : HttpTransport {

    data class Recorded(
        val url: String,
        val method: String,
        val headers: Map<String, String>,
        val body: String?,
    )

    val requests: MutableList<Recorded> = Collections.synchronizedList(mutableListOf())

    private val queue = ArrayDeque<suspend () -> HttpTransport.Response>()

    fun enqueue(status: Int, body: String) {
        synchronized(queue) { queue.addLast { HttpTransport.Response(status, body.toByteArray()) } }
    }

    fun enqueueNetworkFailure(message: String = "offline") {
        synchronized(queue) { queue.addLast { throw IOException(message) } }
    }

    /**
     * A response that is only produced once [gate] completes — models a
     * request that is still in flight, so a test can observe what the client
     * does while one send is outstanding (single-flight assertions).
     */
    fun enqueueGated(status: Int, body: String, gate: CompletableDeferred<Unit>) {
        synchronized(queue) {
            queue.addLast {
                gate.await()
                HttpTransport.Response(status, body.toByteArray())
            }
        }
    }

    override suspend fun execute(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): HttpTransport.Response {
        requests.add(Recorded(url, method, headers, body?.decodeToString()))
        val next = synchronized(queue) { queue.removeFirstOrNull() }
            ?: throw AssertionError("FakeTransport: no response queued for $method $url")
        return next()
    }
}

object TestFixtures {

    val tokens = SalesConfig.Tokens(
        createOrFetchUser = "aaaaaaaaaaaa",
        restoreUser = "bbbbbbbbbbbb",
        applyPurchases = "cccccccccccc",
        currentSubscription = "dddddddddddd",
        spendCredits = "eeeeeeeeeeee",
        recordSession = "ffffffffffff",
        recordEvent = "111111111111",
        attestChallenge = "222222222222",
        attestKey = "333333333333",
        claimReward = "444444444444",
    )

    fun config(store: TokenStore = InMemoryTokenStore()): SalesConfig = SalesConfig(
        baseUrl = "https://sales.example.com",
        apiKey = "csk_test",
        tokens = tokens,
        tokenStore = store,
    )

    /**
     * [outboxScope] drives the client's automatic analytics drain. The
     * default is the SDK's own background scope; event tests pass
     * `runTest`'s `backgroundScope` (or a scope on a test dispatcher) so
     * the automatic drain runs only when the test advances the scheduler.
     */
    fun client(
        transport: FakeTransport,
        store: TokenStore = InMemoryTokenStore(),
        clock: java.time.Clock = java.time.Clock.systemUTC(),
        outboxScope: kotlinx.coroutines.CoroutineScope? = null,
    ): SalesClient = if (outboxScope == null) {
        SalesClient(config(store), transport, clock = clock)
    } else {
        SalesClient(config(store), transport, clock = clock, outboxScope = outboxScope)
    }

    /**
     * A scope whose dispatcher nobody ever advances: the client's automatic
     * drain is scheduled onto it and never runs, so a test drives delivery
     * exclusively through `flush()`.
     */
    fun parkedScope(): kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.test.StandardTestDispatcher())

    /**
     * A scope on the calling `runTest`'s scheduler: the client's automatic
     * drain runs as a FOREGROUND task, i.e. only when the test calls
     * `advanceUntilIdle()` (or otherwise yields to the scheduler).
     */
    fun scheduledScope(scheduler: kotlinx.coroutines.test.TestCoroutineScheduler): kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.test.StandardTestDispatcher(scheduler) + kotlinx.coroutines.SupervisorJob(),
        )

    /** Minimal happy-path config bundle (createOrFetchUser response). */
    fun bundleJson(
        userId: String = "u1",
        balance: Int = 100,
        extras: String = "",
    ): String = """
        {
          "token": "jwt-1",
          "user": {
            "id": "$userId",
            "premium": { "tier": "free" },
            "credits": { "balance": $balance }
          }
          $extras
        }
    """.trimIndent()
}
