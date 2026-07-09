package com.salescentral.sdk

import org.json.JSONArray
import org.json.JSONObject

/**
 * A decoded JSON value of unknown shape — string / number / bool /
 * array / dictionary / null. Used to expose arbitrary content in
 * [SalesPaywall.data] and [SalesClient.remoteConfig].
 *
 * The SDK doesn't try to be smart about schemas — the caller types
 * each lookup themselves via the [stringValue] / [intValue] / [doubleValue]
 * / [boolValue] / [arrayValue] / [dictionaryValue] accessors below. Missing
 * keys / type mismatches return null, which is the cue to fall back to a
 * default.
 */
sealed class SalesAnyValue {
    data class StringValue(val value: String) : SalesAnyValue()
    data class IntValue(val value: Int) : SalesAnyValue()
    data class DoubleValue(val value: Double) : SalesAnyValue()
    data class BoolValue(val value: Boolean) : SalesAnyValue()
    data class ArrayValue(val value: List<SalesAnyValue>) : SalesAnyValue()
    data class DictionaryValue(val value: Map<String, SalesAnyValue>) : SalesAnyValue()
    object Null : SalesAnyValue()

    val stringValue: String? get() = (this as? StringValue)?.value
    val intValue: Int?
        get() = when (this) {
            is IntValue -> value
            is DoubleValue -> value.toInt()
            else -> null
        }
    val doubleValue: Double?
        get() = when (this) {
            is IntValue -> value.toDouble()
            is DoubleValue -> value
            else -> null
        }
    val boolValue: Boolean? get() = (this as? BoolValue)?.value
    val arrayValue: List<SalesAnyValue>? get() = (this as? ArrayValue)?.value
    val dictionaryValue: Map<String, SalesAnyValue>? get() = (this as? DictionaryValue)?.value
    val isNull: Boolean get() = this is Null

    /**
     * Coerce to the same concrete type as `fallback`. Used by
     * [SalesClient.remoteConfig]. Supports String, Int, Long, Double,
     * Float, Boolean. Anything else returns the fallback.
     *
     * Long/Float matter beyond convenience: Kotlin type inference can
     * silently pick them for numeric literals (e.g. when the surrounding
     * expression expects a Long), and without these branches the lookup
     * would quietly return the fallback.
     */
    @Suppress("UNCHECKED_CAST")
    internal fun <T> coerced(fallback: T): T = when (fallback) {
        is String -> (stringValue as? T) ?: fallback
        is Int -> (intValue as? T) ?: fallback
        is Long -> (intValue?.toLong() as? T) ?: fallback
        is Double -> (doubleValue as? T) ?: fallback
        is Float -> (doubleValue?.toFloat() as? T) ?: fallback
        is Boolean -> (boolValue as? T) ?: fallback
        else -> fallback
    }

    /** Back to an org.json-compatible value (for re-encoding). */
    internal fun toJsonValue(): Any = when (this) {
        is StringValue -> value
        is IntValue -> value
        is DoubleValue -> value
        is BoolValue -> value
        is ArrayValue -> JSONArray().apply { for (v in value) put(v.toJsonValue()) }
        is DictionaryValue -> JSONObject().apply { for ((k, v) in value) put(k, v.toJsonValue()) }
        Null -> JSONObject.NULL
    }

    companion object {
        /** Decode any org.json value into a [SalesAnyValue]. */
        fun from(raw: Any?): SalesAnyValue = when (raw) {
            null, JSONObject.NULL -> Null
            is Boolean -> BoolValue(raw)
            is Int -> IntValue(raw)
            is Long -> if (raw in Int.MIN_VALUE..Int.MAX_VALUE) IntValue(raw.toInt()) else DoubleValue(raw.toDouble())
            is Number -> {
                val d = raw.toDouble()
                // Integer-valued numbers surface as Int (mirrors the Swift
                // decoder trying Int before Double).
                if (d % 1.0 == 0.0 && d >= Int.MIN_VALUE.toDouble() && d <= Int.MAX_VALUE.toDouble()) {
                    IntValue(d.toInt())
                } else {
                    DoubleValue(d)
                }
            }
            is String -> StringValue(raw)
            is JSONArray -> ArrayValue((0 until raw.length()).map { from(raw.opt(it)) })
            is JSONObject -> DictionaryValue(raw.keys().asSequence().associateWith { from(raw.opt(it)) })
            else -> StringValue(raw.toString())
        }

        /** Decode a JSON object into a map of [SalesAnyValue]s. */
        fun mapFrom(o: JSONObject?): Map<String, SalesAnyValue> {
            if (o == null) return emptyMap()
            return o.keys().asSequence().associateWith { from(o.opt(it)) }
        }
    }
}

/** A server-defined paywall. Returned by [SalesClient.paywall]. */
data class SalesPaywall(
    val key: String,
    val name: String,
    val productIds: List<String>,
    val data: Map<String, SalesAnyValue> = emptyMap(),
) {
    companion object {
        internal fun fromJson(o: JSONObject): SalesPaywall = SalesPaywall(
            key = o.optString("key", ""),
            name = o.optString("name", ""),
            productIds = o.optJSONArray("productIds")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it, "").ifEmpty { null } }
            } ?: emptyList(),
            data = SalesAnyValue.mapFrom(o.optJSONObject("data")),
        )
    }
}
