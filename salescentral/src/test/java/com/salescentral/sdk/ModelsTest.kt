package com.salescentral.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ModelsTest {

    @Test
    fun `user parses with tolerant defaults for lean responses`() {
        val user = SalesUser.fromJson(
            JSONObject(
                """
                { "id": "u1", "premium": { "tier": "free" }, "credits": { "balance": 5 } }
                """,
            ),
        )
        assertEquals("u1", user.id)
        assertEquals(5, user.credits.balance)
        assertEquals(0, user.credits.locked)
        assertTrue(user.entitlements.isEmpty())
        assertTrue(user.features.isEmpty())
        assertTrue(user.properties.isEmpty())
        assertNull(user.stats)
        assertFalse(user.isPaid)
    }

    @Test
    fun `user parses full payload`() {
        val user = SalesUser.fromJson(
            JSONObject(
                """
                {
                  "id": "u2",
                  "premium": { "tier": "pro", "expiresAt": "2999-01-01T00:00:00.000Z", "isTrial": true },
                  "credits": { "balance": 10, "locked": 90, "nextUnlockAt": "2999-01-01T00:00:00Z" },
                  "entitlements": { "pro_pack": { "active": true } },
                  "features": ["export"],
                  "properties": { "name": "Alice", "orders": 3, "vip": true },
                  "stats": { "sessionCount": 7 }
                }
                """,
            ),
        )
        assertTrue(user.isPaid)
        assertTrue(user.isInTrial)
        assertEquals(90, user.credits.locked)
        assertEquals(true, user.entitlements["pro_pack"]?.active)
        assertEquals(listOf("export"), user.features)
        assertEquals(SalesPropertyValue.StringValue("Alice"), user.properties["name"])
        assertEquals(SalesPropertyValue.NumberValue(3.0), user.properties["orders"])
        assertEquals(SalesPropertyValue.BoolValue(true), user.properties["vip"])
        assertEquals(7, user.stats?.sessionCount)
    }

    // Mirrors PremiumExpiryTests.swift — expiry is respected locally.

    @Test
    fun `expired premium reports free`() {
        val premium = PremiumState(tier = "pro", expiresAt = Instant.now().minusSeconds(60))
        assertFalse(premium.isPaid)
        assertEquals("free", premium.effectiveTier)
    }

    @Test
    fun `active premium reports paid and lapsed trial reports not in trial`() {
        val active = PremiumState(tier = "pro", expiresAt = Instant.now().plusSeconds(3600))
        assertTrue(active.isPaid)
        assertEquals("pro", active.effectiveTier)

        val lifetime = PremiumState(tier = "pro", expiresAt = null)
        assertTrue(lifetime.isPaid)

        val lapsedTrial = PremiumState(
            tier = "pro",
            expiresAt = Instant.now().plusSeconds(3600),
            isTrial = true,
            trialEndsAt = Instant.now().minusSeconds(60),
        )
        assertFalse(lapsedTrial.isInTrial)
    }

    @Test
    fun `dates parse with and without fractional seconds`() {
        assertEquals(
            Instant.parse("2026-06-11T08:15:30.123Z"),
            JsonUtil.parseDate("2026-06-11T08:15:30.123Z"),
        )
        assertEquals(
            Instant.parse("2026-06-11T08:15:30Z"),
            JsonUtil.parseDate("2026-06-11T08:15:30Z"),
        )
        assertEquals(
            Instant.parse("2026-06-11T06:15:30Z"),
            JsonUtil.parseDate("2026-06-11T08:15:30+02:00"),
        )
        assertNull(JsonUtil.parseDate("not-a-date"))
        assertNull(JsonUtil.parseDate(null))
    }

    @Test
    fun `retention claim result reads flat credits`() {
        val result = RetentionClaimResult.fromJson(
            JSONObject(
                """
                {
                  "granted": { "amount": 25, "bonus": 100, "total": 125, "streakDay": 7 },
                  "retention": { "enabled": true, "available": false, "reason": "already_claimed" },
                  "balance": 350,
                  "locked": 0
                }
                """,
            ),
        )
        assertEquals(125, result.granted.total)
        assertEquals(7, result.granted.streakDay)
        assertEquals(350, result.credits.balance)
        assertEquals(false, result.retention?.available)
    }

    @Test
    fun `apply result parses applied receipts and user`() {
        val result = ApplyResult.fromJson(
            JSONObject(
                """
                {
                  "applied": [
                    { "ok": true, "transactionId": "t1", "productId": "p1" },
                    { "ok": false, "error": "expired_transaction" }
                  ],
                  "user": { "id": "u1", "premium": { "tier": "pro" }, "credits": { "balance": 0 } }
                }
                """,
            ),
        )
        assertEquals(2, result.applied.size)
        assertTrue(result.applied[0].ok)
        assertEquals("expired_transaction", result.applied[1].error)
        assertEquals("u1", result.user?.id)
    }
}

// Mirrors ProductEffectTests.swift.
class ProductEffectTest {

    @Test
    fun `effects parse each known type`() {
        val product = SalesProduct.fromJson(
            JSONObject(
                """
                {
                  "productId": "com.foo.pro",
                  "type": "subscription",
                  "displayName": "Pro",
                  "effects": [
                    { "type": "set_premium", "tier": "pro", "durationDays": 30 },
                    { "type": "grant_credits", "amount": 500, "unlockAmount": 100, "unlockPeriod": "day" },
                    { "type": "grant_entitlement", "entitlement": "cloud_sync" },
                    { "type": "unlock_feature", "feature": "export" },
                    { "type": "something_new", "foo": 1 }
                  ]
                }
                """,
            ),
        )
        assertEquals(5, product.effects.size)
        assertEquals(
            ProductEffect.SetPremium(tier = "pro", durationDays = 30, trialDurationDays = null),
            product.effects[0],
        )
        assertEquals(
            ProductEffect.GrantCredits(amount = 500, trialAmount = null, unlockAmount = 100, unlockPeriod = "day"),
            product.effects[1],
        )
        assertEquals(
            ProductEffect.GrantEntitlement(entitlement = "cloud_sync", durationDays = null, trialDurationDays = null),
            product.effects[2],
        )
        assertEquals(ProductEffect.UnlockFeature(feature = "export"), product.effects[3])
        assertEquals(ProductEffect.Unknown(type = "something_new"), product.effects[4])
        assertTrue(product.isSubscription)
        assertFalse(product.isConsumable)
    }

    @Test
    fun `paywall parses data as any values`() {
        val pw = SalesPaywall.fromJson(
            JSONObject(
                """
                {
                  "key": "main",
                  "name": "Main paywall",
                  "productIds": ["a", "b"],
                  "data": { "cta": "Buy now", "discount": 20, "ratio": 0.5, "on": true, "list": [1, "x"] }
                }
                """,
            ),
        )
        assertEquals(listOf("a", "b"), pw.productIds)
        assertEquals("Buy now", pw.data["cta"]?.stringValue)
        assertEquals(20, pw.data["discount"]?.intValue)
        assertEquals(0.5, pw.data["ratio"]?.doubleValue)
        assertEquals(true, pw.data["on"]?.boolValue)
        assertEquals(2, pw.data["list"]?.arrayValue?.size)
    }
}
