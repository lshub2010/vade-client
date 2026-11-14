package vade.ui.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Wire JSON for the whole protocol layer.
 *
 * **Why hand-written codecs and no `@Serializable`:** the repo's frozen build matrix
 * (composeApp/build.gradle.kts, untouchable for this batch) does NOT apply the
 * kotlinx-serialization **compiler plugin** — the runtime jar is on the classpath only
 * via Ktor's content-negotiation, so `@Serializable` data classes compile but no
 * serializers are generated. A protocol layer pinned to a frozen contract is better off
 * with explicit field mapping anyway: every key is spelled once, in one place, next to
 * the contract it encodes, and nothing depends on codegen or reflection (wasm-safe).
 */
object VadeJson {
    val default: Json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        encodeDefaults = false
        explicitNulls = true
    }
}

// ------------------------------------------------------------------ decode helpers

/** Safe accessors over [JsonObject] — a missing key and an explicit null behave the same. */
fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

fun JsonObject.lng(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

fun JsonObject.int(key: String): Int? = lng(key)?.let {
    if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else null
}

fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

fun JsonObject.dbl(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

fun JsonObject.el(key: String): JsonElement? = this[key]?.takeIf { it !is JsonNull }

/** The contract writes explicit nulls for self-describing rows; both shapes decode to null. */
fun JsonObject.strOrNull(key: String): String? = this[key]?.let {
    (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
}

// ------------------------------------------------------------------ required-key accessors
//
// A missing REQUIRED key in a result/payload is a decode failure — [PayloadDecodeException]
// — never a silent zero-value fallback (the "all-zero row" bug class). The safe accessors
// above treat absent and null alike; these demand presence.

fun JsonObject.mustStr(key: String): String =
    str(key) ?: throw PayloadDecodeException("wire object misses required string '$key'")

fun JsonObject.mustLng(key: String): Long =
    lng(key) ?: throw PayloadDecodeException("wire object misses required number '$key'")

fun JsonObject.mustInt(key: String): Int =
    int(key) ?: throw PayloadDecodeException("wire object misses required number '$key'")

fun JsonObject.mustBool(key: String): Boolean =
    bool(key) ?: throw PayloadDecodeException("wire object misses required bool '$key'")

fun JsonObject.mustObj(key: String): JsonObject =
    obj(key) ?: throw PayloadDecodeException("wire object misses required object '$key'")

fun JsonObject.mustArr(key: String): JsonArray =
    arr(key) ?: throw PayloadDecodeException("wire object misses required array '$key'")

/** The key must be PRESENT; its value may be JSON null ("required-with-null", RFC-0012 §5.2). */
fun JsonObject.mustKey(key: String): JsonElement =
    this[key] ?: throw PayloadDecodeException("wire object misses required key '$key' (may be null)")

/** required-with-null string: key present (value may be null), a wrong shape is rejected. */
fun JsonObject.mustStrOrNull(key: String): String? {
    val v = mustKey(key)
    if (v is JsonNull) return null
    return (v as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw PayloadDecodeException("wire object field '$key' is not string|null")
}

/** required-with-null number: key present (value may be null), a wrong shape is rejected. */
fun JsonObject.mustLngOrNull(key: String): Long? {
    val v = mustKey(key)
    if (v is JsonNull) return null
    return (v as? JsonPrimitive)?.longOrNull
        ?: throw PayloadDecodeException("wire object field '$key' is not number|null")
}

// ------------------------------------------------------------------ tri-state wire value
//
// Some contract params have THREE states, not two: key omitted / key present with JSON
// null / key present with a value — each with different semantics (session/list.group_id:
// omitted = all groups, null = ungrouped only, a string = that group). A nullable Kotlin
// type cannot express the middle state, so params that need it carry [Optional].

sealed class Optional<out T> {
    /** The key is NOT written to the wire at all. */
    object Absent : Optional<Nothing>()

    /** The key is written as JSON null. */
    object Null : Optional<Nothing>()

    /** The key is written with this value. */
    data class Value<out T>(val value: T) : Optional<T>()

    companion object {
        /** Shorthand for a present value. */
        fun <T> of(value: T): Optional<T> = Value(value)
    }
}

/** Writes [value] honoring the three [Optional] states (absent → no key at all). */
fun <T> JsonObjectBuilderCompat.putOpt(key: String, value: Optional<T>, wire: (T) -> JsonElement) {
    when (value) {
        is Optional.Absent -> Unit
        is Optional.Null -> put(key, JsonNull)
        is Optional.Value -> put(key, wire(value.value))
    }
}

/** The overwhelmingly common string tri-state. */
fun JsonObjectBuilderCompat.putOptStr(key: String, value: Optional<String>) =
    putOpt(key, value) { JsonPrimitive(it) }

// ------------------------------------------------------------------ encode helpers

/** Only present keys are written — absent optional contract keys stay absent on the wire. */
fun JsonObjectBuilderCompat.putStr(key: String, value: String?) {
    if (value != null) put(key, JsonPrimitive(value))
}

fun JsonObjectBuilderCompat.putLng(key: String, value: Long?) {
    if (value != null) put(key, JsonPrimitive(value))
}

fun JsonObjectBuilderCompat.putInt(key: String, value: Int?) {
    if (value != null) put(key, JsonPrimitive(value))
}

fun JsonObjectBuilderCompat.putBool(key: String, value: Boolean?) {
    if (value != null) put(key, JsonPrimitive(value))
}

fun JsonObjectBuilderCompat.putDbl(key: String, value: Double?) {
    if (value != null) put(key, JsonPrimitive(value))
}

fun JsonObjectBuilderCompat.putEl(key: String, value: JsonElement?) {
    if (value != null) put(key, value)
}

fun JsonObjectBuilderCompat.putArr(key: String, values: List<JsonElement>?) {
    if (values != null) put(key, JsonArray(values))
}

/**
 * Type alias so the helpers above can be written once for both [JsonObject.builder]
 * shapes; resolves to kotlinx's JsonObjectBuilder at each use site.
 */
typealias JsonObjectBuilderCompat = kotlinx.serialization.json.JsonObjectBuilder
