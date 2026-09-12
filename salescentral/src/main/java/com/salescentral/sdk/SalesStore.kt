package com.salescentral.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * Optional convenience wrapper for observable UI (Jetpack Compose / Flows).
 *
 * [SalesStore] mirrors the [SalesClient] state into [StateFlow]s so UI can
 * react to premium / credits / subscription changes without manually
 * awaiting. In Compose: `val user by store.user.collectAsState()`.
 *
 * You don't have to use it — [SalesClient] is fully usable on its own.
 */
class SalesStore(val client: SalesClient) {

    constructor(config: SalesConfig) : this(SalesClient(config))

    private val _user = MutableStateFlow<SalesUser?>(null)
    val user: StateFlow<SalesUser?> = _user.asStateFlow()

    private val _subscription = MutableStateFlow<CurrentSubscriptionResponse?>(null)
    val subscription: StateFlow<CurrentSubscriptionResponse?> = _subscription.asStateFlow()

    /**
     * Retention-reward claim status — drive a "Claim daily reward" badge
     * off `retention.value?.available`. Refreshed by bootstrap / restore / claim.
     */
    private val _retention = MutableStateFlow<RetentionStatus?>(null)
    val retention: StateFlow<RetentionStatus?> = _retention.asStateFlow()

    /**
     * Registered catalog with effects, mirrored from the client after
     * ensureUser / restore. Drive paywall "what you get" UI off this.
     */
    private val _products = MutableStateFlow<List<SalesProduct>>(emptyList())
    val products: StateFlow<List<SalesProduct>> = _products.asStateFlow()

    private val _lastError = MutableStateFlow<SalesError?>(null)
    val lastError: StateFlow<SalesError?> = _lastError.asStateFlow()

    internal val sessionTracker = SessionTracker(client)

    /**
     * True once a user has actually been established (a successful bootstrap).
     * Stays false after an offline failure so [ensureBootstrapped] retries.
     */
    @Volatile
    var didBootstrap: Boolean = false
        private set

    // Single-flight guard for concurrent bootstrap callers.
    private val bootstrapMutex = Mutex()

    /**
     * Sync the store's user + subscription state after an out-of-band
     * purchase / receipt upload (e.g. [SalesCentral.purchase]). UI
     * re-renders immediately without waiting for the next refresh.
     */
    suspend fun syncAfterPurchase(user: SalesUser?) {
        user?.let { _user.value = it }
        _subscription.value = try {
            client.currentSubscription()
        } catch (_: Exception) {
            _subscription.value
        }
    }

    // ------------------------------------------------------------------

