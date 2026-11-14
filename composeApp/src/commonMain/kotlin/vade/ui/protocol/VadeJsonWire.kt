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
