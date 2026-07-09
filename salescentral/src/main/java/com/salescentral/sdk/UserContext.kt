package com.salescentral.sdk

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import org.json.JSONObject
import java.time.Instant
import java.util.Currency
import java.util.Locale
import java.util.TimeZone

/**
 * Context payload sent up with `ensureUser` / `restorePurchases` /
 * `updateContext`. Every field is optional — the SDK collects what it can
 * from the device and the caller fills in the rest (notably [marketing]
 * and [consent], which involve user prompts the SDK can't make on the
 * caller's behalf).
 */
data class UserContext(
    var device: DeviceContext? = null,
    var app: AppContext? = null,
    var locale: LocaleContext? = null,
    var network: NetworkContext? = null,
    var marketing: MarketingContext? = null,
    var consent: ConsentContext? = null,
    var push: PushContext? = null,
    var metadata: Map<String, Any?>? = null,
) {
    /**
     * SDK-managed stable client id (UUID), injected by [SalesClient] on
     * createOrFetch so a tokenless retry resolves to the same server user.
     * Callers don't set this — it is filled in internally.
     */
    internal var clientId: String? = null

    /**
     * Merge another context onto this one, preferring the other side's
     * values when present. Useful after a consent prompt completes:
     *
     * ```kotlin
     * val ctx = UserContext.current(context)
     * ctx.merge(UserContext(marketing = MarketingContext(attStatus = "authorized")))
     * ```
     */
    fun merge(other: UserContext) {
        other.device?.let { device = it }
        other.app?.let { app = it }
        other.locale?.let { locale = it }
        other.network?.let { network = it }
        other.marketing?.let { marketing = it }
        other.consent?.let { consent = it }
        other.push?.let { push = it }
        other.metadata?.let { metadata = it }
    }

    internal fun toJson(): JSONObject = JSONObject().apply {
        device?.let { put("device", it.toJson()) }
        app?.let { put("app", it.toJson()) }
        locale?.let { put("locale", it.toJson()) }
        network?.let { put("network", it.toJson()) }
        marketing?.let { put("marketing", it.toJson()) }
        consent?.let { put("consent", it.toJson()) }
        push?.let { put("push", it.toJson()) }
        metadata?.let { put("metadata", JsonUtil.toJsonValue(it)) }
        clientId?.let { put("clientId", it) }
    }

    companion object {
        /**
         * Build a context populated from the current device + locale. Caller
         * can then [merge] marketing / consent overrides.
         */
        fun current(context: Context): UserContext = UserContext(
            device = DeviceContext.current(context),
            app = AppContext.current(context),
            locale = LocaleContext.current(),
        )
    }
}

/**
 * Push notification context — FCM registration token + current
 * notification permission status. Sent up via
 * [SalesCentral.registerPushToken] or attached to any [UserContext] you
 * push through [SalesClient.updateContext].
 */
data class PushContext(
    /** FCM registration token. */
    var token: String? = null,
    /** Always "production" for FCM (Google Play has no sandbox push host). */
    var environment: String? = null,
    /** "authorized" / "denied" — from NotificationManager.areNotificationsEnabled(). */
    var authStatus: String? = null,
    var appVersion: String? = null,
    var bundleId: String? = null,
    /**
     * Push transport — always "fcm" on Android. Tells the server to store
     * the token verbatim (FCM tokens are case-sensitive) and route sends
     * through Firebase instead of APNs.
     */
    var platform: String? = "fcm",
) {
    internal fun toJson(): JSONObject = JSONObject().apply {
        token?.let { put("token", it) }
        environment?.let { put("environment", it) }
        authStatus?.let { put("authStatus", it) }
        appVersion?.let { put("appVersion", it) }
        bundleId?.let { put("bundleId", it) }
        platform?.let { put("platform", it) }
    }
}

data class DeviceContext(
    var model: String? = null,
    var family: String? = null,
    var osName: String? = null,
    var osVersion: String? = null,
    var screenWidth: Int? = null,
    var screenHeight: Int? = null,
    var screenScale: Double? = null,
    var totalMemoryMB: Int? = null,
    var isLowPowerMode: Boolean? = null,
    var isSimulator: Boolean? = null,
) {
    internal fun toJson(): JSONObject = JSONObject().apply {
        model?.let { put("model", it) }
        family?.let { put("family", it) }
        osName?.let { put("osName", it) }
        osVersion?.let { put("osVersion", it) }
        screenWidth?.let { put("screenWidth", it) }
        screenHeight?.let { put("screenHeight", it) }
        screenScale?.let { put("screenScale", it) }
        totalMemoryMB?.let { put("totalMemoryMB", it) }
        isLowPowerMode?.let { put("isLowPowerMode", it) }
        isSimulator?.let { put("isSimulator", it) }
    }

    companion object {
        fun current(context: Context): DeviceContext {
            val ctx = DeviceContext(
                model = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                family = "Android",
                osName = "Android",
                osVersion = Build.VERSION.RELEASE,
                isSimulator = isEmulator(),
            )
            try {
                val metrics = context.resources.displayMetrics
                ctx.screenWidth = metrics.widthPixels
                ctx.screenHeight = metrics.heightPixels
                ctx.screenScale = metrics.density.toDouble()
            } catch (_: Exception) {
            }
            try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val info = ActivityManager.MemoryInfo()
                am?.getMemoryInfo(info)
                if (info.totalMem > 0) ctx.totalMemoryMB = (info.totalMem / 1024 / 1024).toInt()
            } catch (_: Exception) {
            }
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                ctx.isLowPowerMode = pm?.isPowerSaveMode
            } catch (_: Exception) {
            }
            return ctx
        }

        /** Best-effort emulator detection (the Android analog of `isSimulator`). */
        internal fun isEmulator(): Boolean {
            val fp = Build.FINGERPRINT.lowercase()
            val model = Build.MODEL.lowercase()
            val product = Build.PRODUCT.lowercase()
            return fp.startsWith("generic") || fp.startsWith("unknown") ||
                fp.contains("emulator") || fp.contains("test-keys") ||
                model.contains("google_sdk") || model.contains("sdk_gphone") ||
                model.contains("emulator") || model.contains("android sdk built for") ||
                product.contains("sdk_gphone") || Build.HARDWARE.lowercase().let {
                it.contains("goldfish") || it.contains("ranchu")
            }
        }
    }
}

