package com.salescentral.sdk

/** Outcome of [SalesCentral.purchase]. */
sealed class PurchaseResult {

    /**
     * Receipt accepted by your backend; effects (premium, credits,
     * entitlements, feature unlocks) have been applied to the user.
     * [applied] is the per-receipt summary if you want to inspect what
     * changed.
     */
    data class Success(val applied: List<AppliedReceipt>) : PurchaseResult()

    /** User dismissed the Google Play purchase dialog. Not an error. */
    object UserCancelled : PurchaseResult()

    /**
     * The purchase is pending (e.g. Google Play's "pending transactions" —
     * cash payment at a store, parental approval). The purchase observer
     * started by [SalesCentral.start] will upload the receipt automatically
     * once Google resolves it; you can show a "pending approval" hint in
     * the meantime.
     */
    object Pending : PurchaseResult()

    /**
     * Google Play returned a purchase in an unspecified/unverifiable state.
     * Rare; signals tampering or a corrupted Play response — **do not
     * unlock the purchase**.
     */
    data class Unverified(val reason: String) : PurchaseResult()

    /**
     * Google Play returned a purchase (sometimes with no purchase sheet — an
     * already-owned product, an already-active subscription, or a re-test)
     * but it did NOT grant a current entitlement: the backend rejected it
     * (e.g. `expired_transaction`, `revoked_transaction`,
     * `product_not_registered`). The user was NOT charged for anything new —
     * **do not unlock**; show your paywall / "subscription expired" state.
     * [reason] is the backend's machine code.
     */
    data class NotEntitled(val reason: String) : PurchaseResult()

    /** Convenience: was the purchase fully completed (effects applied)? */
    val didSucceed: Boolean get() = this is Success
}
