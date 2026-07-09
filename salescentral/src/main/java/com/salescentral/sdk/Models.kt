package com.salescentral.sdk

import org.json.JSONObject
import java.time.Instant

// Models that mirror the SalesCentral response shapes. All parsing is
// tolerant — older / leaner server responses that omit optional blocks
// decode to empty defaults instead of failing (mirrors the Swift decoders).

data class SalesUser(
    val id: String,
    val premium: PremiumState,
    val credits: Credits,
    val entitlements: Map<String, Entitlement> = emptyMap(),
    val features: List<String> = emptyList(),
    /**
     * Caller-defined user properties — see [SalesClient.setUserProperty].
     * Values are scalar (string / number / bool). Empty when the user
     * has none set.
     */
    val properties: Map<String, SalesPropertyValue> = emptyMap(),
    val stats: Stats? = null,
) {
    /** Convenience: is the user on any paid tier right now? */
    val isPaid: Boolean get() = premium.isPaid

    /** Convenience: is the user currently in a free trial? (respects expiry) */
    val isInTrial: Boolean get() = premium.isInTrial

    companion object {
        internal fun fromJson(o: JSONObject): SalesUser {
            val premiumObj = o.optJSONObject("premium")
                ?: throw SalesError.Decoding("SalesUser: missing 'premium'")
            val creditsObj = o.optJSONObject("credits")
                ?: throw SalesError.Decoding("SalesUser: missing 'credits'")
            val id = JsonUtil.optString(o, "id")
                ?: throw SalesError.Decoding("SalesUser: missing 'id'")
            return SalesUser(
                id = id,
                premium = PremiumState.fromJson(premiumObj),
                credits = Credits.fromJson(creditsObj),
                entitlements = o.optJSONObject("entitlements")?.let { ents ->
                    ents.keys().asSequence().mapNotNull { key ->
                        ents.optJSONObject(key)?.let { key to Entitlement.fromJson(it) }
                    }.toMap()
                } ?: emptyMap(),
                features = o.optJSONArray("features")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it, "").ifEmpty { null } }
                } ?: emptyList(),
                properties = o.optJSONObject("properties")?.let { props ->
                    props.keys().asSequence().mapNotNull { key ->
                        SalesPropertyValue.from(props.opt(key))?.let { key to it }
                    }.toMap()
                } ?: emptyMap(),
                stats = o.optJSONObject("stats")?.let { Stats.fromJson(it) },
            )
        }
    }
}

data class PremiumState(
    val tier: String,
    val expiresAt: Instant? = null,
    val source: String? = null,
    val isTrial: Boolean? = null,
    val trialEndsAt: Instant? = null,
) {
    /**
     * True when the user currently has paid (non-free) access. Respects
     * [expiresAt]: a lapsed premium reports false immediately — no server
     * round-trip — so a long-running app reflects expiry the moment it
     * passes. A null [expiresAt] means no expiry (e.g. lifetime grant).
     */
    val isPaid: Boolean
        get() {
            if (tier == "free") return false
            val exp = expiresAt ?: return true
            return exp.isAfter(Instant.now())
        }

    /**
     * The effective tier accounting for expiry: the raw [tier] while active,
     * otherwise `"free"`. Use this for tier-gated UI instead of raw [tier].
     */
    val effectiveTier: String get() = if (isPaid) tier else "free"

    /** True while a free trial is currently running (respects [trialEndsAt]). */
    val isInTrial: Boolean
        get() {
            if (isTrial != true) return false
            val ends = trialEndsAt ?: return true
            return ends.isAfter(Instant.now())
        }

    companion object {
        internal fun fromJson(o: JSONObject): PremiumState = PremiumState(
            tier = o.optString("tier", "free"),
            expiresAt = JsonUtil.optDate(o, "expiresAt"),
            source = JsonUtil.optString(o, "source"),
            isTrial = JsonUtil.optBool(o, "isTrial"),
            trialEndsAt = JsonUtil.optDate(o, "trialEndsAt"),
        )
    }
}

