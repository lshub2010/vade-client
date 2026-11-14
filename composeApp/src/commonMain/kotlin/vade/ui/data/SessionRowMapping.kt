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
 */
internal fun sessionRowToSummary(row: JsonObject, nowMs: Long): SessionSummary? {
    val id = row.str("session_id") ?: return null
    val pinned = row.lng("pinned_at") != null
    // Archived rows never reach the visible list even if a core sent them anyway.
    if (row.lng("archived_at") != null) return null
    val updatedAt = row.lng("updated_at") ?: 0L
    return SessionSummary(
        id = id,
        title = row.str("title") ?: "（无标题）",
        preview = "",
        bucket = bucketFor(updatedAtMs = updatedAt, pinned = pinned, nowMs = nowMs),
        updatedAt = updatedAt,
        updatedLabel = relativeLabel(updatedAtMs = updatedAt, nowMs = nowMs),
        pinned = pinned,
        unread = (row.lng("unread") ?: 0L) > 0,
        model = null,
        isDraft = false,
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
