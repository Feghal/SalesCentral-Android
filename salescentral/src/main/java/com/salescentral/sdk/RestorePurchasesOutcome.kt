package com.salescentral.sdk

/**
 * Per-call outcome of [SalesStore.restorePurchasesResult] — the three things
 * a "Restore purchases" button has to tell apart: the restore succeeded and
 * re-linked an existing account, it succeeded but found no account to
 * re-link (by far the most common tap), or the call itself failed. The
 * fire-and-forget [SalesStore.restorePurchases] cannot tell these apart —
 * its only failure signal is the store-wide [SalesStore.lastError], which
 * every other store method writes too.
 */
sealed class RestorePurchasesOutcome {

    /**
     * The restore round-trip succeeded and [SalesStore.user],
     * [SalesStore.products], [SalesStore.retention] and
     * [SalesStore.subscription] were all updated before this was returned.
     *
     * [result] is the server's `restoreUser` response. Its `restored` flag
     * means exactly what the server computes for it
     * (`central_sales_rest/controllers/userController.js`, `exports.restore`:
     * `const restored = !!user;` right after the owner-resolution loop): an
     * EXISTING user was resolved as the owner of at least one submitted
     * receipt — by the purchase's `appAccountToken`, its Transaction or
     * Subscription record, or a linked original transaction id
     * (`utils/purchaseOwner.js`) — so this device has been re-linked to
     * that account. `false` means no owner was found and the server created
     * a fresh user for the receipts. It is NOT "at least one receipt was
     * accepted": the receipts' effects are applied to whichever user the
     * response carries either way, and `result.applied` reports each
     * receipt's own outcome. When the device has no Play purchases at all,
     * [SalesClient.restorePurchases] never calls `restoreUser` — it falls
     * back to `createOrFetchUser` and answers `restored = false` with an
     * empty `applied` list.
     *
     * [subscription] is the follow-up `currentSubscription()` read that was
     * mirrored into [SalesStore.subscription]. `null` here means that read
     * FAILED — [subscriptionRefreshError] carries why — not that the user
     * has no subscription: the restore itself still succeeded, and
     * `result.user.premium` is the server-reconciled premium state the
     * restore response delivered. Treat a `null` subscription with a
     * non-null error as "could not verify", never as "nothing to restore".
     */
    data class Completed(
        val result: RestoreResult,
        val subscription: CurrentSubscriptionResponse?,
        val subscriptionRefreshError: SalesError?,
    ) : RestorePurchasesOutcome()

    /**
     * The restore call failed before anything was mirrored: [SalesStore.user],
     * [SalesStore.products], [SalesStore.retention] and
     * [SalesStore.subscription] are exactly what they were. [error] is the
     * same value written to [SalesStore.lastError] —
     * `SalesError.InvalidState("analytics_only")` when the server has this
     * platform in analytics-only mode, [SalesError.Http] for a rejected
     * `restoreUser`, [SalesError.Network] for a transport failure (or any
     * non-[SalesError] exception, wrapped).
     */
    data class Failed(val error: SalesError) : RestorePurchasesOutcome()
}
