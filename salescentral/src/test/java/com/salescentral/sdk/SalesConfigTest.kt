package com.salescentral.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SalesConfigTest {

    private val full = """
        {
          "baseURL": "https://sales-central.org",
          "apiKey": "csk_test",
          "tokens": {
            "createOrFetchUser": "aaaaaaaaaaaa",
            "restoreUser": "bbbbbbbbbbbb",
            "applyPurchases": "cccccccccccc",
            "currentSubscription": "dddddddddddd",
            "spendCredits": "eeeeeeeeeeee",
            "recordSession": "ffffffffffff",
            "recordEvent": "111111111111",
            "attestChallenge": "222222222222",
            "attestKey": "333333333333",
            "claimReward": "444444444444"
          }
        }
    """.trimIndent()

    @Test
    fun `parses full config and composes endpoint urls`() {
        val config = SalesConfig.parse(full)
        assertEquals("https://sales-central.org", config.baseUrl)
        assertEquals("csk_test", config.apiKey)
        assertEquals(
            "https://sales-central.org/aaaaaaaaaaaa",
            config.urlFor(SalesConfig.Endpoint.CREATE_OR_FETCH_USER),
        )
        assertEquals(
            "https://sales-central.org/444444444444",
            config.urlFor(SalesConfig.Endpoint.CLAIM_REWARD),
        )
    }

    @Test
    fun `claimReward token is optional`() {
        val withoutClaim = full.replace("\"claimReward\": \"444444444444\"", "\"claimReward\": \"\"")
            .replace(",\n            \"claimReward\": \"\"", "")
        val config = SalesConfig.parse(withoutClaim)
        assertNull(config.tokens.claimReward)
    }

    @Test
    fun `missing required keys throw with the key name`() {
        val noApiKey = full.replace("\"apiKey\": \"csk_test\",", "")
        val e1 = assertThrows(IllegalStateException::class.java) { SalesConfig.parse(noApiKey) }
        assertEquals(true, e1.message?.contains("apiKey"))

        val noToken = full.replace("\"spendCredits\": \"eeeeeeeeeeee\",", "")
        val e2 = assertThrows(IllegalStateException::class.java) { SalesConfig.parse(noToken) }
        assertEquals(true, e2.message?.contains("spendCredits"))

        val badUrl = full.replace("https://sales-central.org", "")
        assertThrows(IllegalStateException::class.java) { SalesConfig.parse(badUrl) }

        assertThrows(IllegalStateException::class.java) { SalesConfig.parse("not json") }
    }
}
