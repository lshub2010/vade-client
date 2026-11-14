package vade.ui.subagent

/**
 * C-Activity · sub-agent snapshot DTOs for the Activity page's 子代理 panel.
 *
 * The client protocol layer (base f189de2) has no `subagent.*` event or `subagent/get`
 * models yet, so this package carries its own **snapshot types shaped 1:1 after the
 * frozen contract** — the `subagent/get` result (rpc.json / RFC-0012 §6.2), which is
 * also the per-run shape `subagent/list` returns (§6.1, `SubagentRunView`). There is
 * deliberately **no network/JSON code here**: the future data layer maps the RPC result
 * onto [SubagentRunView] and hands the list to the panel. Reaching real data later =
 * passing the mapped DTOs in; zero UI change.
 *
 * 脱敏红线 (RFC-0012 §5.3): `result_digest` is a sha-256 **fingerprint** of the full
 * result text, never the text; `result_preview` is the ≤500-char read-time projection
 * of the child session's last assistant message. The full text lives only in the child
 * session's message stream — this panel can never show it, and says so in the card.
 */

/**
 * `subagent.state` (RFC-0012): the five contract values, plus [UNKNOWN] for a raw value
 * this client does not know. An unknown state renders as a neutral「未知状态」badge — it
 * is NOT collapsed into `running`: claiming a run is active when we cannot know is the
 * kind of invention this slice forbids (same honesty rule as the usage fields).
 */
enum class SubagentState {
    SPAWNED, RUNNING, COMPLETED, FAILED, CANCELLED, UNKNOWN;

    companion object {
        fun fromRaw(raw: String?): SubagentState = when (raw) {
            "spawned" -> SPAWNED
            "running" -> RUNNING
            "completed" -> COMPLETED
            "failed" -> FAILED
            "cancelled" -> CANCELLED
            else -> UNKNOWN
        }
    }
}

/**
 * One sub-agent run, 1:1 with the `subagent/get` result (RFC-0012 §6.2). Field names
 * follow the wire names; nothing here is defaulted into fake data — an absent optional
 * stays `null` and the card renders it as「暂无数据」/「无摘要」.
 */
data class SubagentRunView(
    /** `run_<32hex>` — displayed verbatim, never normalized. */
    val runId: String,
    val parentSessionId: String,
    val parentTurnId: String?,
    /** The run's own LLM-loop session; null while unknown (FR-8.58 jump target later). */
    val childSessionId: String?,
    val label: String,
    val state: SubagentState,
    /** unix ms. */
    val createdAt: Long,
    /** unix ms; null while the run has not terminated. */
    val finishedAt: Long?,
    /** sha-256 hex of the FULL result text — fingerprint only, never the text. */
    val resultDigest: String?,
    /** Length of the full result text; null when unknown. */
    val resultChars: Int?,
    /** C-04 error code name; set when state=failed. */
    val errorCode: String?,
    /** ≤500-char read-time projection of the child session's last assistant message. */
    val resultPreview: String?,
    /** Contract `duration_ms` — elapsed server-side, present for every state. */
    val durationMs: Long,
)
