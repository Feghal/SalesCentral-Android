package com.salescentral.sdk

import android.util.Log

/**
 * Lightweight logging for the SalesCentral SDK.
 *
 * Lines route through logcat under the tag `SalesCentral`, prefixed with a
 * category (`sdk`, `http`, `store`, `push`, `paywall`, …). Filter logcat on
 * `SalesCentral` to see the entire SDK conversation — app launch through
 * purchase round-trip.
 *
 * [SalesCentral.start] enables logging automatically for debuggable builds
 * (the analog of the Swift SDK's DEBUG default). Override anywhere:
 *
 * ```kotlin
 * SalesCentral.loggingEnabled = true   // also visible in release
 * SalesCentral.loggingEnabled = false  // silence everything
 * ```
 */
object SalesLog {

    @Volatile
    var isEnabled: Boolean = false

    private const val TAG = "SalesCentral"

    enum class Category(val label: String) {
        SDK("sdk"),           // boot / configure / reset
        HTTP("http"),         // HTTP requests + responses
        STORE("store"),       // Play Billing product loads + purchases
        PUSH("push"),         // FCM token registration
        PAYWALL("paywall"),   // paywall lookup + filtering
        SESSION("session"),   // foreground session tracker
        OBSERVER("observer"), // background purchase observer
    }

    fun debug(cat: Category, message: String) {
        if (!isEnabled) return
        Log.d(TAG, "[${cat.label}] $message")
    }

    fun info(cat: Category, message: String) {
        if (!isEnabled) return
        Log.i(TAG, "[${cat.label}] $message")
    }

    fun warn(cat: Category, message: String) {
        if (!isEnabled) return
        Log.w(TAG, "[${cat.label}] $message")
    }

    fun error(cat: Category, message: String) {
        if (!isEnabled) return
        Log.e(TAG, "[${cat.label}] $message")
    }
}
