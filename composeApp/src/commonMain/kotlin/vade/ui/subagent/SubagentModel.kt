package vade.ui.subagent

import vade.ui.usage.formatDuration

/**
 * C-Activity · the pure display projections for a [SubagentRunView] card.
 *
 * Same honesty contract as the usage panel: the contract gave us a fingerprint, a char
 * count and a ≤500-char preview — never the full result text (RFC-0012 §5.3). Everything
 * the DTO does not carry renders as an explicit empty state, never as invented content.
 */

/** The mandatory desensitization note every summary card shows — exact wording pinned by test. */
const val PREVIEW_ONLY_NOTE: String = "仅预览，全文在子会话"

/** How the card renders the `result_preview` field. */
sealed interface PreviewCell {
    /** A real (non-blank) preview plus the optional full-text char count. */
    data class Text(val preview: String, val chars: Int?) : PreviewCell

    /** No preview was delivered (null OR blank) — honest empty, e.g. failed/cancelled runs. */
    data object NoPreview : PreviewCell
}

fun previewCell(preview: String?, chars: Int?): PreviewCell =
    if (preview.isNullOrBlank()) PreviewCell.NoPreview else PreviewCell.Text(preview, chars)

fun PreviewCell.display(): String = when (this) {
    is PreviewCell.Text -> preview
    PreviewCell.NoPreview -> "无摘要 — 子会话没有可投影的助手输出"
}

/** The「共 N 字」caption; missing (null) →「暂无数据」 — a missing count is never shown as 0 字. */
fun charsCell(chars: Int?): String = when {
    chars == null -> "暂无数据"
    chars == 1 -> "全文共 1 字"
    else -> "全文共 $chars 字"
}

/**
 * The digest as a short fingerprint: first 8 hex chars + an ellipsis. A sha-256 is not
 * secret, but a 64-char wall of hex is noise; the short form still lets the user match
 * the fingerprint the contract documents (consumers reconcile by fingerprint).
 */
fun digestText(digest: String?): String = when {
    digest.isNullOrBlank() -> "暂无数据"
    digest.length <= 8 -> "sha256:$digest"
    else -> "sha256:${digest.take(8)}…"
}

/** Duration line: the contract always carries `duration_ms`, so there is no missing case. */
fun durationText(run: SubagentRunView): String = when (run.state) {
    SubagentState.SPAWNED, SubagentState.RUNNING -> "已运行 ${formatDuration(run.durationMs)}"
    else -> "耗时 ${formatDuration(run.durationMs)}"
}

/**
 * The C-04 error code line — shown ONLY on failed runs that carry one (the contract sets
 * `error_code` when state=failed); every other state renders nothing rather than a
 * placeholder.
 */
fun errorCell(run: SubagentRunView): String? =
    if (run.state == SubagentState.FAILED && !run.errorCode.isNullOrBlank()) "错误码 ${run.errorCode}" else null