data class AppContext(
    var version: String? = null,
    var build: String? = null,
    var sdkVersion: String? = null,
    var firstLaunchAt: Instant? = null,
    var storefront: String? = null,
) {
    internal fun toJson(): JSONObject = JSONObject().apply {
        version?.let { put("version", it) }
        build?.let { put("build", it) }
        sdkVersion?.let { put("sdkVersion", it) }
        firstLaunchAt?.let { put("firstLaunchAt", JsonUtil.formatDate(it)) }
        storefront?.let { put("storefront", it) }
    }

    companion object {
        fun current(context: Context, sdkVersion: String = SDKMetadata.version): AppContext {
            var version: String? = null
            var build: String? = null
            try {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                version = info.versionName
                @Suppress("DEPRECATION")
                build = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.longVersionCode.toString()
                } else {
                    info.versionCode.toString()
                }
            } catch (_: Exception) {
            }
            return AppContext(version = version, build = build, sdkVersion = sdkVersion)
        }
    }
}

data class LocaleContext(
    var locale: String? = null,
    var language: String? = null,
    var region: String? = null,
    var timezone: String? = null,
    var currency: String? = null,
) {
    internal fun toJson(): JSONObject = JSONObject().apply {
        locale?.let { put("locale", it) }
        language?.let { put("language", it) }
        region?.let { put("region", it) }
        timezone?.let { put("timezone", it) }
        currency?.let { put("currency", it) }
    }

    companion object {
        fun current(): LocaleContext {
            val l = Locale.getDefault()
            return LocaleContext(
                locale = l.toLanguageTag(),
                language = l.language.ifEmpty { null },
                region = l.country.ifEmpty { null },
                timezone = TimeZone.getDefault().id,
                currency = try {
                    Currency.getInstance(l).currencyCode
                } catch (_: Exception) {
                    null
                },
            )
        }
    }
}

data class NetworkContext(
    var type: String? = null,
    var carrier: String? = null,
) {
    internal fun toJson(): JSONObject = JSONObject().apply {
        type?.let { put("type", it) }
        carrier?.let { put("carrier", it) }
    }
}

/**
 * Marketing / attribution context. Field names match the server's wire
 * format (shared with iOS) — on Android, put your GAID (Google Advertising
 * ID) in [idfa] and your app-set / firebase installation id in [idfv].
 */
data class MarketingContext(
    var idfa: String? = null,
    var idfv: String? = null,
    var attStatus: String? = null,
    var attributionSource: String? = null,
    var campaign: String? = null,
    var utmSource: String? = null,
    var utmMedium: String? = null,
    var utmCampaign: String? = null,
    var utmTerm: String? = null,
    var utmContent: String? = null,
    var referrer: String? = null,
) {
    internal fun toJson(): JSONObject = JSONObject().apply {
        idfa?.let { put("idfa", it) }
        idfv?.let { put("idfv", it) }
        attStatus?.let { put("attStatus", it) }
        attributionSource?.let { put("attributionSource", it) }
        campaign?.let { put("campaign", it) }
        utmSource?.let { put("utmSource", it) }
        utmMedium?.let { put("utmMedium", it) }
        utmCampaign?.let { put("utmCampaign", it) }
        utmTerm?.let { put("utmTerm", it) }
        utmContent?.let { put("utmContent", it) }
        referrer?.let { put("referrer", it) }
    }
}

data class ConsentContext(
    var analytics: Boolean? = null,
    var marketing: Boolean? = null,
    var timestamp: Instant? = null,
) {
    init {
        if (timestamp == null && (analytics != null || marketing != null)) {
            timestamp = Instant.now()
        }
    }

    internal fun toJson(): JSONObject = JSONObject().apply {
        analytics?.let { put("analytics", it) }
        marketing?.let { put("marketing", it) }
        timestamp?.let { put("timestamp", JsonUtil.formatDate(it)) }
    }
}
