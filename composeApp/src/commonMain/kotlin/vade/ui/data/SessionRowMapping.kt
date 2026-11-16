package vade.ui.data

import kotlinx.serialization.json.JsonObject
import vade.ui.protocol.lng
import vade.ui.protocol.str
import kotlin.time.ExperimentalTime
import kotlin.time.Clock

/** `session/list` page size — the contract default (limit 1..200, default 50). */
internal const val SESSION_LIST_PAGE_LIMIT = 50

/** Hard cap on session/list pages per `sessions()` call — a non-terminating cursor must not spin forever. */
internal const val SESSION_LIST_MAX_PAGES = 20

private const val DAY_MS = 24L * 60 * 60 * 1000
private const val MINUTE_MS = 60L * 1000

/** Common-code epoch clock (kotlin.time.Clock — no platform expect/actual needed on Kotlin 2.4). */
@OptIn(ExperimentalTime::class)
internal fun epochNowMs(): Long = Clock.System.now().toEpochMilliseconds()

/**
 * `session/list` row (RFC-0010 §5 `to_wire` shape) → the UI's [SessionSummary].
 * Pure ([nowMs] injected) so tests can pin the bucket/label derivation.
 *
 * `preview` stays empty: session/list rows are deliberately light — no messages, no
 * turns ("content belongs to ui/attach", core models.py) — and this batch adds no
 * extra round trips per row. `model` is null: the list row carries no model field
 * (D101 badge lives on attach's snapshot.session.model).
 *
 * ## 元数据字段（g-summary-fields）
 * - `archived` ← `archived_at: int|null`. **契约没有 `archived` 布尔字段**，归档是
 *   「时间戳在不在」；读 `row["archived"]` 会永远得到 false（那是 `session/archive`
 *   的**入参**，不是回包字段）。
 * - `groupId` ← `group_id: string|null`。单归属列（§1 #1 文件夹语义，不是 `groups[]`），
 *   缺键 / 显式 null 都投影成 null（未分组）；类型不对同样落 null —— 与 [str] 在
 *   本文件其余字段上的宽松口径一致，宁可「未分组」也不要为一个畸形行炸掉整页。
 * - `updated_at` 是契约必填；缺键时 [SessionSummary.updatedLabel] 留空（行上不画时间）、
 *   桶落「更早」。绝不拿 `0L` 去算相对时间 —— 那会画出「20670 天前」。
 *
 * ## [includeArchived]
 * 默认 `false` 保持既有行为：`sessions()` 不传 `archived`，core 按契约默认只回未归档行，
 * 真回了归档行也不进列表（可见列表 = 未归档）。归档视图要传 `true`，否则本函数会把
 * 每一行都丢掉（`return null`），归档筛选永远空白。
 */
internal fun sessionRowToSummary(
    row: JsonObject,
    nowMs: Long,
    includeArchived: Boolean = false,
): SessionSummary? {
    val id = row.str("session_id") ?: return null
    val pinned = row.lng("pinned_at") != null
    val archivedAt = row.lng("archived_at")
    // Archived rows never reach the default (archived=false) list even if a core sent them anyway.
    if (archivedAt != null && !includeArchived) return null
    // 契约里 `updated_at` 是**必填**（`SessionRow.updatedAt` = `mustLng`）。真缺了就是回包畸形，
    // 但列表不能因为一行畸形整页炸掉 —— 于是：桶落「更早」、时间标签**留空**。
    // 以前这里默认 0L，行上会显示「20670 天前」（1970 到现在）：一个凭空编出来的时间。
    // 行现在真的画时间（alpha J2），所以这条必须诚实 —— 没有就说没有，不编。
    val updatedAtRow = row.lng("updated_at")
    val updatedAt = updatedAtRow ?: 0L
    return SessionSummary(
        id = id,
        title = row.str("title") ?: "（无标题）",
        preview = "",
        bucket = bucketFor(updatedAtMs = updatedAt, pinned = pinned, nowMs = nowMs),
        updatedAt = updatedAt,
        updatedLabel = updatedAtRow?.let { relativeLabel(updatedAtMs = it, nowMs = nowMs) }.orEmpty(),
        pinned = pinned,
        unread = (row.lng("unread") ?: 0L) > 0,
        model = null,
        isDraft = false,
        archived = archivedAt != null,
        groupId = row.str("group_id"),
    )
}

/**
 * FR-8.26 bucket order (pinned > today > yesterday > last 7 days > earlier).
 * "Day" arithmetic is age-based UTC-day division — consistent with the fixture rows
 * in `FakeSessions`, which also bucket by elapsed time, and deterministic for tests.
 */
internal fun bucketFor(updatedAtMs: Long, pinned: Boolean, nowMs: Long): TimeBucket {
    if (pinned) return TimeBucket.Pinned
    val days = ((nowMs - updatedAtMs).coerceAtLeast(0L)) / DAY_MS
    return when (days) {
        0L -> TimeBucket.Today
        1L -> TimeBucket.Yesterday
        in 2L..7L -> TimeBucket.LastSevenDays
        else -> TimeBucket.Earlier
    }
}

/**
 * Row label, mirroring the fixture vocabulary ("刚刚" / "N 分钟前" / "HH:mm" /
 * "昨天 HH:mm" / "N 天前"). HH:mm is the UTC wall time of [updatedAtMs] — the same
 * instant core wrote, no timezone conversion (KMP has no common zoned clock).
 */
internal fun relativeLabel(updatedAtMs: Long, nowMs: Long): String {
    val age = (nowMs - updatedAtMs).coerceAtLeast(0L)
    val minutes = age / MINUTE_MS
    if (minutes < 1) return "刚刚"
    if (minutes < 60) return "$minutes 分钟前"
    val days = age / DAY_MS
    if (days == 0L) return hourMinuteUtc(updatedAtMs)
    if (days == 1L) return "昨天 " + hourMinuteUtc(updatedAtMs)
    return "$days 天前"
}

private fun hourMinuteUtc(epochMs: Long): String {
    val minuteOfDay = ((epochMs / MINUTE_MS) % (24 * 60)).toInt()
    val h = (minuteOfDay / 60).toString().padStart(2, '0')
    val m = (minuteOfDay % 60).toString().padStart(2, '0')
    return "$h:$m"
}