data class Credits(
    /** Spendable right now. */
    val balance: Int,
    /**
     * Purchased but still locked — released on the product's drip schedule
     * (e.g. 100/day). 0 when the product grants everything at once.
     */
    val locked: Int = 0,
    /** When the next locked tranche becomes spendable. Null when nothing is locked. */
    val nextUnlockAt: Instant? = null,
    /** Ledger row id of the debit. Present only on [SalesClient.spendCredits] responses. */
    val transactionId: String? = null,
    /**
     * Signed proof of the debit (compact HS256 JWS, ~10-minute expiry).
     * Present only on `spendCredits` responses. For server-delivered work,
     * forward it to YOUR backend, which verifies it offline with the
     * receipt signing secret from the admin panel — see the README's
     * "Charge credits" section.
     */
    val receipt: String? = null,
) {
    companion object {
        internal fun fromJson(o: JSONObject): Credits = Credits(
            balance = JsonUtil.optInt(o, "balance")
                ?: throw SalesError.Decoding("Credits: missing 'balance'"),
            // Tolerate older servers that only send `balance`.
            locked = JsonUtil.optInt(o, "locked") ?: 0,
            nextUnlockAt = JsonUtil.optDate(o, "nextUnlockAt"),
            transactionId = JsonUtil.optString(o, "transactionId"),
            receipt = JsonUtil.optString(o, "receipt"),
        )
    }
}

data class Entitlement(
    val active: Boolean,
    val expiresAt: Instant? = null,
) {
    companion object {
        internal fun fromJson(o: JSONObject): Entitlement = Entitlement(
            active = JsonUtil.optBool(o, "active") ?: false,
            expiresAt = JsonUtil.optDate(o, "expiresAt"),
        )
    }
}

data class Stats(
    val sessionCount: Int? = null,
    val totalSecondsInApp: Int? = null,
    val avgSessionDurationSec: Int? = null,
    val firstSessionAt: Instant? = null,
    val lastSessionAt: Instant? = null,
    val eventCount: Int? = null,
    val lifetimePurchaseCents: Int? = null,
    val lifetimeRefundedCents: Int? = null,
) {
    companion object {
        internal fun fromJson(o: JSONObject): Stats = Stats(
            sessionCount = JsonUtil.optInt(o, "sessionCount"),
            totalSecondsInApp = JsonUtil.optInt(o, "totalSecondsInApp"),
            avgSessionDurationSec = JsonUtil.optInt(o, "avgSessionDurationSec"),
            firstSessionAt = JsonUtil.optDate(o, "firstSessionAt"),
            lastSessionAt = JsonUtil.optDate(o, "lastSessionAt"),
            eventCount = JsonUtil.optInt(o, "eventCount"),
            lifetimePurchaseCents = JsonUtil.optInt(o, "lifetimePurchaseCents"),
            lifetimeRefundedCents = JsonUtil.optInt(o, "lifetimeRefundedCents"),
        )
    }
}

/**
 * Retention-reward claim status — configured per app in the admin
 * (App settings → Retention rewards) and refreshed on every `ensureUser`
 * / restore round-trip plus every [SalesClient.claimReward] call.
 */
