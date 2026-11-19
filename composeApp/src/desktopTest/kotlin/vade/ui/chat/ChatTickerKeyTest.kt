package vade.ui.chat

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import vade.ui.protocol.TurnCompleted
import vade.ui.protocol.TurnCompletedPayload
import vade.ui.protocol.TurnDelta
import vade.ui.protocol.TurnDeltaPayload
import vade.ui.protocol.VadeEvent
import vade.ui.protocol.VadeUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * H2 · 两个 ticker 的键**不许**随数据抖动（第 2 轮性能报告 §2）。
 *
 * 事故形态：`LaunchedEffect(animate, state.isStreaming)` / `LaunchedEffect(animate,
 * state.staged.size)` 写在 `ChatThread` 主体里 —— 而 `isStreaming` 在真源上要扫 `items`、
 * `staged` 是 `SnapshotStateList`。于是每一条 `turn.delta`（45ms 一次）、每一次上传进度
 * （160ms 一次）都让**整页**组合作用域失效：`Column(verticalScroll)` 的 content lambda 与
 * `items.forEach` 重新跑一遍，协程也可能跟着重建。
 *
 * 修法：键改成 [ChatState.streamingTickerKey] / [ChatState.uploadTickerKey] 两个派生标量
 * （`derivedStateOf`），并把 effect 下沉到没有 UI 子节点的叶子。本类用**计数器**钉住两件事：
 *  1. delta / 进度写入**不再让读键的作用域重组**（旧键在真源上每条 delta 必然重组一次）；
 *  2. `LaunchedEffect` 只在「开始了 / 结束了 / chip 数量变了」这些**边沿**上重启。
 */
@OptIn(ExperimentalTestApi::class)
class ChatTickerKeyTest {

    // --- 探针：真通道（有 `items` 读点）+ 一份隔离的草稿表 ----------------------

    /**
     * 真 gateway 的替身：`synthetic` 走接口默认值（false = 真通道），读能力由构造参数决定；
     * 写方法委托给内存假实现（本文件只用读取/投影那一路）。与 `RealChatSourceTest` 同一个
     * 形态 —— 真源模式下 `isStreaming` 才会走 `realRunningTurnId()` 的 `items` 扫描，那正是
     * H2 要钉住的那条路径。
     */
    private class ProbeGateway(private val channel: ChatTranscriptSource) : ChatTurnGateway {

        private val writes = InMemoryTurnGateway()

        override fun transcript(): ChatTranscriptSource = channel

        override suspend fun editResubmit(
            sessionId: String,
            messageId: String,
            content: List<ContentPart>,
            clientMsgId: String,
        ): EditResubmitReceipt = writes.editResubmit(sessionId, messageId, content, clientMsgId)

        override suspend fun regenerate(
            sessionId: String,
            turnId: String,
            clientMsgId: String,
        ): RegenerateReceipt = writes.regenerate(sessionId, turnId, clientMsgId)

        override suspend fun submit(
            sessionId: String,
            parts: List<ContentPart>,
            clientMsgId: String,
        ): SubmitReceipt = writes.submit(sessionId, parts, clientMsgId)

        override suspend fun interrupt(sessionId: String, turnId: String) {
            writes.interrupt(sessionId, turnId)
        }
    }

    private fun channel(): ChatTranscriptSource =
        ChatTranscriptSource.of(history = { emptyList() }, events = { emptyFlow<VadeEvent>() })

    private fun realSourceChat(id: String): ChatState =
        ChatState(id, turns = ProbeGateway(channel()), drafts = DraftStore())

    private fun delta(seq: Long, turn: String, text: String): TurnDelta =
        TurnDelta(seq, coalesced = false, payload = TurnDeltaPayload(turn, text, 0, "text"))

    private fun completed(seq: Long, turn: String): TurnCompleted = TurnCompleted(
        seq,
        coalesced = false,
        payload = TurnCompletedPayload(
            turn,
            "completed",
            VadeUsage(1, 1, 2, null, null, "provider", 1, 0, 0),
            "stop",
        ),
    )

    // ------------------------------------------- 1 · 流式键（真实组合 + 计数器）

