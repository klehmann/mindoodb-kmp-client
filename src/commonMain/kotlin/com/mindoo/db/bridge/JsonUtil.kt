package com.mindoo.db.bridge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Build a request params object. */
internal fun params(block: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(block)

/** Convert an arbitrary Kotlin value into a JsonElement for document payloads. */
fun anyToJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Int -> JsonPrimitive(value)
    is Long -> JsonPrimitive(value)
    is Double -> JsonPrimitive(value)
    is Float -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to anyToJson(v) })
    is Iterable<*> -> JsonArray(value.map { anyToJson(it) })
    is Array<*> -> JsonArray(value.map { anyToJson(it) })
    else -> JsonPrimitive(value.toString())
}

/**
 * MindooDB typed values for document payloads (`MindooValue` in MindooDB).
 * A plain string is collaborative text; use [atomic] for identifiers, status
 * and enum values, URLs and hashes, where two concurrent changes must not mix.
 */
object MindooValue {
    fun atomic(value: String): JsonObject = buildJsonObject {
        put("\$mindoo", "atomic")
        put("value", value)
    }

    /** A counter; change it with `UpdateOpsBuilder.increment`, not by setting a new one. */
    fun counter(value: Long = 0): JsonObject = buildJsonObject {
        put("\$mindoo", "counter")
        put("value", value)
    }

    /** A timestamp in epoch milliseconds, stored as a date. */
    fun timestamp(epochMillis: Long): JsonObject = buildJsonObject {
        put("\$mindoo", "timestamp")
        put("value", epochMillis)
    }
}
