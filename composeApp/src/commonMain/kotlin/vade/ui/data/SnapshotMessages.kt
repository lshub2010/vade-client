package vade.ui.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import vade.ui.chat.ChatMessage
import vade.ui.chat.ChatRole
import vade.ui.chat.ContentPart as UiContentPart
import vade.ui.chat.MessageState
import vade.ui.protocol.ContentPart as ProtocolContentPart
import vade.ui.protocol.bool
import vade.ui.protocol.str

/**
 * `ui/attach` snapshot → the UI thread (`SessionSource.messages`).
 *
 * Row shape is core's `Message.to_dict()` (`core/vade/session/models.py`):
 * `{id, session_id, turn_id, role, content, seq, name, tool_call_id, created_at,
 * partial, superseded_by, parts[]}` where parts carry the RFC-0004 §6.3 content-part
 * shapes. Rows written before `parts` existed are rendered from `content` — core
 * synthesises one text part for them (`wire_parts`), and so do we here, so both shapes
 * decode identically.
 *
 * Tool-role rows are skipped on purpose: tool cards are event-driven UI (turn.tool_call
 * / turn.tool_result), not snapshot message rows.
 */
internal fun snapshotToMessages(element: JsonElement?): List<ChatMessage> {
    val rows = element as? JsonArray ?: return emptyList()
    return rows.mapNotNull { row -> (row as? JsonObject)?.let(::snapshotRowToMessage) }
}

private fun snapshotRowToMessage(row: JsonObject): ChatMessage? {
    val id = row.str("id") ?: return null
    val role = when (row.str("role")) {
        "user" -> ChatRole.User
        "assistant" -> ChatRole.Assistant
        "system" -> ChatRole.System
        else -> return null
    }
    val parts = ProtocolContentPart.fromJson(row["parts"]).mapNotNull(::toUiPart)
    val body = if (parts.isEmpty()) {
        listOf(UiContentPart.Text(row.str("content").orEmpty()))
    } else {
        parts
    }
    val partial = row.bool("partial") == true
    return ChatMessage(
        id = id,
        role = role,
        parts = body,
        state = if (partial) MessageState.Interrupted else MessageState.Completed,
        partialChars = if (partial) body.codePointCount() else 0,
    )
}

private fun toUiPart(part: ProtocolContentPart): UiContentPart? = when (part) {
    is ProtocolContentPart.TextPart -> UiContentPart.Text(part.text)
    is ProtocolContentPart.ImagePart -> UiContentPart.Image(
        fileId = part.fileId,
        name = part.name,
        size = part.size,
        width = part.width,
        height = part.height,
        mime = part.mime,
        sha256 = part.sha256,
    )
    is ProtocolContentPart.FilePart -> UiContentPart.File(
        fileId = part.fileId,
        name = part.name,
        size = part.size,
        mime = part.mime,
    )
    // v1 keeps audio reserved on the wire (D97) and the UI model has no audio part.
    is ProtocolContentPart.AudioPart -> null
}

/**
 * RFC-0006 partial_chars is CODE POINTS already streamed — count code points, not
 * UTF-16 units (a low surrogate never starts a code point; common-safe arithmetic).
 */
private fun List<UiContentPart>.codePointCount(): Int =
    filterIsInstance<UiContentPart.Text>()
        .joinToString("\n") { it.text }
        .count { !it.isLowSurrogate() }
