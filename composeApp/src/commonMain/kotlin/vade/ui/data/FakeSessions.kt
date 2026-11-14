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
    /**
     * `session/list` 行的 `archived_at: int|null` → 布尔投影（`archived_at != null`）。
     *
     * 契约没有 `archived` 布尔字段 —— 归档是「时间戳在不在」，不是「标志位真不真」；
     * 从线上回包直接读 `row["archived"]` 是编字段。这里保留布尔是因为 UI 只需要
     * 「要不要打归档标记 / 该不该出现在归档筛选里」，时间戳对列表无用。
     *
     * 默认 `false`：现有 40+ 处构造与 [SessionSummary.copy] 不传即「未归档」。
     */
    val archived: Boolean = false,
    /**
     * `session/list` 行的 `group_id: string|null` 逐字保留（契约 §1 #1：文件夹式单归属，
     * 不是 `groups[]` 标签集）。`null` = 未分组。
     */
    val groupId: String? = null,
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
    /**
     * 夹具源**没有**服务端筛选这回事：两档都返回 [all]（含两行 `archived`）。
     *
     * 为什么不按 [includeArchived] 过滤：`includeArchived = false` 的语义是「与既有行为
     * 逐字节一致」，而假数据的默认列表本来就是这 6 行（`RealCoreUiE2ETest` 的
     * `FIXTURE_IDS` 逐一钉着）。在这里把 s-wasm / s-agp 滤掉就是改了默认列表，而且演示
     * 界面里再也看不到「已归档」标记 —— 那正是本棒要让人看见的东西。真源一侧的筛选语义
     * 由 `sessionRowToSummary` 与 `RealSessionSource` 的测试钉住。
     */
    override suspend fun sessions(includeArchived: Boolean): List<SessionSummary> = all

    override suspend fun messages(sessionId: String): List<ChatMessage> =
        ChatFixtures.threadFor(sessionId).items
            .filterIsInstance<ChatItem.Message>()
            .map { it.message }

    override fun observeEvents(): Flow<VadeEvent> = emptyFlow()

    private const val D = 1L
    private const val H = 60L

    /**
     * Six rows: two pinned + today(2) + yesterday(1) + last-7-days(1) = 3 buckets.
     *
     * `archived` / `groupId` coverage is deliberate (g-summary-fields): TWO archived rows and
     * THREE grouped rows, so the list has something to show for both dimensions — with one
     * row in BOTH (s-agp: archived and in g-1) and rows in NEITHER, since a filter UI must
     * prove it can tell "not archived" from "archived" and "ungrouped" from "grouped".
     * Group ids reuse the fake groups in `vade.ui.meta.FakeMetaActions` (g-1 项目 / g-2 灵感)
     * so a group-name lookup has a real target.
     */
    val all: List<SessionSummary> = listOf(
        SessionSummary(
            id = "s-rfc0004",
            title = "RFC-0004 附件与多模态评审",
            preview = "把 POST /ui/blob 的分片与断点续传写进 §3…",
            bucket = TimeBucket.Pinned,
            updatedAt = 3 * D + 2 * H,
            updatedLabel = "3 天前",
            pinned = true,
            groupId = "g-1",
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
            groupId = "g-2",
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
            archived = true,
        ),
        SessionSummary(
            id = "s-agp",
            title = "AGP 9.5.1 矩阵验证",
            preview = "两个开关缺一不可：builtInKotlin + newDsl",
            bucket = TimeBucket.LastSevenDays,
            updatedAt = 4 * D,
            updatedLabel = "4 天前",
            model = "local-qwen",
            archived = true,
            groupId = "g-1",
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

/**
 * FR-8.26 grouping for an ARBITRARY row list — the grouping `UiState` uses once its rows
 * come from a real [SessionSource] (w-wire). Same bucket order ([TimeBucket] declaration
 * order == the IA order of [FakeSessions.groups]'s private table), same within-bucket
 * descending `updatedAt` sort, same empty-bucket drop. Kept beside [FakeSessions.groups]
 * so the two grouping contracts cannot drift apart — the shell wiring test pins that a
 * source serving the fixture rows groups identically to [FakeSessions.groups].
 */
fun sessionGroups(rows: List<SessionSummary>): List<SessionGroup> =
    TimeBucket.entries.mapNotNull { bucket ->
        val items = rows.filter { it.bucket == bucket }.sortedByDescending { it.updatedAt }
        if (items.isEmpty()) null else SessionGroup(bucket, items)
    }
