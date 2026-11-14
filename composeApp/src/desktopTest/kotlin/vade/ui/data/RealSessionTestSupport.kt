package vade.ui.data

import kotlinx.serialization.json.JsonObject
import vade.ui.protocol.FakeTransportFactory
import vade.ui.protocol.VadeClientConfig
import vade.ui.protocol.VadeJson

/**
 * Shared test support for the real-data-layer tests: a small-timeout client config
 * (keepalive off, fast backoff) plus JSON helpers. Drives [RealSessionSource] through
 * the protocol layer's in-memory [FakeTransportFactory] — the same harness the frozen
 * protocol tests use, so no real socket is ever opened.
 */
internal fun dataTestConfig(
    baseMs: Long = 5,
    capMs: Long = 40,
    timeoutMs: Long = 2000,
): VadeClientConfig = VadeClientConfig(
    host = "127.0.0.1",
    port = 1,
    browserSession = null,
    requestTimeoutMs = timeoutMs,
    reconnectBaseMs = baseMs,
    reconnectCapMs = capMs,
    keepaliveIntervalMs = null,
)

internal fun json(text: String): JsonObject =
    VadeJson.default.parseToJsonElement(text) as JsonObject

/** One session/list row with the exact RFC-0010 §5 wire shape. */
internal fun sessionRow(
    id: String,
    updatedAt: Long,
    title: String? = "标题",
    pinned: Boolean = false,
    archived: Boolean = false,
    unread: Int = 0,
): String = buildString {
    append("{\"session_id\":\"$id\",\"created_at\":${updatedAt - 1000},\"updated_at\":$updatedAt,")
    append("\"title\":${if (title == null) "null" else "\"$title\""},")
    append("\"icon\":null,\"color\":null,\"group_id\":null,")
    append("\"pinned_at\":${if (pinned) "1700000000000" else "null"},")
    append("\"archived_at\":${if (archived) "1700000000000" else "null"},")
    append("\"unread\":$unread,\"state\":\"open\"}")
}
