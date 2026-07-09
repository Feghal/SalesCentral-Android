package com.salescentral.sdk

/**
 * Abstraction over hardware device attestation, mirroring the iOS SDK's
 * `AppAttestServicing` (backed there by Apple App Attest).
 *
 * On Android the production implementation is [PlayIntegrityAttestService]
 * (Google Play Integrity), enabled by setting `"playIntegrity": true` in
 * `SalesCentral.json` — the server must have the app's Google Play service
 * account configured (admin → App Detail → Google Play card).
 *
 * When disabled (the default) the SDK sends money-touching calls with the
 * explicit `x-attest-unsupported` signal instead of an assertion, and the
 * server admits the session as a **sandbox identity**: a separate user
 * with play-money credits, excluded from production analytics — exactly
 * how the iOS Simulator behaves.
 */
interface DeviceAttestService {
    val isSupported: Boolean

    /**
     * Wire identifier of the attestation scheme, sent with key
     * registration so the server picks the right verifier. Null means the
     * server default (Apple App Attest).
     */
    val platform: String? get() = null

    /** Generate a hardware-backed key; returns its key id. */
    suspend fun generateKey(): String

    /** Have the platform attest the key; returns the attestation object. */
    suspend fun attestKey(keyId: String, clientDataHash: ByteArray): ByteArray

    /** Sign one request; returns the assertion object. */
    suspend fun generateAssertion(keyId: String, clientDataHash: ByteArray): ByteArray
}

/**
 * Default service: attestation off (see [DeviceAttestService] docs — the
 * session runs as a sandbox identity).
 */
class UnsupportedAttestService : DeviceAttestService {
    override val isSupported: Boolean get() = false
    override suspend fun generateKey(): String = throw SalesError.AttestUnsupported()
    override suspend fun attestKey(keyId: String, clientDataHash: ByteArray): ByteArray =
        throw SalesError.AttestUnsupported()
    override suspend fun generateAssertion(keyId: String, clientDataHash: ByteArray): ByteArray =
        throw SalesError.AttestUnsupported()
}

/**
 * Production attestation backed by Google Play Integrity (classic
 * requests).
 *
 * Play Integrity has no hardware key handle, so the "key" is a locally
 * minted UUID and both the registration attestation and every assertion
 * are fresh integrity tokens whose `nonce` is the base64url-encoded
 * clientDataHash — which binds each token to the exact challenge + request
 * body, the same recipe App Attest uses. The server decodes tokens with
 * Google's Play Integrity API and checks the verdicts + nonce.
 */
class PlayIntegrityAttestService(context: android.content.Context) : DeviceAttestService {

    private val manager: com.google.android.play.core.integrity.IntegrityManager =
        com.google.android.play.core.integrity.IntegrityManagerFactory.create(context.applicationContext)

    override val isSupported: Boolean get() = true
    override val platform: String get() = "play_integrity"

    override suspend fun generateKey(): String = java.util.UUID.randomUUID().toString()

    override suspend fun attestKey(keyId: String, clientDataHash: ByteArray): ByteArray =
        requestToken(clientDataHash)

    override suspend fun generateAssertion(keyId: String, clientDataHash: ByteArray): ByteArray =
        requestToken(clientDataHash)

    private suspend fun requestToken(clientDataHash: ByteArray): ByteArray {
        // Classic-request nonce: base64 URL-safe, no padding/wrap. The server
        // recomputes exactly this from the challenge + body it received.
        val nonce = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(clientDataHash)
        val request = com.google.android.play.core.integrity.IntegrityTokenRequest.builder()
            .setNonce(nonce)
            .build()
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            manager.requestIntegrityToken(request)
                .addOnSuccessListener { resp ->
                    cont.resumeWith(Result.success(resp.token().toByteArray(Charsets.UTF_8)))
                }
                .addOnFailureListener { err ->
                    cont.resumeWith(
                        Result.failure(SalesError.Network("play_integrity: ${err.message ?: err.javaClass.simpleName}")),
                    )
                }
        }
    }
}

internal object Base64Url {
    // java.util.Base64 (minSdk 26) rather than android.util.Base64 so the
    // pure-JVM unit tests exercise the real implementation.

    /** The server issues challenges base64url-encoded (header-safe). */
    fun decode(s: String): ByteArray? = try {
        var b64 = s.replace('-', '+').replace('_', '/')
        while (b64.length % 4 != 0) b64 += "="
        java.util.Base64.getDecoder().decode(b64)
    } catch (_: Exception) {
        null
    }

    fun encodeStandard(bytes: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(bytes)
}
