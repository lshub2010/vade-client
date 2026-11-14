package vade.ui.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Ordered content parts for `ui/submit` / `message/edit_resubmit` (RFC-0004 §6.1).
 *
 * Wire shape is the contract's exactly: the discriminator key `type` selects `text` /
 * `image` / `file` / `audio`, and field names are the contract's verbatim snake_case
 * (`file_id`, `sha256`, …). Order is the display order; the server must not reorder it.
 * A message with no text part but at least one image/file part is legal (pure-image
 * turn); 20 parts is the frozen hard cap.
 *
 * Codecs are hand-written ([toJson] / [fromJson]) — this layer must not depend on the
 * kotlinx-serialization compiler plugin, which the frozen build matrix does not apply
 * (see [VadeJson]).
 */
sealed class ContentPart {

    /** `{type: "text", text: 1..32000}` — the only part v1 answers must contain. */
    data class TextPart(val text: String) : ContentPart()

    /**
     * `{type: "image", file_id, mime, name, size, width, height, sha256}` — mime is
     * contract-constrained (image/jpeg|png|webp) but travels as a String so a future
     * mime cannot break the codec; server-side validation owns the rejection.
     */
    data class ImagePart(
        val fileId: String,
        val mime: String,
        val name: String,
        val size: Long,
        val width: Int,
        val height: Int,
        val sha256: String,
    ) : ContentPart()

    /** `{type: "file", file_id, mime, name, size}`. */
    data class FilePart(
        val fileId: String,
        val mime: String,
        val name: String,
        val size: Long,
    ) : ContentPart()

    /**
     * `{type: "audio", …}` — **reserved on purpose** (§7.2 / D97): the type stays in the
     * union so FR-8.42 is not narrowed, but v1 answers 501 UNSUPPORTED with
     * `data.reason='audio_disabled'`. Clients may construct it; the core will refuse it.
     */
    data class AudioPart(
        val fileId: String,
        val mime: String,
        val name: String,
        val size: Long,
    ) : ContentPart()

    fun toJson(): JsonElement = buildJsonObject {
        when (this@ContentPart) {
            is TextPart -> {
                put("type", "text")
                put("text", text)
            }
            is ImagePart -> {
                put("type", "image")
                put("file_id", fileId)
                put("mime", mime)
                put("name", name)
                put("size", size)
                put("width", width)
                put("height", height)
                put("sha256", sha256)
            }
            is FilePart -> {
                put("type", "file")
                put("file_id", fileId)
                put("mime", mime)
                put("name", name)
                put("size", size)
            }
            is AudioPart -> {
                put("type", "audio")
                put("file_id", fileId)
                put("mime", mime)
                put("name", name)
                put("size", size)
            }
        }
    }

    companion object {
        /** `content` array codec. */
        fun listToJson(parts: List<ContentPart>): JsonArray =
            JsonArray(parts.map { it.toJson() })

        /** An unknown part `type` is skipped, not fatal (append-only contract). */
        fun fromJson(element: JsonElement?): List<ContentPart> {
            val array = element as? JsonArray ?: return emptyList()
            return array.mapNotNull { part ->
                val obj = part as? JsonObject ?: return@mapNotNull null
                when (obj.strStrict("type")) {
                    "text" -> TextPart(obj.strStrict("text") ?: "")
                    "image" -> ImagePart(
                        fileId = obj.strStrict("file_id") ?: "",
                        mime = obj.strStrict("mime") ?: "",
                        name = obj.strStrict("name") ?: "",
                        size = obj.lngStrict("size") ?: 0,
                        width = obj.intStrict("width") ?: 0,
                        height = obj.intStrict("height") ?: 0,
                        sha256 = obj.strStrict("sha256") ?: "",
                    )
                    "file" -> FilePart(
                        fileId = obj.strStrict("file_id") ?: "",
                        mime = obj.strStrict("mime") ?: "",
                        name = obj.strStrict("name") ?: "",
                        size = obj.lngStrict("size") ?: 0,
                    )
                    "audio" -> AudioPart(
                        fileId = obj.strStrict("file_id") ?: "",
                        mime = obj.strStrict("mime") ?: "",
                        name = obj.strStrict("name") ?: "",
                        size = obj.lngStrict("size") ?: 0,
                    )
                    else -> null
                }
            }
        }
    }
}

/**
 * `turn/input.value` (RFC-0008 §7.1): `string | string[] | bool` — one wire field,
 * three shapes. Decoding is strict about the three declared shapes only: anything else
 * raises PROTOCOL_MISMATCH — the value is handed to the model as a tool result, never
 * executed, so failing closed on an alien shape is the right reflex.
 */
sealed class InputValue {

    /** A single string answer (free_text / single_choice / confirm). */
    data class Text(val value: String) : InputValue()

    /** Multi-select answers (multi_choice). */
    data class Multi(val values: List<String>) : InputValue()

    /** Confirm answers. */
    data class Flag(val value: Boolean) : InputValue()

    fun toJson(): JsonElement = when (this) {
        is Text -> JsonPrimitive(value)
        is Multi -> JsonArray(values.map { JsonPrimitive(it) })
        is Flag -> JsonPrimitive(value)
    }

    companion object {
        fun fromJson(element: JsonElement?): InputValue = when {
            element is JsonPrimitive && element.booleanOrNull != null -> Flag(element.boolean)
            element is JsonPrimitive && element.isString -> Text(element.contentOrNull ?: "")
            element is JsonArray -> Multi(element.map { (it as? JsonPrimitive)?.contentOrNull ?: "" })
            else -> throw VadeException(
                VadeErrorCodes.PROTOCOL_MISMATCH,
                "turn/input.value must be string | string[] | bool, got: $element",
            )
        }

        /** Factory shorthands so call sites read `InputValue.of("…")` / `InputValue.of(true)`. */
        fun of(value: String): InputValue = Text(value)

        fun of(vararg values: String): InputValue = Multi(values.toList())

        fun of(value: Boolean): InputValue = Flag(value)
    }
}
