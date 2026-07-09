package com.salescentral.sdk

import android.content.Context
import android.content.SharedPreferences

/**
 * Pluggable storage for the user JWT issued by the service.
 *
 * The SDK defaults to [SharedPrefsTokenStore] (app-private
 * `SharedPreferences`). Unlike the iOS Keychain, Android preferences do NOT
 * survive uninstall — for paying users the authoritative recovery path is
 * `restorePurchases()`, which re-links the user by their Google Play
 * purchases (the same way StoreKit entitlements are the authoritative path
 * on iOS). Apps that want test-only storage can implement this interface
 * and pass it in via [SalesConfig].
 */
interface TokenStore {
    fun read(): String?
    fun write(token: String)
    fun clear()

    /**
     * Read/persist the SDK's stable client id (a UUID) used for idempotent
     * user creation. Default implementations are no-ops so custom
     * [TokenStore]s keep compiling — they just don't get cross-launch
     * idempotency until they implement these. The built-in stores do.
     */
    fun readClientId(): String? = null
    fun writeClientId(id: String) {}

    /**
     * Wipe the stored client id so the next create starts a brand-new guest
     * user (a genuine identity reset). Default is a no-op.
     */
    fun clearClientId() {}

    /**
     * Read/persist the device-attestation key id registered with the server.
     * Dormant until the backend supports an Android attestation scheme
     * (Play Integrity); mirrors the iOS App Attest keyId slot.
     */
    fun readAttestKeyId(): String? = null
    fun writeAttestKeyId(id: String) {}
    fun clearAttestKeyId() {}
}

/**
 * Default token store — app-private [SharedPreferences], namespaced to the
 * package name so multiple apps using the SDK on one device don't collide.
 */
class SharedPrefsTokenStore(
    context: Context,
    name: String? = null,
) : TokenStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(
            name ?: "${context.packageName}.centralSales",
            Context.MODE_PRIVATE,
        )

    private companion object {
        const val KEY_TOKEN = "user_token"
        const val KEY_CLIENT_ID = "client_id"
        const val KEY_ATTEST_KEY_ID = "attest_key_id"
    }

    override fun read(): String? = prefs.getString(KEY_TOKEN, null)
    override fun write(token: String) { prefs.edit().putString(KEY_TOKEN, token).apply() }
    override fun clear() { prefs.edit().remove(KEY_TOKEN).apply() }

    override fun readClientId(): String? = prefs.getString(KEY_CLIENT_ID, null)
    override fun writeClientId(id: String) { prefs.edit().putString(KEY_CLIENT_ID, id).apply() }
    override fun clearClientId() { prefs.edit().remove(KEY_CLIENT_ID).apply() }

    override fun readAttestKeyId(): String? = prefs.getString(KEY_ATTEST_KEY_ID, null)
    override fun writeAttestKeyId(id: String) { prefs.edit().putString(KEY_ATTEST_KEY_ID, id).apply() }
    override fun clearAttestKeyId() { prefs.edit().remove(KEY_ATTEST_KEY_ID).apply() }
}

/** In-memory store — useful for unit tests. */
class InMemoryTokenStore(initial: String? = null) : TokenStore {
    private val lock = Any()
    private var token: String? = initial
    private var clientId: String? = null
    private var attestKeyId: String? = null

    override fun read(): String? = synchronized(lock) { token }
    override fun write(token: String) = synchronized(lock) { this.token = token }
    override fun clear() = synchronized(lock) { token = null }

    override fun readClientId(): String? = synchronized(lock) { clientId }
    override fun writeClientId(id: String) = synchronized(lock) { clientId = id }
    override fun clearClientId() = synchronized(lock) { clientId = null }

    override fun readAttestKeyId(): String? = synchronized(lock) { attestKeyId }
    override fun writeAttestKeyId(id: String) = synchronized(lock) { attestKeyId = id }
    override fun clearAttestKeyId() = synchronized(lock) { attestKeyId = null }
}
