package vade.ui.chat

import kotlinx.coroutines.flow.Flow
import vade.ui.protocol.TurnInputRequiredPayload
import vade.ui.protocol.VadeEvent

/**
 * 一次 `ui/attach` 快照里**聊天需要的全部东西**：消息 + 还没作答的问答。
 *
 * 为什么不是两个方法：真适配器的一次快照只对应**一次** `ui/attach`。拆成
 * `history()` + `pendingInputs()` 两个入口，同一个会话就会被 attach 两次 —— 同连接双
 * sink 等于事件双投递（`RealSessionSource.createSession` 的 KDoc 明确禁止）。所以快照是
 * 一个整体，一次读拿到两样东西。
 */
data class ChatHistory(
    /** 消息，oldest first。空列表是**合法答案**（真空会话），不是加载失败。 */
    val messages: List<ChatMessage>,
    /**
     * `snapshot.pending_inputs`：断线 / 重进会话时仍未作答的问答（RFC-0008 §8.2）。
     * 空 = 没有未作答的问答（或该源不提供这一块）。
     */
    val pendingInputs: List<TurnInputRequiredPayload> = emptyList(),
)

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
 *  - [snapshot] 是**一次性的完整快照**，oldest first；空消息列表 = 这个会话真的没有消息
 *    （绝不是「加载失败」，也绝不回退 fixtures）。它同时带上快照里的未作答问答 ——
 *    少了这一块，重进会话 / 断线重连后问答卡不会回来（回合永远停在 `waiting_input`）；
 *  - [events] 是冷流，每条都是 RFC-0006 的 core→UI 事件，seq 纪律由 [LiveTranscript]
 *    统一裁决（缺口缓冲 / 重复丢弃 / anomaly 记录），本接口不做排序也不去重。
 */
interface ChatTranscriptSource {

    /**
     * 一个会话的完整快照（消息 + 未作答问答）。
     *
     * 默认实现只要 [history]：老调用点 / 只关心消息的假源一行都不用改，代价是它们报「
     * 快照里没有问答」—— 对不提供这一块的源来说那就是事实。
     */
    suspend fun snapshot(sessionId: String): ChatHistory = ChatHistory(messages = history(sessionId))

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
         *
         * 这条只带消息（[snapshot] 走接口默认实现）；要带快照里的 `pending_inputs` 用
         * [ofSnapshot]。
         */
        fun of(
            history: suspend (String) -> List<ChatMessage>,
            events: () -> Flow<VadeEvent>,
        ): ChatTranscriptSource = object : ChatTranscriptSource {
            override suspend fun history(sessionId: String): List<ChatMessage> = history.invoke(sessionId)

            override fun events(): Flow<VadeEvent> = events.invoke()
        }

        /**
         * 带完整快照的构造器：一次读同时给消息与未作答问答（真适配器用它，见
         * `RealSessionSource.chatSnapshot`）。
         */
        fun ofSnapshot(
            snapshot: suspend (String) -> ChatHistory,
            events: () -> Flow<VadeEvent>,
        ): ChatTranscriptSource = object : ChatTranscriptSource {
            override suspend fun snapshot(sessionId: String): ChatHistory = snapshot.invoke(sessionId)

            override suspend fun history(sessionId: String): List<ChatMessage> = snapshot.invoke(sessionId).messages

            override fun events(): Flow<VadeEvent> = events.invoke()
        }
    }
}