    /**
     * Call once on app launch. Ensures a user exists, refreshes their
     * subscription state, and wires up the session tracker. (The Play
     * Billing purchase observer is wired by [SalesCentral.start].)
     */
    suspend fun bootstrap(context: UserContext? = null) {
        try {
            _user.value = if (context != null) client.ensureUser(context) else client.ensureUser()
            _products.value = client.configuredProducts
            _retention.value = client.retentionStatus
            if (client.analyticsOnly) {
                // Server says this platform is analytics-only: no subscription
                // fetch, no foreground re-sync. Sessions still track.
                SalesLog.info(SalesLog.Category.SDK, "bootstrap — analyticsOnly (server): skipping subscription fetch + foreground refresh")
            } else {
                _subscription.value = try {
                    client.currentSubscription()
                } catch (_: Exception) {
                    null
                }
                // Re-sync subscription/premium from the server whenever the app
                // returns to the foreground (catches renewals / refunds on resume).
                sessionTracker.onForeground = { refreshSubscription() }
            }
            sessionTracker.start()
        } catch (e: SalesError) {
            _lastError.value = e
        } catch (e: Exception) {
            _lastError.value = SalesError.Network(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Single-flight bootstrap. Concurrent callers await the SAME in-flight
     * attempt — so the SDK never fires two `createOrFetch` requests and
     * double-creates a user — and a failed attempt (e.g. offline first
     * launch) leaves [didBootstrap] false so the next call retries.
     */
    suspend fun ensureBootstrapped(context: UserContext? = null) {
        if (didBootstrap) return
        bootstrapMutex.withLock {
            if (didBootstrap) return
            bootstrap(context)
            if (_user.value != null) didBootstrap = true
        }
    }

    /**
     * Restore this device's Play purchases and report THIS call's outcome —
     * the call to wire to a "Restore purchases" button. See
     * [RestorePurchasesOutcome] for what each case means (in particular why
     * `restored == false` is a success, not a failure, and needs its own
     * copy); [restorePurchases] is the fire-and-forget variant.
     *
     * Runs, in order: the analytics-only guard, [SalesClient.restorePurchases]
     * with the device's current Play receipts (the provider [SalesCentral]
     * wires), then mirrors `user` / `products` / `retention` from the
     * response and refreshes [subscription] with a `currentSubscription()`
     * read. All of that happens synchronously, before this returns, so a
     * caller may read [user] / [subscription] `.value` straight after the
     * call and see the restored state — there is no coroutine hand-off to
     * race against.
     *
     * Never swallows: a failure comes back as [RestorePurchasesOutcome.Failed]
     * AND is written to [lastError] (so anything observing that flow sees
     * exactly what it used to), with [user] / [products] / [retention] /
     * [subscription] left untouched. A failed subscription refresh after a
     * successful restore is NOT a failure — it is reported on
     * [RestorePurchasesOutcome.Completed.subscriptionRefreshError] with
     * [subscription] set to null, as before. A non-[SalesError] exception is
     * wrapped as [SalesError.Network], as everywhere else in this class; a
     * `CancellationException` propagates and is never recorded.
     */
    suspend fun restorePurchasesResult(): RestorePurchasesOutcome {
        val result = try {
            if (client.analyticsOnly) throw SalesError.InvalidState("analytics_only")
            client.restorePurchases()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SalesError) {
            _lastError.value = e
            return RestorePurchasesOutcome.Failed(e)
        } catch (e: Exception) {
            val wrapped = SalesError.Network(e.message ?: e.javaClass.simpleName)
            _lastError.value = wrapped
            return RestorePurchasesOutcome.Failed(wrapped)
        }
        _user.value = result.user
        _products.value = client.configuredProducts
        _retention.value = client.retentionStatus
        var refreshError: SalesError? = null
        val subscription = try {
            client.currentSubscription()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SalesError) {
            refreshError = e
            null
        } catch (e: Exception) {
            refreshError = SalesError.Network(e.message ?: e.javaClass.simpleName)
            null
        }
        _subscription.value = subscription
        return RestorePurchasesOutcome.Completed(result, subscription, refreshError)
    }

    /**
     * Fire-and-forget restore for callers that only observe the store's
     * flows: delegates to [restorePurchasesResult] and discards the outcome,
     * so there is one restore code path. Never throws — a failure is only
     * visible through [lastError], which cannot attribute it to this call
     * rather than any other writer. A restore button wants
     * [restorePurchasesResult] instead.
     */
    suspend fun restorePurchases() {
        restorePurchasesResult()
    }

    /**
     * Re-pull subscription + premium from the server (a cheap GET) and apply
     * the server-reconciled premium to the cached user, so [isPaid] / [tier]
     * reflect the latest server state. Lightweight — call on app resume or
     * before showing a paywall. [bootstrap] also wires this to fire
     * automatically when the app returns to the foreground.
     *
     * Note: [isPaid] / [tier] are already expiry-aware locally (they respect
     * `expiresAt` with no network), so this is for re-syncing server changes
     * like renewals / refunds, not for catching plain time-based expiry.
     */
    suspend fun refreshSubscription() {
        if (client.analyticsOnly) return
        val sub = try {
            client.currentSubscription()
        } catch (_: Exception) {
            return
        }
        _subscription.value = sub
        _user.value = _user.value?.copy(premium = sub.premium)
    }

    /** Upload a single receipt — for the post-purchase flow. */
    suspend fun applyReceipt(receipt: String) {
        try {
            val r = client.applyReceipt(receipt)
            r.user?.let { _user.value = it }
            _subscription.value = try {
                client.currentSubscription()
            } catch (_: Exception) {
                _subscription.value
            }
        } catch (e: SalesError) {
            _lastError.value = e
        } catch (e: Exception) {
            _lastError.value = SalesError.Network(e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun spendCredits(amount: Int, reason: String, idempotencyKey: String? = null): Credits {
        val credits = client.spendCredits(amount, reason, idempotencyKey)
        // Mutate `user` so UI updates without a re-fetch.
        _user.value = _user.value?.copy(credits = credits)
        return credits
    }

    /**
     * Claim today's retention reward. Updates [user] (credits) and
     * [retention] so UI re-renders without a re-fetch. Throws the same
     * [SalesError]s as [SalesClient.claimReward] — branch on
     * `e.code == "already_claimed"` etc.
     */
    suspend fun claimReward(): RetentionClaimResult {
        val result = client.claimReward()
        result.retention?.let { _retention.value = it }
        _user.value = _user.value?.copy(credits = result.credits)
        return result
    }

    /** Set a single user property. See [SalesClient.setUserProperty]. */
    suspend fun setUserProperty(key: String, value: SalesPropertyValue?) {
        try {
            _user.value = client.setUserProperty(key, value)
        } catch (e: SalesError) {
            _lastError.value = e
        } catch (e: Exception) {
            _lastError.value = SalesError.Network(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Set multiple user properties in one round-trip. See [SalesClient.setUserProperties]. */
    suspend fun setUserProperties(properties: Map<String, SalesPropertyValue?>) {
        try {
            _user.value = client.setUserProperties(properties)
        } catch (e: SalesError) {
            _lastError.value = e
        } catch (e: Exception) {
            _lastError.value = SalesError.Network(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Log a custom event. Enqueue-only, never blocks or throws — see
     * [SalesClient.track]. Safe to call before [bootstrap] has established a
     * user: the SDK's outbox holds the event (with [occurredAt], stamped at
     * this call by default) and delivers it once a user token exists.
     */
    fun track(
        name: String,
        properties: Map<String, Any?> = emptyMap(),
        occurredAt: Instant = Instant.now(),
    ) {
        client.track(name, properties, occurredAt)
    }

    /**
     * Register event ("super") properties merged into every subsequent
     * [track] call — see [SalesClient.setEventProperties]. In-memory; safe
     * to call before [bootstrap].
     */
    fun setEventProperties(properties: Map<String, Any?>) = client.setEventProperties(properties)

    /** Register (or update) a single event property. See [SalesClient.setEventProperty]. */
    fun setEventProperty(key: String, value: Any?) = client.setEventProperty(key, value)

    /** Stop attaching [key] to future events. See [SalesClient.removeEventProperty]. */
    fun removeEventProperty(key: String) = client.removeEventProperty(key)

    /** Drop all registered event properties. See [SalesClient.clearEventProperties]. */
    fun clearEventProperties() = client.clearEventProperties()

    // ------------------------------------------------------------------
    // Convenience accessors
    // ------------------------------------------------------------------

    val isPaid: Boolean get() = _user.value?.isPaid ?: false
    val isInTrial: Boolean get() = _user.value?.isInTrial ?: false
    val creditBalance: Int get() = _user.value?.credits?.balance ?: 0

    /** Credits bought but still on a drip-unlock schedule (not spendable yet). */
    val lockedCredits: Int get() = _user.value?.credits?.locked ?: 0

    /** When the next drip tranche unlocks. Null when nothing is locked. */
    val nextCreditUnlockAt: Instant? get() = _user.value?.credits?.nextUnlockAt

    val tier: String get() = _user.value?.premium?.effectiveTier ?: "free"

    /** True when a retention reward is claimable right now. */
    val rewardAvailable: Boolean get() = _retention.value?.available ?: false

    /** Effects for a product id from the mirrored catalog (empty if unknown). */
    fun effects(forProductId: String): List<ProductEffect> =
        _products.value.firstOrNull { it.productId == forProductId }?.effects ?: emptyList()

    // ------------------------------------------------------------------
    // Paywalls / remote config / experiments
    // ------------------------------------------------------------------

    /**
     * Fetch a server-defined paywall. Surfaces errors into [lastError]
     * and returns null — convenient for UI code.
     */
    suspend fun paywall(key: String): SalesPaywall? = try {
        client.paywall(key)
    } catch (e: SalesError) {
        _lastError.value = e
        null
    } catch (e: Exception) {
        _lastError.value = SalesError.Network(e.message ?: e.javaClass.simpleName)
        null
    }

    /**
     * Same coerce-or-default behaviour as [SalesClient.remoteConfig].
     * Synchronous — reads the cache populated by the most recent
     * bootstrap / refresh.
     */
    fun <T> remoteConfig(key: String, fallback: T): T = client.remoteConfig(key, fallback)

    /** Pull the user's active variant assignments. */
    fun activeExperiments(): Map<String, String> = client.activeExperiments()

    /** Force-refresh the bundled config from the server. */
    suspend fun refreshConfig() {
        try {
            client.refreshConfig()
        } catch (e: SalesError) {
            _lastError.value = e
        } catch (e: Exception) {
            _lastError.value = SalesError.Network(e.message ?: e.javaClass.simpleName)
        }
    }
}