data class RetentionStatus(
    /** Feature switched on for this app at all. */
    val enabled: Boolean = false,
    /** Whether [SalesClient.claimReward] would succeed right now. */
    val available: Boolean = false,
    /** Why not, when `available == false`: "disabled" / "audience" / "already_claimed". */
    val reason: String? = null,
    /** "daily" or "streak". */
    val mode: String? = null,
    /** Credits granted per successful claim. */
    val dailyAmount: Int? = null,
    /** Streak mode only — days in a full cycle. */
    val streakLength: Int? = null,
    /** Streak mode only — extra credits on the cycle's final day. */
    val streakBonusAmount: Int? = null,
    /** Consecutive days claimed (includes today once claimed). */
    val streak: Int? = null,
    /** 1-based day the NEXT claim lands on ("Day 3 of 7"). */
    val nextStreakDay: Int? = null,
    /** What the next claim grants, excluding any bonus. */
    val nextAmount: Int? = null,
    /** Bonus included in the next claim (0 when none). */
    val nextBonus: Int? = null,
    val claimedToday: Boolean? = null,
    /** When the user can claim again. Null = claimable now. */
    val nextClaimAt: Instant? = null,
) {
    companion object {
        internal fun fromJson(o: JSONObject): RetentionStatus = RetentionStatus(
            enabled = JsonUtil.optBool(o, "enabled") ?: false,
            available = JsonUtil.optBool(o, "available") ?: false,
            reason = JsonUtil.optString(o, "reason"),
            mode = JsonUtil.optString(o, "mode"),
            dailyAmount = JsonUtil.optInt(o, "dailyAmount"),
            streakLength = JsonUtil.optInt(o, "streakLength"),
            streakBonusAmount = JsonUtil.optInt(o, "streakBonusAmount"),
            streak = JsonUtil.optInt(o, "streak"),
            nextStreakDay = JsonUtil.optInt(o, "nextStreakDay"),
            nextAmount = JsonUtil.optInt(o, "nextAmount"),
            nextBonus = JsonUtil.optInt(o, "nextBonus"),
            claimedToday = JsonUtil.optBool(o, "claimedToday"),
            nextClaimAt = JsonUtil.optDate(o, "nextClaimAt"),
        )
    }
}

/** Result of a successful [SalesClient.claimReward] call. */
data class RetentionClaimResult(
    val granted: Granted,
    /** Post-claim status — `available` will be false until the next UTC day. */
    val retention: RetentionStatus?,
    /** Post-claim credit state (balance + any drip-locked pool). */
    val credits: Credits,
) {
    data class Granted(
        /** The daily reward portion. */
        val amount: Int,
        /** Streak-completion bonus included in this claim (0 when none). */
        val bonus: Int,
        /** amount + bonus — what actually landed on the balance. */
        val total: Int,
        /** 1-based streak position this claim landed on. */
        val streakDay: Int,
    )

    companion object {
        internal fun fromJson(o: JSONObject): RetentionClaimResult {
            val g = o.optJSONObject("granted")
                ?: throw SalesError.Decoding("RetentionClaimResult: missing 'granted'")
            return RetentionClaimResult(
                granted = Granted(
                    amount = JsonUtil.optInt(g, "amount") ?: 0,
                    bonus = JsonUtil.optInt(g, "bonus") ?: 0,
                    total = JsonUtil.optInt(g, "total") ?: 0,
                    streakDay = JsonUtil.optInt(g, "streakDay") ?: 0,
                ),
                retention = o.optJSONObject("retention")?.let { RetentionStatus.fromJson(it) },
                // The claim response carries balance / locked / nextUnlockAt flat
                // at the top level — same shape spendCredits returns.
                credits = Credits.fromJson(o),
            )
        }
    }
}

data class SubscriptionDetail(
    val id: String,
    val productId: String,
    val status: String,
    val isInTrial: Boolean? = null,
    val trialEndsAt: Instant? = null,
    val expiresAt: Instant? = null,
    val isAutoRenewing: Boolean = false,
    val environment: String = "",
) {
    companion object {
        internal fun fromJson(o: JSONObject): SubscriptionDetail = SubscriptionDetail(
            id = o.optString("id", ""),
            productId = o.optString("productId", ""),
            status = o.optString("status", ""),
            isInTrial = JsonUtil.optBool(o, "isInTrial"),
            trialEndsAt = JsonUtil.optDate(o, "trialEndsAt"),
            expiresAt = JsonUtil.optDate(o, "expiresAt"),
            isAutoRenewing = JsonUtil.optBool(o, "isAutoRenewing") ?: false,
            environment = o.optString("environment", ""),
        )
    }
}

