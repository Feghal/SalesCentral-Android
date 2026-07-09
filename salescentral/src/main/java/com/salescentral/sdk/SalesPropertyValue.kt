package com.salescentral.sdk

/**
 * A single user property value.
 *
 * Properties are caller-defined attributes (name, email, plan_intent, …)
 * the SDK attaches to the current user via [SalesClient.setUserProperty]
 * or [SalesClient.setUserProperties]. The admin renders + searches across
 * these in the Users list.
 *
 * The value space is intentionally narrow — string / number / bool — so
 * the admin can display them in a table and the backend's wildcard text
 * index stays cheap. Pass Kotlin `null` (not a [SalesPropertyValue] case)
 * to delete a key from the user's bag.
 */
sealed class SalesPropertyValue {
    data class StringValue(val value: String) : SalesPropertyValue()
    data class NumberValue(val value: Double) : SalesPropertyValue()
    data class BoolValue(val value: Boolean) : SalesPropertyValue()

    /**
     * On-the-wire value. Integer-valued doubles round-trip as integers so
     * the backend stores 42 rather than 42.0 (cosmetic, but the admin
     * displays raw numbers and the difference is visible).
     */
    internal fun toJsonValue(): Any = when (this) {
        is StringValue -> value
        is NumberValue ->
            if (value % 1.0 == 0.0 && value >= Long.MIN_VALUE.toDouble() && value <= Long.MAX_VALUE.toDouble()) {
                value.toLong()
            } else {
                value
            }
        is BoolValue -> value
    }

    companion object {
        /** Convenience factories mirroring the Swift literal conformances. */
        fun of(value: String): SalesPropertyValue = StringValue(value)
        fun of(value: Int): SalesPropertyValue = NumberValue(value.toDouble())
        fun of(value: Long): SalesPropertyValue = NumberValue(value.toDouble())
        fun of(value: Double): SalesPropertyValue = NumberValue(value)
        fun of(value: Boolean): SalesPropertyValue = BoolValue(value)

        /** Decode a server-sent property value. Null for unsupported shapes. */
        internal fun from(raw: Any?): SalesPropertyValue? = when (raw) {
            is String -> StringValue(raw)
            is Boolean -> BoolValue(raw)
            is Number -> NumberValue(raw.toDouble())
            else -> null
        }
    }
}