    @Test
    fun streamingDeltasNeitherRecomposeTheKeyReaderNorRestartTheTicker() = runComposeUiTest {
        val chat = realSourceChat("h2-stream-key")
        // 第一条 delta 自建一条 Running 的 live 行（turn.started 之前也照样算「在跑」——
        // `realRunningTurnId` 的兜底分支）。
        chat.applyLiveEvent(delta(1, "t-h2", "第一段"))
        assertTrue(chat.isStreaming, "前置条件：live 行正在流式")

        var keyReads = 0
        var tickerStarts = 0
        setContent {
            // 与 `StreamTicker` 逐字同一个读法 / 同一个键：先读键，再把它当 LaunchedEffect 的键。
            keyReads += 1
            val streaming = chat.streamingTickerKey
            LaunchedEffect(streaming) { tickerStarts += 1 }
            BasicText("streaming=$streaming")
        }

        waitForIdle()
        assertEquals(1, keyReads, "首帧：读键作用域组合一次")
        assertEquals(1, tickerStarts, "首帧：键为 true，ticker 起一次")

        // 每条 delta 写一次 `items`。旧键（`state.isStreaming`，真源上 `items.lastOrNull`）
        // 在这里**每一条**都让读键的作用域失效。
        repeat(12) { step ->
            runOnIdle { chat.applyLiveEvent(delta(2L + step, "t-h2", "第${step}段")) }
        }
        waitForIdle()

        assertEquals(1, keyReads, "delta 不许让读键的作用域重组（旧键每条 delta 一次）")
        assertEquals(1, tickerStarts, "delta 不许重建 ticker 协程（键一次都没变）")
        assertTrue(chat.streamingTickerKey, "delta 期间键必须一直是 true")

        // 回合收尾 —— 这是**唯一**该翻转的边沿（旧实现的循环在这里退出）。
        runOnIdle { chat.applyLiveEvent(completed(14, "t-h2")) }
        waitForIdle()

        assertEquals(false, chat.streamingTickerKey, "turn.completed 之后键必须翻转")
        assertEquals(2, keyReads, "边沿到了才允许重组一次")
        assertEquals(2, tickerStarts, "边沿到了才允许 ticker 重启一次")
    }

    // ------------------------------- 2 · 上传键（真实组合 + 计数器，进度 vs 数量）

    @Test
    fun uploadProgressWritesDoNotRecomposeTheChipKeyReaderButANewChipDoes() = runComposeUiTest {
        val chat = ChatState("h2-upload-key", drafts = DraftStore())
        chat.attach()
        chat.attach()
        assertEquals(2, chat.uploadTickerKey, "前置条件：两个「上传中」的 chip")

        var keyReads = 0
        setContent {
            // 与 `UploadTicker` 逐字同一个读法（只读派生 Int，不读 `staged` 本身）。
            keyReads += 1
            BasicText("chips=${chat.uploadTickerKey}")
        }

        waitForIdle()
        assertEquals(1, keyReads)

        // 每 160ms 一次的进度写入：`staged[i] = …`，**size 一个都不变**。
        // 旧键直接读 `staged.size` —— 列表的读在调用方作用域里，这些写入每条都让它失效。
        repeat(5) { runOnIdle { chat.tickUploads() } }
        waitForIdle()
        assertEquals(1, keyReads, "上传进度（size 不变）不许让键的读者重组")

        // chip 数量变了 —— 键必须通知一次（ticker 要开起来）。
        runOnIdle { chat.attach() }
        waitForIdle()
        assertEquals(3, chat.uploadTickerKey)
        assertTrue(keyReads > 1, "chip 数量变了必须让读者重组一次（否则新 chip 永远停在 0%）")
    }

    // ----------------------------- 3 · 键的发射序列（协程会被重建的次数，无窗口）

    /**
     * `LaunchedEffect` 只在 key 的**值**变化时 cancel + relaunch；这里数的是同一个表达式
     * 的发射次数 —— 也就是 ticker 协程被重建的次数。整条回合里它只该是 `true` / `false`
     * 两次，而不是「每条 delta 一次」。
     */
    @Test
    fun theStreamingKeyEmitsOncePerTurnNotPerDelta() = runTest {
        val chat = realSourceChat("h2-emit")

        val keyChanges = mutableListOf<Boolean>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            snapshotFlow { chat.streamingTickerKey }.collect { keyChanges += it }
        }
        fun flush() {
            Snapshot.sendApplyNotifications()
            runCurrent()
        }

        flush()
        chat.applyLiveEvent(delta(1, "t-h2", "a"))
        flush()
        repeat(8) { step ->
            chat.applyLiveEvent(delta(2L + step, "t-h2", "x"))
            flush()
        }
        chat.applyLiveEvent(completed(10, "t-h2"))
        flush()

        job.cancel()
        assertEquals(
            // snapshotFlow 先发射当前值 false，再是回合开始 true、结束 false。
            listOf(false, true, false),
            keyChanges,
            "键只许在「开始 / 结束」各翻一次；delta 一次都不许让它变",
        )
    }
}