data class CurrentSubscriptionResponse(
    val subscription: SubscriptionDetail?,
    val premium: PremiumState,
) {
    companion object {
        internal fun fromJson(o: JSONObject): CurrentSubscriptionResponse = CurrentSubscriptionResponse(
            subscription = o.optJSONObject("subscription")?.let { SubscriptionDetail.fromJson(it) },
            premium = o.optJSONObject("premium")?.let { PremiumState.fromJson(it) }
                ?: throw SalesError.Decoding("CurrentSubscriptionResponse: missing 'premium'"),
        )
    }
}

data class AppliedReceipt(
    val ok: Boolean,
    val transactionId: String? = null,
    val originalTransactionId: String? = null,
    val productId: String? = null,
    val alreadyProcessed: Boolean? = null,
    val effects: List<SalesAnyValue>? = null,
    val error: String? = null,
) {
    companion object {
        internal fun fromJson(o: JSONObject): AppliedReceipt = AppliedReceipt(
            ok = JsonUtil.optBool(o, "ok") ?: false,
            transactionId = JsonUtil.optString(o, "transactionId"),
            originalTransactionId = JsonUtil.optString(o, "originalTransactionId"),
            productId = JsonUtil.optString(o, "productId"),
            alreadyProcessed = JsonUtil.optBool(o, "alreadyProcessed"),
            effects = o.optJSONArray("effects")?.let { arr ->
                (0 until arr.length()).map { SalesAnyValue.from(arr.opt(it)) }
            },
            error = JsonUtil.optString(o, "error"),
        )
    }
}

/** Response shape for the applyPurchases endpoint. */
data class ApplyResult(
    val applied: List<AppliedReceipt>,
    val user: SalesUser?,
) {
    companion object {
        internal fun fromJson(o: JSONObject): ApplyResult = ApplyResult(
            applied = o.optJSONArray("applied")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { AppliedReceipt.fromJson(it) }
                }
            } ?: emptyList(),
            user = o.optJSONObject("user")?.let { SalesUser.fromJson(it) },
        )
    }
}

/** Response shape for the restoreUser endpoint. */
data class RestoreResult(
    val token: String,
    val user: SalesUser,
    val restored: Boolean,
    val applied: List<AppliedReceipt>,
    /**
     * SKUs registered for this app in the admin. May be null on older
     * servers that don't ship the product-prefetch feature.
     */
    val products: List<SalesProduct>? = null,
    /**
     * Bundled paywalls / remote config / variant assignments. May be null
     * on older servers; the SDK then falls back to whatever it already had
     * cached.
     */
    val paywalls: List<SalesPaywall>? = null,
    val remoteConfig: Map<String, SalesAnyValue>? = null,
    val experimentAssignments: Map<String, String>? = null,
) {
    companion object {
        internal fun fromJson(o: JSONObject): RestoreResult = RestoreResult(
            token = o.optString("token", ""),
            user = o.optJSONObject("user")?.let { SalesUser.fromJson(it) }
                ?: throw SalesError.Decoding("RestoreResult: missing 'user'"),
            restored = JsonUtil.optBool(o, "restored") ?: false,
            applied = o.optJSONArray("applied")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { AppliedReceipt.fromJson(it) }
                }
            } ?: emptyList(),
            products = o.optJSONArray("products")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { SalesProduct.fromJson(it) }
                }
            },
            paywalls = o.optJSONArray("paywalls")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { SalesPaywall.fromJson(it) }
                }
            },
            remoteConfig = o.optJSONObject("remoteConfig")?.let { SalesAnyValue.mapFrom(it) },
            experimentAssignments = o.optJSONObject("experimentAssignments")?.let { ea ->
                ea.keys().asSequence().mapNotNull { key ->
                    JsonUtil.optString(ea, key)?.let { key to it }
                }.toMap()
            },
        )
    }
}
