package com.salescentral.sdk

import java.io.IOException

/**
 * Scriptable [HttpTransport] for unit tests — the analog of the injected
 * URLSession/mocked attest service in the Swift test suite. Responses are
 * dequeued in FIFO order; every request is recorded for assertions.
 */
class FakeTransport : HttpTransport {

    data class Recorded(
        val url: String,
        val method: String,
        val headers: Map<String, String>,
        val body: String?,
    )

    val requests = mutableListOf<Recorded>()

    private val queue = ArrayDeque<() -> HttpTransport.Response>()

    fun enqueue(status: Int, body: String) {
        queue.addLast { HttpTransport.Response(status, body.toByteArray()) }
    }

    fun enqueueNetworkFailure(message: String = "offline") {
        queue.addLast { throw IOException(message) }
    }

    override suspend fun execute(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): HttpTransport.Response {
        requests.add(Recorded(url, method, headers, body?.decodeToString()))
        val next = queue.removeFirstOrNull()
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

    fun client(transport: FakeTransport, store: TokenStore = InMemoryTokenStore()): SalesClient =
        SalesClient(config(store), transport)

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
