package com.salescentral.sdk

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * The single entry point for talking to the SalesCentral backend.
 *
 * All methods are `suspend` — call them from any coroutine. The client owns
 * the user JWT (read/written through the configured [TokenStore]) and
 * transparently re-issues it on calls that return one.
 *
 * Typical lifecycle:
 *
 * ```kotlin
 * val client = SalesClient(config)
 * val user = client.ensureUser()          // first launch
 * // PlayBillingConnector observes purchases and auto-uploads them.
 * // On the user tapping "Restore Purchases":
 * client.restorePurchases()
 * ```
 *
 * Most apps never construct this directly — use the [SalesCentral] facade.
 */
class SalesClient(
    private val config: SalesConfig,
    private val transport: HttpTransport = UrlConnectionTransport(),
    private val attestService: DeviceAttestService = UnsupportedAttestService(),
    internal val androidContext: Context? = null,
) {

    private val tokenStore: TokenStore = config.tokenStore
        ?: throw IllegalStateException(
            "SalesConfig.tokenStore is null — construct the client via SalesCentral.configure(context, config), " +
                "or pass a TokenStore in the config.",
        )

    // ------------------------------------------------------------------
    // State (guarded by `lock`; snapshots returned to callers)
    // ------------------------------------------------------------------

    private val lock = Any()

    private var _currentUser: SalesUser? = null
    private var _configuredProducts: List<SalesProduct> = emptyList()
    private var _paywallsByKey: Map<String, SalesPaywall> = emptyMap()
    private var _remoteConfigCache: Map<String, SalesAnyValue> = emptyMap()
    private var _experimentAssignments: Map<String, String> = emptyMap()
    private var _retentionStatus: RetentionStatus? = null

    val currentUser: SalesUser? get() = synchronized(lock) { _currentUser }

    /**
     * Google Play SKU strings registered for this app in the SalesCentral
     * admin. Populated by the most recent ensureUser / restorePurchases
     * response so the SDK can ask Play Billing for products without the
     * integrator hard-coding identifiers.
     */
    val configuredProductIds: List<String>
        get() = synchronized(lock) { _configuredProducts.map { it.productId } }

    /** Full registered catalog (incl. effects), refreshed from every config bundle. */
    val configuredProducts: List<SalesProduct> get() = synchronized(lock) { _configuredProducts }

    /** Effects configured for a given product id (empty if unknown). */
    fun effects(forProductId: String): List<ProductEffect> =
        configuredProducts.firstOrNull { it.productId == forProductId }?.effects ?: emptyList()

    /**
     * Server-defined paywalls, keyed by `key`. Refreshed on every
     * ensureUser / updateContext / restorePurchases call.
     */
    val paywallsByKey: Map<String, SalesPaywall> get() = synchronized(lock) { _paywallsByKey }

    /**
     * Server-defined remote config, keyed by config key. Same refresh
     * cadence as [paywallsByKey].
     */
    val remoteConfigCache: Map<String, SalesAnyValue> get() = synchronized(lock) { _remoteConfigCache }

    /**
     * User's current experiment assignments, keyed by experiment key.
     * Stable for the lifetime of each experiment.
     */
    val experimentAssignments: Map<String, String> get() = synchronized(lock) { _experimentAssignments }

    /**
     * Retention-reward claim status (daily login credits / streaks).
     * Refreshed on every ensureUser / restorePurchases / claimReward call.
     * Null until the first round-trip, or on servers without the feature.
     */
    val retentionStatus: RetentionStatus? get() = synchronized(lock) { _retentionStatus }

    /**
     * Supplies the device's current Play purchase receipts when
     * [restorePurchases] is called without explicit receipts. Wired by
     * [SalesCentral] to the Play Billing connector's query.
     */
    internal var receiptsProvider: (suspend () -> List<String>)? = null

    // ------------------------------------------------------------------
    // Purchase claim bookkeeping
    // ------------------------------------------------------------------

    // Transaction ids already claimed for upload, so the explicit purchase()
    // upload and the Play purchase observer don't both send the SAME one.
    // The Set backs the O(1) membership test; the list preserves insertion
    // order so we can prune the OLDEST entries (not blow away everything)
    // when we hit the cap.
    private val claimedTransactionIds = mutableSetOf<String>()
    private val claimedTransactionOrder = ArrayDeque<String>()
    private val claimedTransactionCap = 512

    /**
     * Claim a transaction id for upload. Returns true if it's newly claimed
     * (caller should upload), false if it was already claimed (caller should
     * skip — someone else is handling it). Bounded so it can't grow forever:
     * when over the cap we drop only the oldest entries, keeping recent ids
     * (a blanket wipe would make old txns re-claimable and re-uploadable).
     */
    internal fun claimTransaction(id: String): Boolean = synchronized(lock) {
        if (!claimedTransactionIds.add(id)) return false
        claimedTransactionOrder.addLast(id)
        while (claimedTransactionOrder.size > claimedTransactionCap) {
            claimedTransactionIds.remove(claimedTransactionOrder.removeFirst())
        }
        true
    }

    /**
     * Release a previously-claimed transaction id so it can be re-claimed and
     * re-uploaded. Call this when an upload attempt fails: otherwise the id
     * stays claimed for the process lifetime and the purchase observer (the
     * retry path) skips it forever, stranding a paid transaction until the
     * next cold launch.
     */
    internal fun unclaimTransaction(id: String) {
        synchronized(lock) {
            if (claimedTransactionIds.remove(id)) claimedTransactionOrder.remove(id)
        }
    }

    /**
     * Upload one observer-delivered purchase: claim → upload → unclaim on
     * failure. Returns true when the upload succeeded and the caller should
     * acknowledge/consume the purchase. On failure the claim is released so
     * the next Play redelivery (same session or next launch) retries the
     * upload — the server is idempotent on transactionId, so a retry can't
     * double-apply.
     */
    internal suspend fun uploadObservedTransaction(id: String, receipt: String): Boolean {
        if (!claimTransaction(id)) return false
        return try {
            applyReceipts(listOf(receipt))
            true
        } catch (_: Exception) {
            unclaimTransaction(id)
            false
        }
    }

    // ------------------------------------------------------------------
    // User lifecycle
    // ------------------------------------------------------------------

    private fun defaultUserContext(): UserContext =
        androidContext?.let { UserContext.current(it) } ?: UserContext()

    /**
     * "Ensure I have a user." On first launch this creates a guest user
     * and saves the returned JWT to the token store. On subsequent launches
     * (with the JWT present) it fetches the existing user and merges any
     * context you passed in.
     */
    suspend fun ensureUser(context: UserContext = defaultUserContext()): SalesUser {
        context.clientId = clientId() // idempotent-create key (see clientId())
        val resp = request(
            SalesConfig.Endpoint.CREATE_OR_FETCH_USER,
            method = "POST",
            body = context.toJson(),
            attachUserToken = tokenStore.read() != null,
        )
        return absorbBundle(resp)
    }

    /**
     * Stable client id (UUID) persisted in the token store, generated once
     * per install. Sent on `createOrFetchUser` so a tokenless retry — offline
     * first launch, or a create whose response was lost — resolves to the
     * SAME server user instead of creating a duplicate.
     */
    private fun clientId(): String {
        tokenStore.readClientId()?.let { return it }
        val id = UUID.randomUUID().toString()
        tokenStore.writeClientId(id)
        return id
    }

    /**
     * Common path for every endpoint that returns a config bundle.
     * Persists the rotated token, refreshes the in-memory caches.
     */
    private fun absorbBundle(resp: JSONObject): SalesUser {
        val token = resp.optString("token", "")
        val user = resp.optJSONObject("user")?.let { SalesUser.fromJson(it) }
            ?: throw SalesError.Decoding("bundle response: missing 'user'")
        if (token.isNotEmpty()) tokenStore.write(token)
        synchronized(lock) {
            _currentUser = user
            _configuredProducts = resp.optJSONArray("products")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { SalesProduct.fromJson(it) }
                }
            } ?: emptyList()
            // Only replace each cache when the response actually carries it — a
            // lean/older response that omits a block must not WIPE the cache.
            resp.optJSONArray("paywalls")?.let { arr ->
                _paywallsByKey = (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { SalesPaywall.fromJson(it) }
                }.associateBy { it.key }
            }
            resp.optJSONObject("remoteConfig")?.let { _remoteConfigCache = SalesAnyValue.mapFrom(it) }
            resp.optJSONObject("experimentAssignments")?.let { ea ->
                _experimentAssignments = ea.keys().asSequence().mapNotNull { key ->
                    JsonUtil.optString(ea, key)?.let { key to it }
                }.toMap()
            }
            resp.optJSONObject("retention")?.let { _retentionStatus = RetentionStatus.fromJson(it) }
        }
        SalesLog.debug(
            SalesLog.Category.SDK,
            "absorbed bundle — user=${user.id} products=${configuredProductIds.size} " +
                "paywalls=${paywallsByKey.size} remoteConfig=${remoteConfigCache.size} " +
                "experiments=${experimentAssignments.size} rewardAvailable=${retentionStatus?.available ?: false}",
        )
        return user
    }

    /**
     * Update context on the current user — locale change, consent flip,
     * push token, etc. Same wire format as [ensureUser], always behaves as
     * a fetch since the token is required.
     */
    suspend fun updateContext(context: UserContext): SalesUser {
        if (tokenStore.read() == null) {
            throw SalesError.InvalidState("no user token — call ensureUser first")
        }
        return ensureUser(context)
    }

    /**
     * Set a single user property — caller-defined attributes like name,
     * email, plan_intent, etc. that the admin can search and display.
     * Pass null to delete the key.
     *
     * Keys must match `[A-Za-z0-9_.-]{1,64}`; values must be strings
     * (≤1024 chars), finite numbers, or booleans. Out-of-spec entries
     * are silently dropped server-side — the SDK doesn't pre-validate
     * so that future server-side relaxations don't require a new SDK.
     */
    suspend fun setUserProperty(key: String, value: SalesPropertyValue?): SalesUser =
        setUserProperties(mapOf(key to value))

    /**
     * Set multiple user properties in one round-trip. Null values delete
     * their key; non-null values upsert.
     */
    suspend fun setUserProperties(properties: Map<String, SalesPropertyValue?>): SalesUser {
        if (tokenStore.read() == null) {
            throw SalesError.InvalidState("no user token — call ensureUser first")
        }
        if (properties.isEmpty()) {
            // Nothing to update — surface the current user without a
            // round-trip. Mirrors the no-op behaviour callers expect.
            currentUser?.let { return it }
            return ensureUser()
        }
        val wire = JSONObject()
        for ((key, value) in properties) {
            // A JSON null means "delete this key"; omitting the field would
            // read as "leave this key alone" server-side.
            wire.put(key, value?.toJsonValue() ?: JSONObject.NULL)
        }
        val resp = request(
            SalesConfig.Endpoint.CREATE_OR_FETCH_USER,
            method = "POST",
            body = JSONObject().put("properties", wire),
            attachUserToken = true,
        )
        return absorbBundle(resp)
    }

    /**
     * Recover the user that owns the given Play purchase(s). Pulls the
     * device's current Play Billing purchases automatically when [receipts]
     * is omitted (requires the [SalesCentral] facade to have wired billing).
     */
    suspend fun restorePurchases(
        receipts: List<String>? = null,
        context: UserContext = defaultUserContext(),
    ): RestoreResult {
        val receiptList = receipts ?: receiptsProvider?.invoke() ?: emptyList()
        if (receiptList.isEmpty()) {
            // No prior purchases on this device — fall back to a plain
            // create-or-fetch so the caller always ends up with a usable
            // user record.
            val user = ensureUser(context)
            return RestoreResult(
                token = tokenStore.read() ?: "",
                user = user,
                restored = false,
                applied = emptyList(),
            )
        }
        val body = JSONObject().apply {
            put("receipts", JSONArray(receiptList))
            context.device?.let { put("device", it.toJson()) }
            context.app?.let { put("app", it.toJson()) }
            context.locale?.let { put("locale", it.toJson()) }
            context.network?.let { put("network", it.toJson()) }
            context.marketing?.let { put("marketing", it.toJson()) }
            context.consent?.let { put("consent", it.toJson()) }
        }
        val respJson = request(
            SalesConfig.Endpoint.RESTORE_USER,
            method = "POST",
            body = body,
            attachUserToken = false,
        )
        val resp = RestoreResult.fromJson(respJson)
        tokenStore.write(resp.token)
        synchronized(lock) {
            _currentUser = resp.user
            resp.products?.let { _configuredProducts = it }
            resp.paywalls?.let { pw -> _paywallsByKey = pw.associateBy { it.key } }
            resp.remoteConfig?.let { _remoteConfigCache = it }
            resp.experimentAssignments?.let { _experimentAssignments = it }
        }
        return resp
    }

    /**
     * Sign the user out locally. Doesn't invalidate the server-side JWT
     * (it'll just naturally expire); call this when the user explicitly
     * signs out or when you want to start fresh.
     *
     * Also wipes the stable `clientId`, so the next [ensureUser] creates a
     * genuinely NEW guest user instead of de-duplicating back to this one —
     * a real identity reset (useful for testing). Also clears the in-flight
     * transaction-claim set.
     */
    fun clearUser() {
        tokenStore.clear()
        tokenStore.clearClientId()
        synchronized(lock) {
            claimedTransactionIds.clear()
            claimedTransactionOrder.clear()
            _currentUser = null
            _paywallsByKey = emptyMap()
            _remoteConfigCache = emptyMap()
            _experimentAssignments = emptyMap()
        }
    }

    // ------------------------------------------------------------------
    // Paywalls / remote config / experiments
    // ------------------------------------------------------------------

    /**
     * Fetch a server-defined paywall by key.
     *
     * Reads from the cache populated by the most recent ensureUser /
     * updateContext / restorePurchases. If the key isn't cached the SDK
     * refreshes the bundle (single round-trip) and tries again — that way
     * a paywall added in the admin reaches the app without a relaunch.
     */
    suspend fun paywall(key: String): SalesPaywall {
        paywallsByKey[key]?.let {
            SalesLog.debug(SalesLog.Category.PAYWALL, "paywall($key) — cache hit, ${it.productIds.size} product(s)")
            return it
        }
        SalesLog.info(SalesLog.Category.PAYWALL, "paywall($key) — cache miss, refreshing bundle")
        refreshConfig()
        val pw = paywallsByKey[key]
        if (pw == null) {
            SalesLog.warn(SalesLog.Category.PAYWALL, "paywall($key) — still not found after refresh")
            throw SalesError.InvalidState("paywall not found: $key")
        }
        SalesLog.info(SalesLog.Category.PAYWALL, "paywall($key) — found after refresh, ${pw.productIds.size} product(s)")
        return pw
    }

    /**
     * Look up a remote-config value, falling back to [fallback] if the
     * key is missing or the value can't be coerced to the same type.
     * Synchronous — reads from the in-memory cache.
     *
     * Supported types: String, Int, Double, Boolean. For richer shapes
     * (arrays / nested objects), read [remoteConfigCache] directly and
     * pattern-match on [SalesAnyValue].
     */
    fun <T> remoteConfig(key: String, fallback: T): T {
        val v = remoteConfigCache[key] ?: return fallback
        return v.coerced(fallback)
    }

    /**
     * Currently-active experiment assignments, keyed by experiment key.
     * Useful when you want to log the user's variant alongside your own
     * analytics events.
     */
    fun activeExperiments(): Map<String, String> = experimentAssignments

    /**
     * Refresh the paywall / remote-config / experiment cache from the
     * server. No-op against a logged-out user.
     */
    suspend fun refreshConfig() {
        if (tokenStore.read() == null) return
        ensureUser()
    }

    // ------------------------------------------------------------------
    // Purchases
    // ------------------------------------------------------------------

    /**
     * Upload one or more purchase receipts. Idempotent on transaction id —
     * re-uploading the same receipt is safe.
     *
     * On Android each receipt string is the JSON envelope produced by the
     * Play Billing connector (`platform: "google_play"`, purchaseToken,
     * originalJson, signature, …). NOTE: the current SalesCentral backend
     * validates Apple receipts only — see the README's "Backend support"
     * section for the state of Google Play validation.
     */
    suspend fun applyReceipts(receipts: List<String>): ApplyResult {
        if (receipts.isEmpty()) {
            throw SalesError.InvalidState("applyReceipts called with empty receipts")
        }
        val respJson = request(
            SalesConfig.Endpoint.APPLY_PURCHASES,
            method = "POST",
            body = JSONObject().put("receipts", JSONArray(receipts)),
            attachUserToken = true,
        )
        val resp = ApplyResult.fromJson(respJson)
        resp.user?.let { u -> synchronized(lock) { _currentUser = u } }
        return resp
    }

    /** Convenience for the common single-receipt case. */
    suspend fun applyReceipt(receipt: String): ApplyResult = applyReceipts(listOf(receipt))

    /**
     * Fetch the current subscription state. Cheap source of truth for
     * "is this user paid right now?" — performs lazy server-side expiry.
     */
    suspend fun currentSubscription(): CurrentSubscriptionResponse {
        val resp = request(
            SalesConfig.Endpoint.CURRENT_SUBSCRIPTION,
            method = "GET",
            body = null,
            attachUserToken = true,
        )
        return CurrentSubscriptionResponse.fromJson(resp)
    }

    // ------------------------------------------------------------------
    // Credits
    // ------------------------------------------------------------------

    /**
     * Debit [amount] credits. Throws [SalesError.Http] with
     * `code == "insufficient_credits"` (402) when the spendable balance is
     * too low — surface a paywall in that branch. Note the user may still
     * have `locked` credits on a drip schedule; re-fetch the user (or check
     * `credits.nextUnlockAt`) to show "more credits unlock at <time>"
     * instead of a bare paywall.
     *
     * Returns the full post-spend [Credits] state (spendable balance plus
     * any locked drip pool).
     *
     * Pass an [idempotencyKey] unique to the intended charge to make retries
     * safe: if a spend times out and you re-send it with the same key, the
     * server recognizes the replay and does NOT debit again (it returns the
     * balance from the single original debit). Without a key, every call
     * that reaches the server debits.
     */
    suspend fun spendCredits(amount: Int, reason: String, idempotencyKey: String? = null): Credits {
        val body = JSONObject().apply {
            put("amount", amount)
            put("reason", reason)
            idempotencyKey?.let { put("idempotencyKey", it) }
        }
        val resp = request(
            SalesConfig.Endpoint.SPEND_CREDITS,
            method = "POST",
            body = body,
            attachUserToken = true,
        )
        val credits = Credits.fromJson(resp)
        synchronized(lock) {
            _currentUser = _currentUser?.copy(credits = credits)
        }
        return credits
    }

    // ------------------------------------------------------------------
    // Retention rewards
    // ------------------------------------------------------------------

    /**
     * Claim today's retention reward (daily login credits / streak),
     * as configured in the admin's App settings → Retention rewards.
     *
     * Call it wherever fits your UX — on app open for an automatic grant,
     * or behind a "Claim" button. The server enforces one claim per UTC
     * day, audience eligibility, and the streak rules, so calling it
     * repeatedly is safe.
     *
     * Check [retentionStatus] (refreshed on every [ensureUser]) to badge
     * your claim UI before calling.
     *
     * Errors ([SalesError.Http], branch on `code`):
     *   - `"already_claimed"` (409) — claimed today; `retentionStatus.nextClaimAt` says when.
     *   - `"not_eligible"`    (403) — user outside the configured audience.
     *   - `"rewards_disabled"`(404) — feature off for this app.
     */
    suspend fun claimReward(): RetentionClaimResult {
        val token = config.tokens.claimReward
        if (token.isNullOrEmpty()) {
            throw SalesError.InvalidState(
                "claimReward token not configured — regenerate SalesCentral.json from the admin's SDK config card",
            )
        }
        val resp = request(
            SalesConfig.Endpoint.CLAIM_REWARD,
            method = "POST",
            body = null,
            attachUserToken = true,
        )
        val result = RetentionClaimResult.fromJson(resp)
        synchronized(lock) {
            result.retention?.let { _retentionStatus = it }
            _currentUser = _currentUser?.copy(credits = result.credits)
        }
        SalesLog.info(
            SalesLog.Category.SDK,
            "claimReward — +${result.granted.total} credits (day ${result.granted.streakDay})",
        )
        return result
    }

    // ------------------------------------------------------------------
    // Engagement
    // ------------------------------------------------------------------

    /**
     * Record a finished foreground session. The SDK's [SessionTracker]
     * calls this for you on app lifecycle events.
     */
    suspend fun recordSession(start: Instant, end: Instant, durationSec: Int? = null) {
        val body = JSONObject().apply {
            put("startedAt", JsonUtil.formatDate(start))
            put("endedAt", JsonUtil.formatDate(end))
            durationSec?.let { put("durationSec", it) }
        }
        request(SalesConfig.Endpoint.RECORD_SESSION, method = "POST", body = body, attachUserToken = true)
    }

    /**
     * Log a single custom event. Failures are swallowed silently — events
     * are analytics-grade signals, not application state, so they should
     * never break the caller.
     *
     * Property values may be String / Number / Boolean / List / Map.
     */
    suspend fun track(name: String, properties: Map<String, Any?> = emptyMap()) {
        val body = JSONObject().apply {
            put("name", name)
            put("properties", JsonUtil.toJsonValue(properties))
            put("occurredAt", JsonUtil.formatDate(Instant.now()))
        }
        try {
            request(SalesConfig.Endpoint.RECORD_EVENT, method = "POST", body = body, attachUserToken = true)
        } catch (_: Exception) {
        }
    }

    /** One buffered analytics event for [trackBatch]. */
    data class SalesEvent(val name: String, val properties: Map<String, Any?> = emptyMap())

    /**
     * Log multiple events at once. Useful when you've buffered events
     * while offline. Max 50 events per call, 16KB per event's properties.
     */
    suspend fun trackBatch(events: List<SalesEvent>) {
        val now = JsonUtil.formatDate(Instant.now())
        val body = JSONObject().put(
            "events",
            JSONArray().apply {
                for (e in events) {
                    put(
                        JSONObject().apply {
                            put("name", e.name)
                            put("properties", JsonUtil.toJsonValue(e.properties))
                            put("occurredAt", now)
                        },
                    )
                }
            },
        )
        try {
            request(SalesConfig.Endpoint.RECORD_EVENT, method = "POST", body = body, attachUserToken = true)
        } catch (_: Exception) {
        }
    }

    // ==================================================================
    // HTTP plumbing (private)
    // ==================================================================

    private class ApiError(val error: String, val message: String?, val retention: RetentionStatus?)

    private fun parseApiError(body: ByteArray, status: Int): ApiError = try {
        val o = JSONObject(body.decodeToString())
        ApiError(
            error = o.optString("error", "unknown").ifEmpty { "unknown" },
            message = JsonUtil.optString(o, "message"),
            // Some error responses carry fresh state the app should keep —
            // e.g. a 409 already_claimed bundles the retention status (with
            // nextClaimAt) so the UI can say "come back at X" without another
            // round trip.
            retention = o.optJSONObject("retention")?.let { RetentionStatus.fromJson(it) },
        )
    } catch (_: Exception) {
        ApiError("http_$status", null, null)
    }

    /**
     * Endpoints that must carry a hardware assertion. Mirrors the server's
     * ENDPOINT_META `assert` flags — keep the two lists in sync.
     */
    private val assertedEndpoints = setOf(
        SalesConfig.Endpoint.CREATE_OR_FETCH_USER,
        SalesConfig.Endpoint.RESTORE_USER,
        SalesConfig.Endpoint.APPLY_PURCHASES,
        SalesConfig.Endpoint.SPEND_CREDITS,
        SalesConfig.Endpoint.CLAIM_REWARD,
    )

    /**
     * 401 codes that mean "your attest proof was bad", NOT "your user token
     * is bad" — these must not wipe the stored user session.
     */
    private val attestErrorCodes = setOf(
        "attestation_required", "unknown_attest_key", "invalid_challenge",
        "invalid_assertion", "assertion_replay", "attest_config_missing",
    )

    /**
     * Coalesces concurrent first-launch attest flows so only one key is
     * generated + registered (the analog of the actor-isolated in-flight
     * Task on iOS).
     */
    private val attestMutex = Mutex()
    private var attestInFlight: CompletableDeferred<String>? = null

    /** One-time flag so the unattested warning logs once per process. */
    @Volatile
    private var warnedUnattested = false

    private suspend fun fetchAttestChallenge(): String {
        val resp = request(
            SalesConfig.Endpoint.ATTEST_CHALLENGE,
            method = "POST",
            body = null,
            attachUserToken = false,
        )
        return resp.optString("challenge", "")
    }

    /**
     * First-launch flow: generate a hardware key, have the platform attest
     * it, register it with the server, persist the keyId. Subsequent calls
     * return the stored keyId. Concurrent first-time callers share a single
     * in-flight attempt so only one key is ever generated and registered.
     */
    private suspend fun ensureAttestedKeyId(): String {
        tokenStore.readAttestKeyId()?.let { return it }
        val (deferred, isOwner) = attestMutex.withLock {
            attestInFlight?.let { it to false } ?: run {
                val d = CompletableDeferred<String>()
                attestInFlight = d
                d to true
            }
        }
        if (!isOwner) return deferred.await()
        try {
            if (!attestService.isSupported) throw SalesError.AttestUnsupported()
            val keyId = performFirstAttest()
            deferred.complete(keyId)
            return keyId
        } catch (e: Exception) {
            deferred.completeExceptionally(e)
            throw e
        } finally {
            // Clear on success AND failure so a later call can retry.
            attestMutex.withLock { attestInFlight = null }
        }
    }

    /**
     * Generate a hardware key, have the platform attest it, register it
     * with the server, and persist the keyId.
     */
    private suspend fun performFirstAttest(): String {
        val keyId = attestService.generateKey()
        val challenge = fetchAttestChallenge()
        val challengeData = Base64Url.decode(challenge)
            ?: throw SalesError.InvalidState("server challenge was not base64url")
        val attestation = attestService.attestKey(keyId, sha256(challengeData))
        request(
            SalesConfig.Endpoint.ATTEST_KEY,
            method = "POST",
            body = JSONObject().apply {
                put("keyId", keyId)
                put("attestation", Base64Url.encodeStandard(attestation))
                put("challenge", challenge)
                // Tells the server which verifier to use (Play Integrity vs
                // the Apple default).
                attestService.platform?.let { put("platform", it) }
            },
            attachUserToken = false,
        )
        tokenStore.writeAttestKeyId(keyId)
        SalesLog.info(SalesLog.Category.HTTP, "device attest key registered")
        return keyId
    }

    /**
     * Assertion headers for one asserted call. clientDataHash =
     * SHA256(challengeBytes ‖ SHA256(exact body bytes)) — must match the
     * server's recipe bit-for-bit.
     */
    private suspend fun attestHeaders(bodyData: ByteArray): Map<String, String> {
        if (!attestService.isSupported) throw SalesError.AttestUnsupported()
        val keyId = ensureAttestedKeyId()
        val challenge = fetchAttestChallenge()
        val challengeData = Base64Url.decode(challenge)
            ?: throw SalesError.InvalidState("server challenge was not base64url")
        val clientDataHash = sha256(challengeData + sha256(bodyData))

        var activeKeyId = keyId
        val assertion: ByteArray = try {
            attestService.generateAssertion(activeKeyId, clientDataHash)
        } catch (e: Exception) {
            // The stored key can no longer sign — its hardware key was
            // invalidated (device restore, key rotation). Discard it, attest
            // a fresh key, and sign once more. The challenge above is not
            // consumed until the asserted request reaches the server, so it
            // stays valid here.
            SalesLog.warn(SalesLog.Category.SDK, "generateAssertion failed for stored key ($e) — re-attesting a fresh key")
            tokenStore.clearAttestKeyId()
            activeKeyId = ensureAttestedKeyId()
            attestService.generateAssertion(activeKeyId, clientDataHash)
        }
        return mapOf(
            "x-attest-key-id" to activeKeyId,
            "x-attest-challenge" to challenge,
            "x-attest-assertion" to Base64Url.encodeStandard(assertion),
        )
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private suspend fun request(
        endpoint: SalesConfig.Endpoint,
        method: String,
        body: JSONObject?,
        attachUserToken: Boolean,
        isAttestRetry: Boolean = false,
    ): JSONObject {
        val url = config.urlFor(endpoint)
        val headers = mutableMapOf<String, String>()
        headers["x-app-key"] = config.apiKey
        if (attachUserToken) {
            tokenStore.read()?.let { headers["x-user-token"] = it }
        }
        val bodyData: ByteArray? = body?.toString()?.toByteArray(Charsets.UTF_8)
        if (endpoint in assertedEndpoints) {
            if (attestService.isSupported) {
                headers += attestHeaders(bodyData ?: ByteArray(0))
            } else {
                // This platform cannot produce an attestation the server can
                // verify (the backend supports Apple App Attest only today).
                // Signal it explicitly — the server quarantines the session
                // as a SANDBOX identity: play-money credits, Sandbox-only
                // receipts, excluded from production analytics.
                headers["x-attest-unsupported"] = "1"
                if (!warnedUnattested) {
                    warnedUnattested = true
                    SalesLog.warn(
                        SalesLog.Category.SDK,
                        "Device attestation unavailable — running UNATTESTED. This identity is SANDBOX " +
                            "(excluded from production data). The backend needs Play Integrity support " +
                            "for attested Android sessions.",
                    )
                }
            }
        }
        if (bodyData != null) headers["Content-Type"] = "application/json"

        SalesLog.debug(SalesLog.Category.HTTP, "→ $method $endpoint")
        val start = System.currentTimeMillis()
        val resp = try {
            transport.execute(url, method, headers, bodyData)
        } catch (e: IOException) {
            SalesLog.error(SalesLog.Category.HTTP, "✗ $method $endpoint network: ${e.message}")
            throw SalesError.Network(e.message ?: e.javaClass.simpleName)
        }
        val ms = System.currentTimeMillis() - start
        if (resp.status !in 200..299) {
            val err = parseApiError(resp.body, resp.status)
            SalesLog.warn(
                SalesLog.Category.HTTP,
                "← ${resp.status} $endpoint (${ms}ms) error=${err.error}" +
                    (err.message?.let { " message=$it" } ?: ""),
            )
            // Ingest state the server attached to the error body before
            // throwing — the response is authoritative even on a 4xx.
            err.retention?.let { r -> synchronized(lock) { _retentionStatus = r } }
            // Attest rejections are about the DEVICE key, not the user
            // session — recover the key, don't wipe the user.
            if (resp.status == 401 && err.error == "unknown_attest_key" &&
                endpoint in assertedEndpoints && !isAttestRetry
            ) {
                tokenStore.clearAttestKeyId()
                return request(endpoint, method, body, attachUserToken, isAttestRetry = true)
            }
            // 403 token_key_mismatch: the user JWT is bound to a previous
            // device key (e.g. after re-attestation) — the session is stale,
            // not the key. Re-mint the JWT against the current key, retry once.
            if (resp.status == 403 && err.error == "token_key_mismatch" &&
                endpoint in assertedEndpoints && attachUserToken && !isAttestRetry
            ) {
                tokenStore.clear()
                ensureUser()
                return request(endpoint, method, body, attachUserToken, isAttestRetry = true)
            }
            // 401 → user token is invalid. Wipe it so the next call starts
            // fresh via /users instead of looping on bad credentials. Also drop
            // the cached user + derived caches: otherwise the app keeps showing
            // the last-known (possibly premium) state the server just
            // invalidated. We keep the clientId so the next ensureUser()
            // de-dupes back to the SAME guest rather than minting a new one —
            // a 401 is an expired session, not an identity reset.
            if (resp.status == 401 && err.error !in attestErrorCodes) {
                tokenStore.clear()
                synchronized(lock) {
                    _currentUser = null
                    _paywallsByKey = emptyMap()
                    _remoteConfigCache = emptyMap()
                    _experimentAssignments = emptyMap()
                }
            }
            throw SalesError.Http(status = resp.status, errorCode = err.error, serverMessage = err.message)
        }
        SalesLog.debug(SalesLog.Category.HTTP, "← ${resp.status} $endpoint (${ms}ms, ${resp.body.size} bytes)")
        return try {
            if (resp.body.isEmpty()) JSONObject() else JSONObject(resp.body.decodeToString())
        } catch (e: Exception) {
            SalesLog.error(SalesLog.Category.HTTP, "✗ $method $endpoint decode: ${e.message}")
            throw SalesError.Decoding(e.message ?: "invalid JSON")
        }
    }
}
