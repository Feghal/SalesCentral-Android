package com.salescentral.sdk

import android.content.Context
import org.json.JSONObject

/**
 * Configuration for a single registered app.
 *
 * Every operation in this SDK lives behind its own unguessable URL. The
 * admin generates a unique 12-char hex token per (app, operation) when the
 * app is registered. The SDK composes the URLs from `baseURL + "/" + token`
 * at call time.
 *
 * You don't have to type these by hand: open your app's detail page in the
 * admin and copy the prebuilt JSON snippet from the "SDK config" card into
 * `src/main/assets/SalesCentral.json`.
 */
class SalesConfig(
    /**
     * The public origin where the SalesCentral service is reachable,
     * e.g. `https://sales-central.org`. No trailing slash.
     */
    val baseUrl: String,

    /**
     * API key for this app — sent as the `x-app-key` header on every
     * request. Treat it as a secret.
     */
    val apiKey: String,

    /** Per-operation tokens. Each is a 12-char hex string. */
    val tokens: Tokens,

    /**
     * Optional override for the user-token storage backend. When null,
     * [SalesCentral.configure] injects a [SharedPrefsTokenStore]. Building
     * a [SalesClient] directly requires a non-null store.
     */
    val tokenStore: TokenStore? = null,

    /**
     * Enable Google Play Integrity device attestation
     * ([PlayIntegrityAttestService]). Requires the app's Google Play
     * service account to be configured on the server (admin → App Detail →
     * Google Play); with it, sessions are production identities. Off by
     * default — unattested sessions run as sandbox identities.
     */
    val playIntegrity: Boolean = false,
) {

    /** Copy with a resolved token store (used by [SalesCentral.configure]). */
    internal fun withTokenStore(store: TokenStore): SalesConfig =
        SalesConfig(baseUrl, apiKey, tokens, store, playIntegrity)

    /** Map an operation to its full URL. */
    fun urlFor(endpoint: Endpoint): String {
        val token = when (endpoint) {
            Endpoint.CREATE_OR_FETCH_USER -> tokens.createOrFetchUser
            Endpoint.RESTORE_USER -> tokens.restoreUser
            Endpoint.APPLY_PURCHASES -> tokens.applyPurchases
            Endpoint.CURRENT_SUBSCRIPTION -> tokens.currentSubscription
            Endpoint.SPEND_CREDITS -> tokens.spendCredits
            Endpoint.CLAIM_REWARD -> tokens.claimReward ?: "" // guarded in SalesClient.claimReward()
            Endpoint.RECORD_SESSION -> tokens.recordSession
            Endpoint.RECORD_EVENT -> tokens.recordEvent
            Endpoint.ATTEST_CHALLENGE -> tokens.attestChallenge
            Endpoint.ATTEST_KEY -> tokens.attestKey
        }
        return "${baseUrl.trimEnd('/')}/$token"
    }

    /**
     * Operations the SDK can talk to. Mirrors `EndpointRoute.ENDPOINT_TYPES`
     * on the server, minus the Apple webhook (which doesn't involve the SDK).
     */
    enum class Endpoint {
        CREATE_OR_FETCH_USER,
        RESTORE_USER,
        APPLY_PURCHASES,
        CURRENT_SUBSCRIPTION,
        SPEND_CREDITS,
        CLAIM_REWARD,
        RECORD_SESSION,
        RECORD_EVENT,
        ATTEST_CHALLENGE,
        ATTEST_KEY,
    }

    /**
     * The per-operation tokens for one app. The admin's "SDK config" card
     * outputs a JSON literal with these prefilled.
     */
    data class Tokens(
        val createOrFetchUser: String,
        val restoreUser: String,
        val applyPurchases: String,
        val currentSubscription: String,
        val spendCredits: String,
        val recordSession: String,
        val recordEvent: String,
        val attestChallenge: String,
        val attestKey: String,
        /**
         * Optional — apps configured before retention rewards existed don't
         * have this token. Regenerate the config from the admin's SDK
         * config card to pick it up.
         */
        val claimReward: String? = null,
    )

    companion object {

        /** Asset file name the SDK loads configuration from. */
        const val ASSET_FILE = "SalesCentral.json"

        /**
         * Load configuration from `assets/SalesCentral.json` in the app.
         * The file is a JSON object mirroring the iOS `SalesCentral.plist`:
         *
         * ```json
         * {
         *   "baseURL": "https://sales-central.org",
         *   "apiKey": "csk_XXXXXXXXXXXXXXXXXXXXXXXXXXXX",
         *   "tokens": {
         *     "createOrFetchUser": "YYYYYYYYYYYY",
         *     "restoreUser": "YYYYYYYYYYYY",
         *     "applyPurchases": "YYYYYYYYYYYY",
         *     "currentSubscription": "YYYYYYYYYYYY",
         *     "spendCredits": "YYYYYYYYYYYY",
         *     "claimReward": "YYYYYYYYYYYY",
         *     "recordSession": "YYYYYYYYYYYY",
         *     "recordEvent": "YYYYYYYYYYYY",
         *     "attestChallenge": "YYYYYYYYYYYY",
         *     "attestKey": "YYYYYYYYYYYY"
         *   }
         * }
         * ```
         *
         * Generate the snippet from the admin's App Detail → SDK config card.
         */
        fun fromAssets(context: Context): SalesConfig {
            val text = try {
                context.assets.open(ASSET_FILE).use { it.readBytes().decodeToString() }
            } catch (e: Exception) {
                throw IllegalStateException(
                    """
                    SalesCentral SDK could not find its configuration. Add a
                    '$ASSET_FILE' file to your app's src/main/assets/ folder.
                    Generate the JSON from the admin's App Detail → SDK config card.
                    """.trimIndent(),
                    e,
                )
            }
            return parse(text, source = ASSET_FILE)
        }

        /** Parse a config JSON document. Throws [IllegalStateException] on missing keys. */
        fun parse(jsonText: String, source: String = "config"): SalesConfig {
            val raw = try {
                JSONObject(jsonText)
            } catch (e: Exception) {
                throw IllegalStateException("$source: not valid JSON — ${e.message}", e)
            }
            val baseUrl = raw.optString("baseURL", "")
            check(baseUrl.isNotEmpty() && (baseUrl.startsWith("https://") || baseUrl.startsWith("http://"))) {
                "$source: 'baseURL' is missing or not a valid URL."
            }
            val apiKey = raw.optString("apiKey", "")
            check(apiKey.isNotEmpty()) { "$source: 'apiKey' is missing." }
            val t = raw.optJSONObject("tokens")
                ?: throw IllegalStateException("$source: 'tokens' must be an object of string → string.")

            fun need(key: String): String {
                val v = t.optString(key, "")
                check(v.isNotEmpty()) { "$source: 'tokens.$key' is missing." }
                return v
            }

            return SalesConfig(
                baseUrl = baseUrl,
                apiKey = apiKey,
                tokens = Tokens(
                    createOrFetchUser = need("createOrFetchUser"),
                    restoreUser = need("restoreUser"),
                    applyPurchases = need("applyPurchases"),
                    currentSubscription = need("currentSubscription"),
                    spendCredits = need("spendCredits"),
                    recordSession = need("recordSession"),
                    recordEvent = need("recordEvent"),
                    attestChallenge = need("attestChallenge"),
                    attestKey = need("attestKey"),
                    // Optional — older configs predate retention rewards. Calling
                    // claimReward() without it throws a descriptive InvalidState.
                    claimReward = t.optString("claimReward", "").ifEmpty { null },
                ),
                playIntegrity = raw.optBoolean("playIntegrity", false),
            )
        }
    }
}

/** SDK constants. Updated when the package is released. */
object SDKMetadata {
    const val version = "1.4.1"
}
