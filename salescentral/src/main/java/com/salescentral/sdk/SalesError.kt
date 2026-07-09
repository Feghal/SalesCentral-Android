package com.salescentral.sdk

/**
 * Errors surfaced by [SalesClient].
 *
 * The [Http] case carries the server's machine-readable `error` code so
 * callers can branch on specific failure modes (e.g. `insufficient_credits`
 * for a paywall). The human-readable `message` is best-effort.
 */
sealed class SalesError(message: String) : Exception(message) {

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
     * The server's `error` code if this is an HTTP error, otherwise null.
     * Handy for `when (err.code) { … }` paywall / restore flows.
     */
    val code: String?
        get() = (this as? Http)?.errorCode

    /** True for HTTP 4xx, false otherwise. */
    val isClientError: Boolean
        get() = (this as? Http)?.status in 400..499
}
