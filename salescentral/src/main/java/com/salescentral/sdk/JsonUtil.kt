package com.salescentral.sdk

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Internal JSON helpers shared by the hand-written model parsers. The SDK
 * deliberately avoids reflection-based (de)serialization — every model
 * parses itself from `org.json`, tolerantly, mirroring the Swift SDK's
 * custom decoders (older / leaner server responses must not fail decode).
 */
internal object JsonUtil {

    /**
     * Parse an ISO-8601 date. The server serializes via JSON.stringify,
     * which emits fractional seconds ("2026-06-11T08:15:30.123Z");
     * [Instant.parse] accepts both fractional and plain. Offset forms
     * ("+02:00") fall back to [OffsetDateTime]. Returns null if the
     * string is missing or unrecognized.
     */
    fun parseDate(s: String?): Instant? {
        if (s.isNullOrEmpty()) return null
        return try {
            Instant.parse(s)
        } catch (_: Exception) {
            try {
                OffsetDateTime.parse(s).toInstant()
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Format an [Instant] the way the server expects (ISO-8601 UTC). */
    fun formatDate(d: Instant): String = DateTimeFormatter.ISO_INSTANT.format(d)

    /** Optional string — null when missing, JSON null, or empty. */
    fun optString(o: JSONObject, key: String): String? {
        if (!o.has(key) || o.isNull(key)) return null
        return o.optString(key, "").ifEmpty { null }
    }

    /** Optional int — null when missing or not a number. */
    fun optInt(o: JSONObject, key: String): Int? {
        if (!o.has(key) || o.isNull(key)) return null
        return when (val v = o.opt(key)) {
            is Number -> v.toInt()
            else -> null
        }
    }

    /** Optional boolean — null when missing or not a boolean. */
    fun optBool(o: JSONObject, key: String): Boolean? {
        if (!o.has(key) || o.isNull(key)) return null
        return o.opt(key) as? Boolean
    }

    /** Optional ISO date field. */
    fun optDate(o: JSONObject, key: String): Instant? = parseDate(optString(o, key))

    /**
     * Encode an arbitrary Kotlin value into an org.json-compatible value.
     * Supports null, String, Boolean, Number, Instant, Map<String, *>,
     * List/Array, [SalesAnyValue], and [SalesPropertyValue]. Anything else
     * is stringified (analytics-grade tolerance, never throws).
     */
    fun toJsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is String -> value
        is Boolean -> value
        is Int, is Long -> value
        is Double -> if (value.isFinite() && value % 1.0 == 0.0 &&
            value >= Long.MIN_VALUE.toDouble() && value <= Long.MAX_VALUE.toDouble()
        ) value.toLong() else value
        is Float -> toJsonValue(value.toDouble())
        is Number -> value
        is Instant -> formatDate(value)
        is SalesPropertyValue -> value.toJsonValue()
        is SalesAnyValue -> value.toJsonValue()
        is Map<*, *> -> JSONObject().apply {
            for ((k, v) in value) put(k.toString(), toJsonValue(v))
        }
        is List<*> -> JSONArray().apply { for (v in value) put(toJsonValue(v)) }
        is Array<*> -> JSONArray().apply { for (v in value) put(toJsonValue(v)) }
        is JSONObject, is JSONArray -> value
        else -> value.toString()
    }
}
