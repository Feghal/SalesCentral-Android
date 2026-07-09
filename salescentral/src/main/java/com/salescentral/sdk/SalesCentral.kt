package com.salescentral.sdk

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ApplicationInfo
import com.android.billingclient.api.ProductDetails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Top-level entry point. The SDK reads its configuration from a
 * `SalesCentral.json` file in your app's assets folder. Generate the file
 * from the admin's "SDK config" card. You then make exactly **one** call
 * from your app launch path:
 *
 * ```kotlin
 * class MyApp : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         MainScope().launch { SalesCentral.start(this@MyApp) }
 *     }
 * }
 * ```
 *
 * That's it — no separate config call to wire up.
 *
 * Everywhere else in your app:
 *
 * ```kotlin
 * SalesCentral.purchase(activity, product)
 * SalesCentral.shared.spendCredits(50, reason = "image_gen")
 * ```
 *
 * ## Configuration order
 * Unlike iOS (where the bundle is globally readable), Android needs a
 * [Context] to read assets — so reference [shared] / [store] only after
 * [start] or [configure] has run once. Both are idempotent.
 *
 * ## Custom config (tests, dynamic setups)
 * Call `SalesCentral.configure(context, myConfig)` before [start] to inject
 * an explicit [SalesConfig] instead of reading the assets file.
 */
object SalesCentral {

    // ------------------------------------------------------------------
    // Storage
    // ------------------------------------------------------------------

    private var _client: SalesClient? = null
    private var _store: SalesStore? = null
    private var _billing: PlayBillingConnector? = null
    private var _bootstrapped = false

    /**
     * In-flight (or already-resolved) job for loading the app's configured
     * Play products. [start] kicks this off right after bootstrap.
     * Concurrent [loadProducts] callers await the same job.
     */
    private var _productsTask: Deferred<List<ProductDetails>>? = null

    /**
     * Watches for network reconnection to retry a bootstrap that failed
     * offline. Created lazily on failure, stopped once bootstrap succeeds.
     */
    private var _reconnectMonitor: NetworkMonitor? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val configLock = Any()

    // Serializes start() — the Kotlin analog of the Swift facade's
    // @MainActor isolation. Guards _bootstrapped and the observer /
    // prefetch kick-off against concurrent start() calls (e.g. one from
    // Application.onCreate and a defensive one from a splash screen).
    private val startMutex = Mutex()

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Configure + bootstrap the SDK in one shot. Reads
     * `assets/SalesCentral.json` if no explicit [configure] was called,
     * then ensures a user, fetches their current subscription, starts the
     * Play purchase observer, and starts the session tracker.
     *
     * Safe to call multiple times — the bootstrap half is gated by an
     * internal flag and subsequent calls are no-ops.
     */
    suspend fun start(context: Context) {
        ensureConfigured(context)
        startMutex.withLock {
            if (_bootstrapped) {
                SalesLog.debug(SalesLog.Category.SDK, "start() called again — bootstrap already complete, no-op")
                return
            }
            SalesLog.info(SalesLog.Category.SDK, "start() — bootstrapping…")
            val store = _store!!
            // Single-flight: concurrent start()/loadProducts triggers share ONE
            // attempt (no duplicate user creation), and a failed attempt leaves
            // the store un-bootstrapped so we can retry.
            store.ensureBootstrapped()
            if (!store.didBootstrap) {
                // No user established (e.g. offline first launch). Do NOT mark
                // _bootstrapped — leave it retryable — and watch for reconnect so
                // we recover automatically once the network returns.
                SalesLog.warn(
                    SalesLog.Category.SDK,
                    "start() — bootstrap failed (no user; likely offline). Watching for reconnect to retry.",
                )
                startReconnectMonitor(context.applicationContext)
                return
            }
            _bootstrapped = true
            _reconnectMonitor?.stop()
            _reconnectMonitor = null
            SalesLog.info(SalesLog.Category.SDK, "start() — bootstrap complete; starting observer + product prefetch")
            // Watch for out-of-band purchases (renewals, pending purchases
            // resolving, unacknowledged purchases from a previous run).
            _billing?.startObserving()
            // Right after bootstrap, the SalesClient knows which Play SKUs the
            // admin has registered for this app (returned from createOrFetchUser).
            // Kick off the Play lookup in the background — loadProducts() awaits
            // the same job. Don't await it here so first paint isn't blocked.
            synchronized(configLock) {
                if (_productsTask == null) {
                    _productsTask = scope.async { fetchProductsFromPlay() }
                }
            }
        }
    }

