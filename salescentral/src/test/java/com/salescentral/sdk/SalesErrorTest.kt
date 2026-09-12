package com.salescentral.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SalesError.ReceiptUpload] (SDK 1.4.0) — the message / `cause` plumbing
 * `PlayBillingConnector.finishPurchase` relies on. The connector itself needs
 * a real `BillingClient`, so the wrapping site is covered by reading, not by
 * a JVM test; this pins the value the app branches on.
 */
class SalesErrorTest {

    @Test
    fun `ReceiptUpload names the product and carries the cause message`() {
        val cause = SalesError.Http(503, "upstream_unavailable", "try later")
        val e = SalesError.ReceiptUpload("goai.sub.weekly", cause)

        assertEquals("Receipt upload failed for goai.sub.weekly: HTTP 503 upstream_unavailable: try later", e.message)
        assertSame(cause, e.cause)
        assertEquals("goai.sub.weekly", e.productId)
    }

    @Test
    fun `code and isClientError read through to the cause`() {
        val http4xx = SalesError.ReceiptUpload("p", SalesError.Http(401, "invalid_token", null))
        assertEquals("invalid_token", http4xx.code)
        assertTrue(http4xx.isClientError)

        val http5xx = SalesError.ReceiptUpload("p", SalesError.Http(500, "internal_error", null))
        assertEquals("internal_error", http5xx.code)
        assertFalse(http5xx.isClientError)

        val network = SalesError.ReceiptUpload("p", SalesError.Network("offline"))
        assertNull(network.code)
        assertFalse(network.isClientError)
    }

    @Test
    fun `other subtypes keep their code semantics`() {
        assertEquals("insufficient_credits", SalesError.Http(402, "insufficient_credits", null).code)
        assertTrue(SalesError.Http(404, "endpoint_not_found", null).isClientError)
        assertFalse(SalesError.Http(500, "internal_error", null).isClientError)
        assertNull(SalesError.Network("dns").code)
        assertNull(SalesError.InvalidState("analytics_only").code)
        assertFalse(SalesError.Decoding("bad json").isClientError)
    }
}
