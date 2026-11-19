package vade.ui.chat

import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.flow.emptyFlow
import vade.ui.protocol.TurnCompleted
import vade.ui.protocol.TurnCompletedPayload
import vade.ui.protocol.TurnDelta
import vade.ui.protocol.TurnDeltaPayload
import vade.ui.protocol.TurnFailed
import vade.ui.protocol.TurnFailedPayload
import vade.ui.protocol.TurnInterrupted
import vade.ui.protocol.TurnInterruptedPayload
import vade.ui.protocol.TurnStarted
import vade.ui.protocol.TurnStartedPayload
import vade.ui.protocol.VadeEvent
import vade.ui.protocol.VadeUsage
import vade.ui.skin.InputMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R3 · P0 —— 回合的生命周期**边沿**必须真的传达给组合。
 *
 * 事故形态：`ChatState.runningTurnId` 早先是普通 `var`，而它经
 * `isStreaming` 被两个 `derivedStateOf`（`inputMode` / `streamingTickerKey`）读取。
 * 派生状态观察不到普通字段，只能指望「同一事件顺带写了 `items`」来重算 —— 而
 * `turn.started` 对投影器是刻意的 no-op（`LiveTranscript.project` 里 started 一行都不改），
 * 于是：
 *
 *  - 回合真的开始了，`inputMode` 却仍停在 **Idle**：composer 画的是 ↑（发送）而不是 ■（停止），
 *    用户既停不下来，再点一次还会把下一条消息排到队里；
 *  - 收尾事件若没有可见行变化，界面永久停在 Streaming（同一个根因的另一半）。
 *
 * 本类用**真实组合**（`BasicText` 读 `state.inputMode`）钉住这两条边沿，而不是直接读属性 ——
 * 属性读每次都会重算，正好绕开「派生状态没被通知」这个 bug（那正是它一直没被发现的原因）。
 */
@OptIn(ExperimentalTestApi::class)
class R3InputModeTest {

    /** 真通道替身：`synthetic` 走接口默认值（false），读能力由构造参数给出。 */
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

    private fun realSourceChat(id: String): ChatState {
        val channel = ChatTranscriptSource.of(history = { emptyList() }, events = { emptyFlow<VadeEvent>() })
        return ChatState(id, turns = ProbeGateway(channel), drafts = DraftStore())
    }

    // --- 事件构造（seq 必须连续：LiveTranscript 按 gap 裁决） ---------------------

    private fun started(seq: Long, turn: String): TurnStarted =
        TurnStarted(seq, coalesced = false, payload = TurnStartedPayload(turn, "running", null, null))

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

    private fun interrupted(seq: Long, turn: String): TurnInterrupted = TurnInterrupted(
        seq,
        coalesced = false,
        payload = TurnInterruptedPayload(
            turnId = turn,
            state = "interrupted",
            by = "user",
            usage = null,
            partialMessageId = null,
            partialChars = 3,
        ),
    )

    private fun failed(seq: Long, turn: String): TurnFailed = TurnFailed(
        seq,
        coalesced = false,
        payload = TurnFailedPayload(
            turnId = turn,
            state = "failed",
            code = "UNAVAILABLE",
            message = "model unreachable",
            usage = null,
            data = null,
        ),
    )

    // ------------------------------------------------------------------ 用例

    @Test
    fun startedDeltaCompletedMovesInputModeFromIdleToStreamingAndBack() = runComposeUiTest {
        val chat = realSourceChat("r3-mode-completed")
        assertTrue(chat.isRealSource, "前置条件：真源模式（`runningTurnId` 那条路径才存在）")

        setContent { BasicText("mode=${chat.inputMode}") }
        waitForIdle()
        onNodeWithText("mode=${InputMode.Idle}").assertExists()

        // ① 回合开始：只有 `turn.started`，没有任何 delta（items 一个字节都不动）。
        //    修复前：派生状态没被通知，界面停在 Idle —— 这条断言就是回归钉。
        runOnIdle { chat.applyLiveEvent(started(1, "t-r3")) }
        waitForIdle()
        onNodeWithText("mode=${InputMode.Streaming}")
            .assertExists()
        assertTrue(chat.isStreaming, "回合真的在跑")

        // ② delta：仍然是 Streaming（不许在流中途抖回 Idle）。
        runOnIdle { chat.applyLiveEvent(delta(2, "t-r3", "第一段")) }
        waitForIdle()
        onNodeWithText("mode=${InputMode.Streaming}").assertExists()

        // ③ 完成：回到 Idle —— 与开始同一个边沿，同一个真源。
        runOnIdle { chat.applyLiveEvent(completed(3, "t-r3")) }
        waitForIdle()
        onNodeWithText("mode=${InputMode.Idle}").assertExists()
        assertFalse(chat.isStreaming, "turn.completed 之后 isStreaming 必须为 false")
    }

    @Test
    fun interruptedAndFailedTurnsAlsoReturnToIdle() = runComposeUiTest {
        val chat = realSourceChat("r3-mode-terminal")

        setContent { BasicText("mode=${chat.inputMode}") }
        waitForIdle()
        onNodeWithText("mode=${InputMode.Idle}").assertExists()

        // 中断：已生成的部分留着，但「在跑」必须收掉（否则 ■ 永远停不下来）。
        runOnIdle { chat.applyLiveEvent(started(1, "t-a")) }
        waitForIdle()
        runOnIdle { chat.applyLiveEvent(delta(2, "t-a", "半句")) }
        waitForIdle()
        onNodeWithText("mode=${InputMode.Streaming}").assertExists()
        runOnIdle { chat.applyLiveEvent(interrupted(3, "t-a")) }
        waitForIdle()
        onNodeWithText("mode=${InputMode.Idle}").assertExists()
        assertFalse(chat.isStreaming, "turn.interrupted 之后 isStreaming 必须为 false")

        // 失败：同理（失败是终态，不是「还在生成」）。
        runOnIdle { chat.applyLiveEvent(started(4, "t-b")) }
        waitForIdle()
        onNodeWithText("mode=${InputMode.Streaming}").assertExists()
        runOnIdle { chat.applyLiveEvent(failed(5, "t-b")) }
        waitForIdle()
        onNodeWithText("mode=${InputMode.Idle}").assertExists()
        assertFalse(chat.isStreaming, "turn.failed 之后 isStreaming 必须为 false")

        // 收尾之后不许有任何残留（失败行本身还在时间线上，只是不再是「在跑」）。
        assertEquals(
            0,
            chat.liveAnomalies.size,
            "整串事件的 seq 连续，不该有任何缺口判决：${chat.liveAnomalies}",
        )
    }
}
