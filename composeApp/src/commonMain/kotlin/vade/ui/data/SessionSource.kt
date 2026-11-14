package vade.ui.data

import kotlinx.coroutines.flow.Flow
import vade.ui.chat.ChatMessage

/**
 * Client-side placeholder for a protocol event, until the contracts batch lands.
 *
 * The client UI is deliberately protocol-free (D98: no core, no protocol, no persistence),
 * and the `contracts/vade` submodule is not even checked out in this slice — so there is no
 * real `VadeEvent` type to import yet. This empty sealed interface pins only the *shape* of
 * [SessionSource.observeEvents] (`Flow<VadeEvent>`): nothing can be instantiated from it,
 * fake sources return an empty flow, and the protocol batch replaces it with the real
 * event hierarchy (subtypes of the real type) without touching the interface's callers.
 */
sealed interface VadeEvent

/**
 * The state-source seam between the UI layer and wherever sessions really come from.
 *
 * Wave 0 (v2-base) only *declares* it: `UiState` / `App` take an optional [SessionSource]
 * and default to the fake implementation, so every existing page keeps running unchanged.
 * A later batch passes a real source (Ktor transport — the deps were added in this batch)
 * and the UI starts pulling from it; nothing in `chat/` `destinations/` `search/` `shell/`
 * had to move for that to become possible.
 *
 * All methods are suspending or flow-returning on purpose: a real transport is async and
 * the fake one simply answers immediately.
 */
interface SessionSource {
    /**
     * Session rows for the list screen, in no particular order (UI groups/buckets them).
     *
     * ## [includeArchived]（g-archive-ui）
     * 归档行要不要进结果。默认 `false` 与既有行为**逐字节一致**：`session/list` 不传
     * `archived`（契约默认 false），投影 [vade.ui.data.sessionRowToSummary] 也会把
     * 「core 明明发了、但带 `archived_at`」的行丢掉。传 `true` 时投影不再把归档行当
     * 过滤谓词丢弃，行上带 `archived = true` 照样返回 —— 归档视图/「已归档」标记的入口。
     *
     * 注意这是**客户端接受度**开关，不是线上过滤器：契约 `session/list.params.archived`
     * 的 `true` 意思是「只回归档行」（筛选反转成一档纯归档视图），所以这里刻意不动线上
     * 参数 —— 传 `true` 只放宽本地投影，不改变请求（见 [RealSessionSource.sessions]）。
     */
    suspend fun sessions(includeArchived: Boolean = false): List<SessionSummary>

    /** The message thread of one session, oldest first. */
    suspend fun messages(sessionId: String): List<ChatMessage>

    /** Live protocol events (turn deltas, approvals, …). Cold stream; empty for the fake. */
    fun observeEvents(): Flow<VadeEvent>
}

/**
 * The default [SessionSource] is [FakeSessions] (see `FakeSessions.kt`, which implements
 * the interface with its existing fixtures so behaviour is unchanged); the task-book-named
 * instance below exists because `FakeSessions` is an `object` and cannot be written
 * `FakeSessionSource()` at a call site — this one-line class delegates everything to it
 * (same fixtures, same behaviour).
 */
class FakeSessionSource : SessionSource by FakeSessions
