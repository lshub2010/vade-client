package vade.ui.data

import kotlinx.coroutines.flow.Flow
import vade.ui.chat.ChatMessage
import vade.ui.protocol.ApprovalDecision
import vade.ui.protocol.ApprovalScope

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

    /**
     * B6 · `approval/decide`（rpc.json 1.7.0，params 逐字：`request_id` /
     * `decision` = `approve|deny` / `scope` = `once|session`；errors
     * `APPROVER_INVALID | READ_ONLY_SESSION | NOT_FOUND`）。
     *
     * [requestId] 必须来自 `turn.approval_required` 事件的 `request_id` —— 客户端工具卡的
     * callId **不是**它。失败以 [vade.ui.protocol.VadeException] 原码穿透（调用方用
     * [VadeErrorMapping] 分四类），三类语义错误各自可辨：`APPROVER_INVALID`（浏览器会话
     * 不能审批 —— **设计行为**，不是 bug）、`READ_ONLY_SESSION`、`NOT_FOUND`（请求已过期
     * 或不存在）；超时/断线是 `TIMEOUT`/`UNAVAILABLE`（可重试）。
     *
     * 默认实现是**故意的响亮失败**：一个没接 `approval/decide` 的源假装成功，等于把
     * 「点批准没反应」的病（B6）再引进来一次。假源要显式声明本地投影（[FakeSessions]）。
     */
    suspend fun decideApproval(
        requestId: String,
        decision: ApprovalDecision,
        scope: ApprovalScope,
    ): Unit = throw IllegalStateException(
        "SessionSource.decideApproval is not implemented: this source cannot decide approvals " +
            "(implement approval/decide or declare an explicit local-projection fake)",
    )

    /**
     * A4-1 · 新建会话口。真源走 `ui/attach` 且 `session_id = null`（core
     * `manager.attach()` 开头 `if session_id is None: create_session()`，并把
     * `session.list_changed(added)` 扇给其他设备），返回 core 铸的新 id —— **建会话与
     * 建事件订阅是同一个动作**：这次 attach 已经为该会话建好 sink，调用方切过去时不得
     * 再发第二次 attach（同连接同会话双 sink = 事件双投递）。
     *
     * 失败以 [vade.ui.protocol.VadeException] 原码穿透（`UNAVAILABLE`/`TIMEOUT`/…），
     * 调用方用 [VadeErrorMapping] 分四类文案；**不允许失败时伪造本地会话**。
     *
     * 默认实现是故意的响亮失败（与 [decideApproval] 同款）：一个没接「建会话」的源假装
     * 成功，等于把 A4-1 的病（草稿 id 发 `ui/submit` 得 NOT_FOUND）再引进来一次。夹具源
     * 要显式声明本地投影（[FakeSessionSource]）。
     */
    suspend fun createSession(): String = throw IllegalStateException(
        "SessionSource.createSession is not implemented: this source cannot create sessions " +
            "(implement ui/attach with session_id=null or declare an explicit local-projection fake)",
    )
}

/**
 * P1-3 · 组合根关停真源的口子。
 *
 * `UiState.dispose()` 只停 loader 是不够的：真源的协议 runner 带着自己的重连循环，
 * 旧源会在后台无限重试，并与新源形成双连接（配对成功后重建 `UiState` 时尤其明显）。
 * 实现者只关**本次连接**（client + 事件/状态收集器 + 适配器包/清单），**绝不**关
 * 传进来的 transport factory —— cookie 缓存在 factory 里，桥与下一次 `connect()` 还要用它。
 *
 * 夹具源不实现它：没有连接，也就没有可关的东西。
 */
interface DisconnectableSource {
    /** 幂等：重复调用、未连接时调用都是 no-op。 */
    suspend fun disconnect()
}

/**
 * The default [SessionSource] is [FakeSessions] (see `FakeSessions.kt`, which implements
 * the interface with its existing fixtures so behaviour is unchanged); the task-book-named
 * instance below exists because `FakeSessions` is an `object` and cannot be written
 * `FakeSessionSource()` at a call site — this one-line class delegates everything to it
 * (same fixtures, same behaviour).
 */
class FakeSessionSource : SessionSource by FakeSessions {
    /**
     * A4-1 · 夹具源的「建会话」本地投影：只铸一个草稿 id，**出网 0 次** —— 这个类没有
     * transport / client 字段、不实现 [AdapterHost]，结构上就不存在可出网的通道。
     *
     * [createCalls] 是探针。夹具路径（`UiState` 的 `loader == null` 分支）按 w-wire 的
     * 设计是**纯同步**的：那里没有 scope、不允许有协程，所以草稿行仍由
     * `UiState.newSession()` 就地构造，**从不路由过这个 suspend 口** —— 探针钉住
     * 「夹具路径新建 = 对源的出网调用 0 次」。本 override 同时让夹具源是一个完整的
     * [SessionSource]：直接调用它也只回本地 id，绝不落到接口的响亮默认失败上。
     */
    val createCalls = mutableListOf<String>()

    private var draftSeq = 0

    override suspend fun createSession(): String {
        draftSeq += 1
        val id = "draft-$draftSeq"
        createCalls += id
        return id
    }
}
