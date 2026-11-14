package vade.ui.chat

import kotlinx.coroutines.flow.Flow
import vade.ui.protocol.VadeEvent

/**
 * B2 · 真源的**读**通道：历史快照 + 实时事件流。
 *
 * `ChatTurnGateway` 是**写**通道（`message/edit_resubmit` / `turn/regenerate`），它答不了
 * 「这个会话已经有哪些消息」这个读问题 —— 这正是真会话点进去看到 6 条 fixtures 的根因之一
 * （另一处是 `ChatState` 无条件用 `ChatFixtures` 做种子）。本接口把读侧补齐，形状直接照
 * `vade.ui.data.SessionSource`（`messages(id)` / `observeEvents()`），所以接上它是**委托**，
 * 不是适配：
 *
 * ```
 * TurnGatewayReal(client).transcript()      // 真适配器：ui/attach 的 snapshot + 事件流
 * InMemoryTurnGateway().transcript()        // null —— 演示路径没有读通道
 * ```
 *
 * 契约（与 `SessionSource` 逐字一致，别在这里加时间/缓存语义）：
 *  - [history] 是**一次性的完整快照**，oldest first；空列表 = 这个会话真的没有消息
 *    （绝不是「加载失败」，也绝不回退 fixtures）；
 *  - [events] 是冷流，每条都是 RFC-0006 的 core→UI 事件，seq 纪律由 [LiveTranscript]
 *    统一裁决（缺口缓冲 / 重复丢弃 / anomaly 记录），本接口不做排序也不去重。
 */
interface ChatTranscriptSource {

    /** 一个会话的完整消息历史，oldest first。空列表是**合法答案**（真空会话）。 */
    suspend fun history(sessionId: String): List<ChatMessage>

    /** 冷流。 */
    fun events(): Flow<VadeEvent>

    companion object {
        /**
         * 函数对形态的构造器：接上真数据源只需要两个方法引用，不必为每个宿主写一个类。
         *
         * ```
         * ChatTranscriptSource.of(source::messages, source::observeEvents)
         * ```
         */
        fun of(
            history: suspend (String) -> List<ChatMessage>,
            events: () -> Flow<VadeEvent>,
        ): ChatTranscriptSource = object : ChatTranscriptSource {
            override suspend fun history(sessionId: String): List<ChatMessage> = history.invoke(sessionId)

            override fun events(): Flow<VadeEvent> = events.invoke()
        }
    }
}
