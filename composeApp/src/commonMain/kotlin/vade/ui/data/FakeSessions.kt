package vade.ui.data

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import vade.ui.chat.ChatFixtures
import vade.ui.chat.ChatItem
import vade.ui.chat.ChatMessage

/**
 * List-side session model for U1-a. **Fake data by design**: this slice does not touch
 * `core/`, has no `ProtocolClient`, and must not pretend otherwise (no fake streaming,
 * no fake "connected" badge). U1-b replaces the source, not the shape.
 *
 * Sorting contract (FR-8.26, task book §2.1): pinned > today > yesterday > last 7 days >
 * earlier; inside a bucket by `updatedAt` descending.
 */
enum class TimeBucket(val label: String) {
    Pinned("置顶"),
    Today("今天"),
    Yesterday("昨天"),
    LastSevenDays("7 天内"),
    Earlier("更早"),
}

@Immutable
data class SessionSummary(
    val id: String,
    val title: String,
    val preview: String,
    val bucket: TimeBucket,
    /** Descending sort key inside a bucket; larger = more recent. */
    val updatedAt: Long,
    /** Human label rendered on the right of the list row. */
    val updatedLabel: String,
    val pinned: Boolean = false,
    val unread: Boolean = false,
    /** Model name if the session pins one explicitly (D101); null = global default. */
    val model: String? = null,
    /** Drafts created with ✎ in this run — never persisted (U1-b owns submit). */
    val isDraft: Boolean = false,
)

data class SessionGroup(val bucket: TimeBucket, val sessions: List<SessionSummary>)

/**
 * The default [SessionSource] (v2 Wave 0): same fixtures, same behaviour as before the
 * seam existed — `sessions()` is [all], `messages()` is exactly what
 * `ChatFixtures.threadFor(sessionId)` feeds `ChatState`'s seed (the `ChatItem.Message`
 * projections; system/tool/approval rows are not messages and are not included), and
 * `observeEvents()` is empty because no fixture ever emits an event.
 */
object FakeSessions : SessionSource {
    override suspend fun sessions(): List<SessionSummary> = all

    override suspend fun messages(sessionId: String): List<ChatMessage> =
        ChatFixtures.threadFor(sessionId).items
            .filterIsInstance<ChatItem.Message>()
            .map { it.message }

    override fun observeEvents(): Flow<VadeEvent> = emptyFlow()

    private const val D = 1L
    private const val H = 60L

    /** Six rows: two pinned + today(2) + yesterday(1) + last-7-days(1) = 3 buckets. */
    val all: List<SessionSummary> = listOf(
        SessionSummary(
            id = "s-rfc0004",
            title = "RFC-0004 附件与多模态评审",
            preview = "把 POST /ui/blob 的分片与断点续传写进 §3…",
            bucket = TimeBucket.Pinned,
            updatedAt = 3 * D + 2 * H,
            updatedLabel = "3 天前",
            pinned = true,
        ),
        SessionSummary(
            id = "s-matrix",
            title = "vade core 构建矩阵升级",
            preview = "AGP 9.5.1 + Gradle 9.6.1 已验证，出 APK 13.5 MB",
            bucket = TimeBucket.Pinned,
            updatedAt = 5 * D,
            updatedLabel = "5 天前",
            pinned = true,
            model = "claude-sonnet",
        ),
        SessionSummary(
            id = "s-u1a",
            title = "U1-a 骨架验收",
            preview = "四个一级目的地 + 三态底栏，等真窗口截图",
            bucket = TimeBucket.Today,
            updatedAt = 12 * H,
            updatedLabel = "12:40",
            unread = true,
        ),
        SessionSummary(
            id = "s-skin11",
            title = "皮肤组件 11 个冻结",
            preview = "D93：+MessageBubble / AttachmentChip / ToolCallCard / InputBar",
            bucket = TimeBucket.Today,
            updatedAt = 3 * H,
            updatedLabel = "21:05",
        ),
        SessionSummary(
            id = "s-wasm",
            title = "wasm 体积回归",
            preview = "app.wasm + 主 JS gzip 1.04 MB，留 10% 余量",
            bucket = TimeBucket.Yesterday,
            updatedAt = 26 * H,
            updatedLabel = "昨天 19:12",
        ),
        SessionSummary(
            id = "s-agp",
            title = "AGP 9.5.1 矩阵验证",
            preview = "两个开关缺一不可：builtInKotlin + newDsl",
            bucket = TimeBucket.LastSevenDays,
            updatedAt = 4 * D,
            updatedLabel = "4 天前",
            model = "local-qwen",
        ),
    )

    private val order = listOf(
        TimeBucket.Pinned,
        TimeBucket.Today,
        TimeBucket.Yesterday,
        TimeBucket.LastSevenDays,
        TimeBucket.Earlier,
    )

    /** Buckets in IA order, empty buckets dropped (a group header with no rows is noise). */
    fun groups(extra: List<SessionSummary> = emptyList()): List<SessionGroup> {
        val rows = all + extra
        return order.mapNotNull { bucket ->
            val items = rows.filter { it.bucket == bucket }.sortedByDescending { it.updatedAt }
            if (items.isEmpty()) null else SessionGroup(bucket, items)
        }
    }
}