    /**
     * Watch for network reconnection and retry [start] once we're online,
     * so a first launch with no internet recovers without the user
     * relaunching the app. Idempotent; stopped once bootstrap completes.
     */
    private fun startReconnectMonitor(appContext: Context) {
        if (_reconnectMonitor != null) return
        val m = NetworkMonitor(appContext)
        m.onReconnect = { scope.launch { start(appContext) } }
        _reconnectMonitor = m
        m.start()
    }

    /**
     * Inject an explicit [SalesConfig] instead of reading the assets file.
     * Useful in tests and apps that resolve config from a remote service.
     *
     * First call wins; later calls are ignored. Use [reset] first to
     * re-configure mid-process.
     */
    fun configure(context: Context, config: SalesConfig) {
        synchronized(configLock) {
            if (_client != null) {
                SalesLog.debug(SalesLog.Category.SDK, "configure() ignored — already configured")
                return
            }
            val appContext = context.applicationContext
            // Default logging on for debuggable builds (the analog of the
            // Swift SDK's DEBUG default).
            if ((appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                SalesLog.isEnabled = true
            }
            val resolved = if (config.tokenStore == null) {
                config.withTokenStore(SharedPrefsTokenStore(appContext))
            } else {
                config
            }
            val attest: DeviceAttestService = if (resolved.playIntegrity) {
                PlayIntegrityAttestService(appContext)
            } else {
                UnsupportedAttestService()
            }
            val client = SalesClient(resolved, attestService = attest, androidContext = appContext)
            val billing = PlayBillingConnector(appContext, client)
            client.receiptsProvider = { billing.queryCurrentReceipts() }
            val store = SalesStore(client)
            billing.onObservedPurchaseApplied = { store.refreshSubscription() }
            _client = client
            _billing = billing
            _store = store
            SalesLog.info(SalesLog.Category.SDK, "configured for baseURL=${resolved.baseUrl}")
        }
    }

    /**
     * Drop the configured client (test hook). After [reset], the next
     * [start] / [configure] re-reads configuration.
     */
    fun reset() {
        synchronized(configLock) {
            SalesLog.debug(SalesLog.Category.SDK, "reset() — clearing configuration")
            _productsTask?.cancel()
            _productsTask = null
            _reconnectMonitor?.stop()
            _reconnectMonitor = null
            _client = null
            _store = null
            _billing = null
            _bootstrapped = false
        }
    }

    // ------------------------------------------------------------------
    // Shared instances
    // ------------------------------------------------------------------

    /**
     * The shared [SalesClient]. Available after the first [configure] /
     * [start] call — reference it from anywhere for direct API calls.
     */
    val shared: SalesClient
        get() = _client ?: throw IllegalStateException(
            "SalesCentral is not configured — call SalesCentral.start(context) " +
                "(or configure(context, config)) from your Application.onCreate first.",
        )

    /**
     * The shared [SalesStore]. Collect its [SalesStore.user] /
     * [SalesStore.subscription] flows to drive UI re-renders when user /
     * subscription state changes.
     */
    val store: SalesStore
        get() = _store ?: throw IllegalStateException(
            "SalesCentral is not configured — call SalesCentral.start(context) " +
                "(or configure(context, config)) from your Application.onCreate first.",
        )

    /** Has the SDK been configured (via [start] or [configure])? */
    val isConfigured: Boolean get() = _client != null

    // ------------------------------------------------------------------
    // Logging
    // ------------------------------------------------------------------

    /**
     * Toggle SDK logging. Verbose by default in debuggable builds; silent
     * in release. Lines route through logcat under the tag `SalesCentral`.
     */
    var loggingEnabled: Boolean
        get() = SalesLog.isEnabled
        set(value) {
            SalesLog.isEnabled = value
        }

    // ------------------------------------------------------------------
    // Products
    // ------------------------------------------------------------------

    /**
     * Fetch the Play products the admin registered for this app.
     *
     * **You don't pass identifiers.** The SDK already knows them — the
     * configured SKUs come down with [start]'s `ensureUser` round-trip,
     * so the app can stay product-list-agnostic. Add / remove products
     * from the admin panel and they appear / disappear here on the next
     * launch, without an app update.
     *
     * Behavior:
     *   - If [start] already kicked off the load and it finished:
     *     returns the cached list immediately.
     *   - If [start] kicked it off but it's still running: awaits.
     *   - If [start] hasn't completed bootstrap yet: starts the product
     *     load now, then awaits.
     *
     * Concurrent callers all share the same in-flight job. Use
     * [reloadProducts] to force a fresh fetch (e.g. after the operator
     * just added a new product).
     */
    suspend fun loadProducts(): List<ProductDetails> {
        shared // throws a descriptive error when unconfigured
        val task = synchronized(configLock) {
            _productsTask ?: run {
                SalesLog.debug(SalesLog.Category.STORE, "loadProducts() — no prefetch in flight, fetching on demand")
                scope.async { fetchProductsFromPlay() }.also { _productsTask = it }
            }
        }
        return try {
            task.await().also {
                SalesLog.info(SalesLog.Category.STORE, "loadProducts() returned ${it.size} product(s)")
            }
        } catch (e: Exception) {
            SalesLog.error(SalesLog.Category.STORE, "loadProducts() failed: ${e.message}")
            throw e
        }
    }

    /**
     * Force a refetch of the registered SKUs + Play lookup. Use after an
     * admin-panel change you want to pick up without restarting the app.
     * Otherwise [start] does this once per launch.
     */
    suspend fun reloadProducts(): List<ProductDetails> {
        shared
        SalesLog.info(SalesLog.Category.STORE, "reloadProducts() — refetching SKUs + Play lookup")
        val task = scope.async { fetchProductsFromPlay(forceRefreshIds = true) }
        synchronized(configLock) { _productsTask = task }
        return try {
            task.await().also {
                SalesLog.info(SalesLog.Category.STORE, "reloadProducts() returned ${it.size} product(s)")
            }
        } catch (e: Exception) {
            SalesLog.error(SalesLog.Category.STORE, "reloadProducts() failed: ${e.message}")
            throw e
        }
    }

    /**
     * Convenience: look up a single registered product by SKU. Returns
     * null if the admin hasn't configured this id (or Google Play doesn't
     * recognize it).
     */
    suspend fun loadProduct(identifier: String): ProductDetails? =
        loadProducts().firstOrNull { it.productId == identifier }

    /**
     * Ask the client for the registered SKUs, then resolve them with Play.
     * Optionally re-runs `ensureUser` first so a freshly added product in
     * the admin shows up without a relaunch.
     */
    private suspend fun fetchProductsFromPlay(forceRefreshIds: Boolean = false): List<ProductDetails> {
        val client = shared
        val billing = _billing ?: throw SalesError.InvalidState("billing connector missing")
        if (forceRefreshIds) {
            SalesLog.debug(SalesLog.Category.STORE, "fetchProductsFromPlay — forcing ensureUser to refresh SKU list")
            client.ensureUser()
        }
        val ids = client.configuredProductIds
        SalesLog.debug(
            SalesLog.Category.STORE,
            "fetchProductsFromPlay — asking Play for ${ids.size} SKU(s): ${ids.joinToString(", ")}",
        )
        if (ids.isEmpty()) {
            SalesLog.warn(SalesLog.Category.STORE, "fetchProductsFromPlay — no SKUs registered for this app in the admin")
            return emptyList()
        }
        return billing.loadProducts(ids)
    }

    // ------------------------------------------------------------------
    // Purchase (end-to-end)
    // ------------------------------------------------------------------

    /**
     * Drive Google Play's purchase dialog, upload the receipt to your
     * SalesCentral backend, apply effects on the user, acknowledge the
     * purchase so Google doesn't auto-refund it, and refresh the shared
     * [SalesStore] so UI sees the new state immediately.
     *
     * Throws on network errors or backend rejection
     * (e.g. `product_not_registered`). UI should branch on the returned
     * [PurchaseResult] for normal flow outcomes and catch for retryable
     * failures.
     *
     * [offerToken] selects a specific subscription offer; when null the
     * product's first offer is used.
     */
    suspend fun purchase(
        activity: Activity,
        product: ProductDetails,
        offerToken: String? = null,
    ): PurchaseResult {
        val billing = _billing ?: throw IllegalStateException(
            "SalesCentral is not configured — call SalesCentral.start(context) first.",
        )
        val result = billing.purchase(activity, product, offerToken)
        if (result is PurchaseResult.Success || result is PurchaseResult.NotEntitled) {
            store.syncAfterPurchase(shared.currentUser)
        }
        return result
    }

    /**
     * Convenience: look up the product by id and purchase it in one call.
     * The lookup hits the SDK's product cache (populated by [start]), so
     * it's free after first launch.
     *
     * Throws `SalesError.InvalidState("product_not_found:<id>")` when:
     *   - the admin hasn't registered this SKU, OR
     *   - Google Play doesn't recognize it (typo, missing in Play Console).
     */
    suspend fun purchase(activity: Activity, productId: String): PurchaseResult {
        val product = loadProduct(productId)
            ?: throw SalesError.InvalidState("product_not_found:$productId")
        return purchase(activity, product)
    }

    // ------------------------------------------------------------------
    // Push notifications
    // ------------------------------------------------------------------

    /**
     * Register the FCM registration token your `FirebaseMessagingService`
     * received in `onNewToken`. The SDK captures the current notification
     * permission state and ships both up to the backend via
     * `updateContext`. Re-registering the same token is cheap — the
     * backend dedupes and just bumps `lastUsedAt`.
     *
     * ```kotlin
     * override fun onNewToken(token: String) {
     *     scope.launch { runCatching { SalesCentral.registerPushToken(token) } }
     * }
     * ```
     *
     * NOTE: the current SalesCentral backend sends via APNs only — FCM
     * sending is a pending backend feature; the token is stored either way.
     */
    suspend fun registerPushToken(token: String) {
        val client = shared
        val context = client.androidContext
        val push = PushContext(
            token = token,
            // FCM has no sandbox/production split like APNs.
            environment = "production",
            authStatus = context?.let { pushAuthStatusString(it) },
            appVersion = context?.let { AppContext.current(it).version },
            bundleId = context?.packageName,
        )
        SalesLog.info(
            SalesLog.Category.PUSH,
            "registerPushToken — auth=${push.authStatus ?: "nil"} token=${token.take(8)}…",
        )
        client.updateContext(UserContext(push = push))
    }

    /**
     * Tell the backend this device no longer wants to receive pushes.
     * Marks the most recent token inactive on the user record. The token
     * itself is left in the array so we can resurrect it if the user
     * opts back in.
     */
    suspend fun unregisterPushToken() {
        val client = shared
        SalesLog.info(SalesLog.Category.PUSH, "unregisterPushToken")
        val push = PushContext(
            authStatus = client.androidContext?.let { pushAuthStatusString(it) },
        )
        client.updateContext(UserContext(push = push))
    }

    /**
     * Read the current notification permission as the string the backend
     * stores. Android exposes a boolean, so this maps to
     * "authorized" / "denied".
     */
    private fun pushAuthStatusString(context: Context): String? {
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return null
            if (nm.areNotificationsEnabled()) "authorized" else "denied"
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------
    // Private
    // ------------------------------------------------------------------

    /**
     * Lazily configure from `assets/SalesCentral.json` if no explicit
     * [configure] has been called yet.
     */
    private fun ensureConfigured(context: Context) {
        if (_client != null) return
        configure(context, SalesConfig.fromAssets(context))
    }
}

// ----------------------------------------------------------------------
// SalesPaywall extension
// ----------------------------------------------------------------------

/**
 * Load the Play [ProductDetails] for this paywall in one call.
 *
 * Equivalent to `SalesCentral.loadProducts().filter` + a reorder, but
 * folded into one ergonomic call. The SDK already prefetched every
 * registered product during [SalesCentral.start] (or on first
 * [SalesCentral.loadProducts] access), so this is just an in-memory filter
 * most of the time — no extra Play round-trip per paywall.
 *
 * The returned list preserves `paywall.productIds` order so the operator's
 * chosen display order is honoured. SKUs the admin lists that Google Play
 * doesn't recognise are silently dropped from the result; inspect
 * `paywall.productIds.size` vs `products.size` if that mismatch matters
 * to you.
 *
 * ```kotlin
 * val paywall = SalesCentral.shared.paywall(key = "main")
 * val products = paywall.loadProducts()   // List<ProductDetails>
 * ```
 */
suspend fun SalesPaywall.loadProducts(): List<ProductDetails> {
    SalesLog.debug(SalesLog.Category.PAYWALL, "paywall.loadProducts() — $key wants ${productIds.size} SKU(s)")
    val all = SalesCentral.loadProducts()
    val byId = all.associateBy { it.productId }
    val products = productIds.mapNotNull { byId[it] }
    if (products.size < productIds.size) {
        val missing = productIds.filter { it !in byId }
        SalesLog.warn(
            SalesLog.Category.PAYWALL,
            "paywall.loadProducts() — $key missing ${missing.size} SKU(s): ${missing.joinToString(", ")}",
        )
    }
    SalesLog.info(SalesLog.Category.PAYWALL, "paywall.loadProducts() — $key resolved ${products.size} product(s)")
    return products
}
