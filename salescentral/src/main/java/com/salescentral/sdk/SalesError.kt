package com.salescentral.sdk

/**
 * Errors surfaced by [SalesClient].
 *
 * The [Http] case carries the server's machine-readable `error` code so
 * callers can branch on specific failure modes (e.g. `insufficient_credits`
 * for a paywall). The human-readable `message` is best-effort.
 */
sealed class SalesError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** The request reached the server but returned a non-2xx response. */
    class Http(val status: Int, val errorCode: String, val serverMessage: String?) :
        SalesError("HTTP $status $errorCode" + (serverMessage?.let { ": $it" } ?: ""))

    /** The response couldn't be decoded into the expected shape. */
    class Decoding(detail: String) : SalesError("Decoding error: $detail")

    /** The transport itself failed (DNS, TLS, offline, etc.). */
    class Network(detail: String) : SalesError("Network error: $detail")

    /** A precondition wasn't met locally (e.g. no token, invalid input). */
    class InvalidState(detail: String) : SalesError("Invalid state: $detail")

    /**
     * Hardware attestation is unavailable on this device. The client doesn't
     * throw this from its request path — unattested platforms run as SANDBOX
     * identities instead (mirroring the iOS Simulator). Retained for API
     * stability and for direct misuse of the attest plumbing.
     */
    class AttestUnsupported : SalesError("Device attestation unsupported on this device")

    /**
     * Google Play completed the purchase of [productId] but uploading its
     * receipt to the backend failed ([cause]: the [Network] / [Http] /
     * [Decoding] the upload threw, with a non-`SalesError` wrapped as
     * [Network]). Thrown by [SalesCentral.purchase] in place of the raw
     * failure because the money state is specific and unlike any other
     * purchase error: the user IS charged, the server has NOT recorded the
     * purchase, and the SDK left it unacknowledged with its upload claim
     * released so the purchase observer re-uploads it — on the next process
     * launch (`SalesCentral.start` → `PlayBillingConnector.startObserving` →
     * `sweepUnacknowledged`, which runs once per process after bootstrap;
     * returning to the foreground does not re-sweep), earlier only if Play
     * redelivers the purchase to `PurchasesUpdatedListener` in the same
     * session or the app re-buys the same product (`ITEM_ALREADY_OWNED`
     * re-applies it). The server is idempotent on transaction id, so the
     * retry cannot double-apply, and Play auto-refunds a purchase that stays
     * unacknowledged for ≈3 days, so nothing is silently kept. [code] and
     * [isClientError] read through to [cause].
     *
     * One precision: `finishPurchase` is also reached through
     * `applyOwnedPurchase` (an `ITEM_ALREADY_OWNED` re-buy), where the
     * purchase may already be acknowledged and uploaded. On that path nothing
     * new was charged, and an already-acknowledged purchase is not picked up
     * by `sweepUnacknowledged` — the entitlement is what bootstrap / restore
     * reflect instead. The error class is the same; only the "you are charged
     * and it will be re-uploaded" reading is specific to a fresh purchase.
     */
    class ReceiptUpload(val productId: String, override val cause: SalesError) :
        SalesError("Receipt upload failed for $productId: ${cause.message}", cause)

    /**
     * The server's `error` code if this is an HTTP error, otherwise null.
     * Handy for `when (err.code) { … }` paywall / restore flows. A
     * [ReceiptUpload] answers with its [ReceiptUpload.cause]'s code.
     */
    val code: String?
        get() = when (this) {
            is Http -> errorCode
            is ReceiptUpload -> cause.code
            else -> null
        }

    /** True for HTTP 4xx (through a [ReceiptUpload]'s cause too), false otherwise. */
    val isClientError: Boolean
        get() = when (this) {
            is Http -> status in 400..499
            is ReceiptUpload -> cause.isClientError
            else -> false
        }
}
