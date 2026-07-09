package com.salescentral.sdk

import org.json.JSONObject

/**
 * A single effect a product applies on purchase, as configured in the admin.
 * Mirrors the server's `Product.effects` entries. [Unknown] keeps the SDK
 * forward-compatible with effect types added on the server later.
 */
sealed class ProductEffect {
    data class SetPremium(
        val tier: String?,
        val durationDays: Int?,
        val trialDurationDays: Int?,
    ) : ProductEffect()

    data class GrantCredits(
        val amount: Int,
        val trialAmount: Int?,
        val unlockAmount: Int?,
        val unlockPeriod: String?,
    ) : ProductEffect()

    data class GrantEntitlement(
        val entitlement: String,
        val durationDays: Int?,
        val trialDurationDays: Int?,
    ) : ProductEffect()

    data class UnlockFeature(val feature: String) : ProductEffect()

    data class Unknown(val type: String) : ProductEffect()

    companion object {
        internal fun fromJson(o: JSONObject): ProductEffect =
            when (val type = o.optString("type", "")) {
                "set_premium" -> SetPremium(
                    tier = JsonUtil.optString(o, "tier"),
                    durationDays = JsonUtil.optInt(o, "durationDays"),
                    trialDurationDays = JsonUtil.optInt(o, "trialDurationDays"),
                )
                "grant_credits" -> GrantCredits(
                    amount = JsonUtil.optInt(o, "amount") ?: 0,
                    trialAmount = JsonUtil.optInt(o, "trialAmount"),
                    unlockAmount = JsonUtil.optInt(o, "unlockAmount"),
                    unlockPeriod = JsonUtil.optString(o, "unlockPeriod"),
                )
                "grant_entitlement" -> GrantEntitlement(
                    entitlement = JsonUtil.optString(o, "entitlement") ?: "",
                    durationDays = JsonUtil.optInt(o, "durationDays"),
                    trialDurationDays = JsonUtil.optInt(o, "trialDurationDays"),
                )
                "unlock_feature" -> UnlockFeature(feature = JsonUtil.optString(o, "feature") ?: "")
                else -> Unknown(type = type)
            }
    }
}

/**
 * A product in the app's catalog, including the effects it grants. Display
 * title/price still come from Google Play (fetched by [productId]); this
 * carries what the product DOES so paywalls can render benefits pre-purchase.
 */
data class SalesProduct(
    val productId: String,
    val type: String,
    val displayName: String = "",
    val description: String = "",
    val subscriptionPeriod: String? = null,
    val effects: List<ProductEffect> = emptyList(),
) {
    /** True when the admin registered this SKU as an auto-renewing subscription. */
    val isSubscription: Boolean get() = type == "subscription"

    /** True when the admin registered this SKU as a consumable (repurchasable). */
    val isConsumable: Boolean get() = type == "consumable"

    companion object {
        internal fun fromJson(o: JSONObject): SalesProduct = SalesProduct(
            productId = o.optString("productId", ""),
            type = o.optString("type", ""),
            displayName = o.optString("displayName", ""),
            description = o.optString("description", ""),
            subscriptionPeriod = JsonUtil.optString(o, "subscriptionPeriod"),
            effects = o.optJSONArray("effects")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { ProductEffect.fromJson(it) }
                }
            } ?: emptyList(),
        )
    }
}
