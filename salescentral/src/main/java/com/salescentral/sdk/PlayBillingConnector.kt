package com.salescentral.sdk

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.consumePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owns the Google Play [BillingClient] — the Android analog of the iOS
 * SDK's StoreKit integration (`Product.products(for:)`, the purchase
 * dialog, and the `Transaction.updates` observer, in one class).
 *
 * Responsibilities:
 *  - load [ProductDetails] for the SKUs the admin registered (never
 *    hard-coded in app code),
 *  - drive the purchase dialog and upload the receipt to the backend,
 *  - observe out-of-band purchases (renewals, pending purchases resolving,
 *    purchases made while the app was off) and auto-upload them,
 *  - acknowledge/consume a purchase ONLY after the server accepted it —
 *    the analog of iOS finishing a transaction only after upload. An
 *    unacknowledged purchase is auto-refunded by Google after 3 days, so
 *    a purchase the backend never accepted is never silently kept.
 *
 * Owned by [SalesCentral]; you don't normally construct this directly.
 */
class PlayBillingConnector(
    context: Context,
    private val client: SalesClient,
    /** Called after an observed (out-of-band) purchase was applied server-side. */
    internal var onObservedPurchaseApplied: (suspend () -> Unit)? = null,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The one in-flight purchase() call awaiting its listener callback.
     * Google's [PurchasesUpdatedListener] carries no correlation id back to
     * the [BillingClient.launchBillingFlow] call that triggered it, so
     * concurrent dialogs can't be told apart — [purchaseMutex] therefore
     * single-flights the whole purchase flow (Play shows at most one billing
     * dialog at a time anyway), and this field holds its awaiter. Purchases
     * that don't match it take the observer (auto-upload) path.
     */
    private val pendingLock = Any()
    private var activePurchase: PendingFlow? = null
    private val purchaseMutex = Mutex()

    private class PendingFlow(
        val productIds: Set<String>,
        val deferred: CompletableDeferred<PurchaseOutcome>,
    )

    private sealed class PurchaseOutcome {
        data class Purchased(val purchase: Purchase) : PurchaseOutcome()
        object Cancelled : PurchaseOutcome()
        object AlreadyOwned : PurchaseOutcome()
        data class Failed(val code: Int, val message: String) : PurchaseOutcome()
    }

    private val listener = PurchasesUpdatedListener { result, purchases ->
        onPurchasesUpdated(result, purchases)
    }

    private val billingClient: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(listener)
        .enablePendingPurchases()
        .build()

    private val connectMutex = Mutex()
    @Volatile
    private var observing = false

    // ------------------------------------------------------------------
    // Connection
    // ------------------------------------------------------------------

    /** Connect (or reuse the live connection). Throws [SalesError.Network] when Play is unreachable. */
    private suspend fun ensureConnected() {
        if (billingClient.isReady) return
        connectMutex.withLock {
            if (billingClient.isReady) return
            val deferred = CompletableDeferred<BillingResult>()
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    deferred.complete(result)
                }

                override fun onBillingServiceDisconnected() {
                    // Reconnection happens lazily on the next ensureConnected().
                    SalesLog.debug(SalesLog.Category.OBSERVER, "billing service disconnected")
                }
            })
            val result = deferred.await()
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                throw SalesError.Network("Play Billing connection failed: ${result.debugMessage} (${result.responseCode})")
            }
            SalesLog.info(SalesLog.Category.OBSERVER, "Play Billing connected")
        }
    }

    /**
     * Start observing out-of-band purchases. Connects and sweeps existing
     * unacknowledged purchases (the analog of `Transaction.updates`
     * redelivering unfinished transactions on launch). Idempotent.
     */
    fun startObserving() {
        if (observing) return
        observing = true
        scope.launch {
            try {
                ensureConnected()
                sweepUnacknowledged()
            } catch (e: Exception) {
                SalesLog.warn(SalesLog.Category.OBSERVER, "startObserving: ${e.message}")
                observing = false
            }
        }
    }

    /** Upload + finish any PURCHASED-but-unacknowledged purchases. */
    private suspend fun sweepUnacknowledged() {
        for (type in listOf(BillingClient.ProductType.INAPP, BillingClient.ProductType.SUBS)) {
            val result = billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(type).build(),
            )
            if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) continue
            for (purchase in result.purchasesList) {
                if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED && !purchase.isAcknowledged) {
                    handleObservedPurchase(purchase)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Products
    // ------------------------------------------------------------------

    /**
     * Resolve admin-registered SKUs into Play [ProductDetails]. Splits the
     * query by the catalog's product type (`subscription` → SUBS, everything
     * else → INAPP) because Play requires the type up front. The returned
     * list preserves [ids] order.
     */
    suspend fun loadProducts(ids: List<String>): List<ProductDetails> {
        if (ids.isEmpty()) return emptyList()
        ensureConnected()
        val catalog = client.configuredProducts.associateBy { it.productId }
        val subs = ids.filter { catalog[it]?.isSubscription == true }
        val inapp = ids.filter { catalog[it]?.isSubscription != true }

        val found = mutableMapOf<String, ProductDetails>()
        suspend fun query(type: String, productIds: List<String>) {
            if (productIds.isEmpty()) return
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(
                    productIds.map {
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(it)
                            .setProductType(type)
                            .build()
                    },
                )
                .build()
            val result = billingClient.queryProductDetails(params)
            if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                throw SalesError.Network(
                    "Play product query failed: ${result.billingResult.debugMessage} " +
                        "(${result.billingResult.responseCode})",
                )
            }
            result.productDetailsList?.forEach { found[it.productId] = it }
        }
        query(BillingClient.ProductType.SUBS, subs)
        query(BillingClient.ProductType.INAPP, inapp)

        if (found.size < ids.size) {
            val missing = ids.filter { it !in found }
            SalesLog.warn(
                SalesLog.Category.STORE,
                "loadProducts — Google Play did not return: ${missing.sorted().joinToString(", ")}",
            )
        }
        return ids.mapNotNull { found[it] }
    }

    // ------------------------------------------------------------------
    // Purchase (end-to-end)
    // ------------------------------------------------------------------

    /**
     * Drive Google Play's purchase dialog, upload the receipt to the
     * SalesCentral backend, apply effects on the user, acknowledge/consume
     * the purchase so Google doesn't refund it, and return the outcome.
     *
     * Single-flight: concurrent calls queue on [purchaseMutex] and run one
     * dialog at a time. Play can't attribute a listener callback to a
     * specific launchBillingFlow call, so overlapping dialogs would make
     * outcomes (especially cancellations) ambiguous — and Play only shows
     * one billing sheet at a time anyway.
     *
     * [offerToken] selects a specific subscription offer; when null the
     * first offer is used (the base plan for single-offer products).
     */
    suspend fun purchase(
        activity: Activity,
        product: ProductDetails,
        offerToken: String? = null,
    ): PurchaseResult = purchaseMutex.withLock {
        ensureConnected()
        SalesLog.info(SalesLog.Category.STORE, "purchase(${product.productId}) — opening Play dialog")

        val paramsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(product)
        if (product.productType == BillingClient.ProductType.SUBS) {
            val token = offerToken
                ?: product.subscriptionOfferDetails?.firstOrNull()?.offerToken
                ?: throw SalesError.InvalidState("product_has_no_offers:${product.productId}")
            paramsBuilder.setOfferToken(token)
        }
        val flowBuilder = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(paramsBuilder.build()))
        // Stamp the purchase with our user id (the analog of Apple's
        // appAccountToken) — Google echoes it back on the purchase so the
        // server can tie webhooks to this user.
        client.currentUser?.id?.let { flowBuilder.setObfuscatedAccountId(it) }

        val flow = PendingFlow(setOf(product.productId), CompletableDeferred())
        synchronized(pendingLock) { activePurchase = flow }
        try {
            val launch = withContext(Dispatchers.Main) {
                billingClient.launchBillingFlow(activity, flowBuilder.build())
            }
            when (launch.responseCode) {
                BillingClient.BillingResponseCode.OK -> {}
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED ->
                    flow.deferred.complete(PurchaseOutcome.AlreadyOwned)
                else -> flow.deferred.complete(
                    PurchaseOutcome.Failed(launch.responseCode, launch.debugMessage),
                )
            }
            when (val outcome = flow.deferred.await()) {
                is PurchaseOutcome.Cancelled -> {
                    SalesLog.info(SalesLog.Category.STORE, "purchase(${product.productId}) — user cancelled")
                    PurchaseResult.UserCancelled
                }
                is PurchaseOutcome.AlreadyOwned -> {
                    // No new purchase happened. Re-upload the existing one so
                    // the backend can decide whether it still grants anything
                    // (mirrors iOS's expired/already-owned handling).
                    SalesLog.info(SalesLog.Category.STORE, "purchase(${product.productId}) — already owned, re-applying")
                    applyOwnedPurchase(product.productId)
                }
                is PurchaseOutcome.Failed -> throw SalesError.InvalidState(
                    "billing_error_${outcome.code}: ${outcome.message}",
                )
                is PurchaseOutcome.Purchased -> finishPurchase(product.productId, outcome.purchase)
            }
        } finally {
            synchronized(pendingLock) { activePurchase = null }
        }
    }

    /** Upload + acknowledge one dialog-delivered purchase. */
    private suspend fun finishPurchase(productId: String, purchase: Purchase): PurchaseResult {
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PENDING -> {
                SalesLog.info(SalesLog.Category.STORE, "purchase($productId) — pending (slow payment / approval)")
                return PurchaseResult.Pending
            }
            Purchase.PurchaseState.PURCHASED -> {}
            else -> {
                SalesLog.warn(SalesLog.Category.STORE, "purchase($productId) — UNSPECIFIED purchase state")
                return PurchaseResult.Unverified("unspecified_purchase_state")
            }
        }
        val txnId = purchase.purchaseToken
        SalesLog.info(SalesLog.Category.STORE, "purchase($productId) — purchased, uploading receipt")
        // Claim this purchase so the observer doesn't ALSO upload it
        // concurrently (which would race on the server). purchase() is the
        // authoritative uploader here — it needs the response.
        client.claimTransaction(txnId)
        val resp: ApplyResult
        try {
            resp = client.applyReceipt(receiptString(purchase))
        } catch (e: Exception) {
            // Upload failed (offline / server error). Release the claim so
            // the observer can retry this purchase — otherwise it stays
            // claimed and unacknowledged, skipped by the observer until the
            // next cold launch.
            client.unclaimTransaction(txnId)
            throw e
        }
        // The server accepted the receipt — NOW finish the purchase with
        // Google (the analog of txn.finish()).
        finishWithPlay(purchase)
        // The backend rejects receipts it can't honor (expired / revoked /
        // unregistered). Don't report those as a successful purchase — the
        // user gained nothing new.
        val first = resp.applied.firstOrNull()
        if (first != null && !first.ok) {
            SalesLog.warn(SalesLog.Category.STORE, "purchase($productId) — receipt not applied: ${first.error ?: "unknown"}")
            return PurchaseResult.NotEntitled(first.error ?: "not_entitled")
        }
        SalesLog.info(SalesLog.Category.STORE, "purchase($productId) — applied ${resp.applied.size} receipt(s)")
        return PurchaseResult.Success(resp.applied)
    }

    /** Re-apply an already-owned purchase (ITEM_ALREADY_OWNED path). */
    private suspend fun applyOwnedPurchase(productId: String): PurchaseResult {
        for (type in listOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP)) {
            val result = billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(type).build(),
            )
            val purchase = result.purchasesList.firstOrNull { productId in it.products }
            if (purchase != null) return finishPurchase(productId, purchase)
        }
        return PurchaseResult.NotEntitled("already_owned_purchase_not_found")
    }

    // ------------------------------------------------------------------
    // Observer path (out-of-band purchases)
    // ------------------------------------------------------------------

    private fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                for (purchase in purchases.orEmpty()) {
                    val awaiter = synchronized(pendingLock) {
                        val active = activePurchase
                        if (active != null && purchase.products.any { it in active.productIds }) {
                            activePurchase = null
                            active.deferred
                        } else {
                            null
                        }
                    }
                    if (awaiter != null) {
                        awaiter.complete(PurchaseOutcome.Purchased(purchase))
                    } else if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        // Out-of-band: renewal, pending purchase resolving, or a
                        // purchase from another surface. Auto-upload like the
                        // iOS Transaction.updates observer.
                        scope.launch { handleObservedPurchase(purchase) }
                    }
                }
            }
            // Non-OK responses carry no purchases; there is at most one
            // dialog in flight (purchaseMutex), so the outcome is its.
            BillingClient.BillingResponseCode.USER_CANCELED -> completeActive(PurchaseOutcome.Cancelled)
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> completeActive(PurchaseOutcome.AlreadyOwned)
            else -> completeActive(PurchaseOutcome.Failed(result.responseCode, result.debugMessage))
        }
    }

    private fun completeActive(outcome: PurchaseOutcome) {
        val awaiter = synchronized(pendingLock) {
            val active = activePurchase
            activePurchase = null
            active?.deferred
        }
        awaiter?.complete(outcome)
    }

    /**
     * Observer path for one purchase: claim → upload → acknowledge only on
     * success. On failure the claim is released so a redelivery (next sweep
     * or next launch) retries — the server is idempotent on transaction id.
     */
    private suspend fun handleObservedPurchase(purchase: Purchase) {
        if (client.uploadObservedTransaction(purchase.purchaseToken, receiptString(purchase))) {
            finishWithPlay(purchase)
            onObservedPurchaseApplied?.invoke()
        }
    }

    /**
     * Tell Google the purchase was granted: consume consumables (so they
     * can be repurchased), acknowledge everything else. Never called before
     * the server accepted the receipt.
     */
    private suspend fun finishWithPlay(purchase: Purchase) {
        val catalog = client.configuredProducts.associateBy { it.productId }
        val isConsumable = purchase.products.any { catalog[it]?.isConsumable == true }
        try {
            if (isConsumable) {
                billingClient.consumePurchase(
                    ConsumeParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build(),
                )
            } else if (!purchase.isAcknowledged) {
                billingClient.acknowledgePurchase(
                    AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build(),
                )
            }
        } catch (e: Exception) {
            // Non-fatal: the sweep on next launch acknowledges it (the server
            // already recorded the receipt; re-upload is idempotent).
            SalesLog.warn(SalesLog.Category.OBSERVER, "acknowledge/consume failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // Receipts
    // ------------------------------------------------------------------

    /**
     * Pull every purchase currently owned on this device and return their
     * receipt strings (for [SalesClient.restorePurchases]). The SDK never
     * interprets receipts — these strings go to the server as-is.
     */
    suspend fun queryCurrentReceipts(): List<String> {
        ensureConnected()
        val out = mutableListOf<String>()
        for (type in listOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP)) {
            val result = billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(type).build(),
            )
            if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) continue
            for (purchase in result.purchasesList) {
                if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                    out.add(receiptString(purchase))
                }
            }
        }
        return out
    }

    /**
     * The on-the-wire receipt: a self-describing JSON envelope carrying
     * everything a server needs to validate a Play purchase (the signed
     * `originalJson` + `signature` pair AND the `purchaseToken` for a
     * Play Developer API lookup).
     */
    internal fun receiptString(purchase: Purchase): String = JSONObject().apply {
        put("platform", "google_play")
        put("packageName", purchase.packageName)
        put("productIds", JSONArray(purchase.products))
        put("purchaseToken", purchase.purchaseToken)
        purchase.orderId?.let { put("orderId", it) }
        put("purchaseTime", purchase.purchaseTime)
        put("purchaseState", purchase.purchaseState)
        put("quantity", purchase.quantity)
        put("isAutoRenewing", purchase.isAutoRenewing)
        put("originalJson", purchase.originalJson)
        put("signature", purchase.signature)
    }.toString()
}
