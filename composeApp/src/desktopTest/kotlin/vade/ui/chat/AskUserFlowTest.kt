package vade.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonNull
import vade.ui.UiText
import vade.ui.protocol.InputKind
import vade.ui.protocol.InputOption
import vade.ui.protocol.InputSource
import vade.ui.protocol.InputValue
import vade.ui.protocol.TurnInputRequired
import vade.ui.protocol.TurnInputRequiredPayload
import vade.ui.protocol.TurnInputResolved
import vade.ui.protocol.TurnInputResolvedPayload
import vade.ui.protocol.VadeEvent
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RFC-0008 · ask-user 的客户端闭环（F8）。
 *
 * 覆盖用户要求的五条验收：事件 → 弹框、提交 → `turn/input` 的 value 正确、`resolved` →
 * 关对应 `input_id`、超时 → 关窗 + 提示、多选 → 一次带上全部选中 id。另加键盘策略
 * （Esc 取消 / 自由文本 ⌘Enter）与 confirm 两键的布尔值。
 *
 * 全部走**真状态代码**（[ChatState] + [ChatThread]），假通道只替换传输层：事件是真实的
 * `TurnInputRequired` / `TurnInputResolved` 对象，答复由 [InputGateway] 记录成帧参数。
 */
@OptIn(ExperimentalTestApi::class)
class AskUserFlowTest {

    private companion object {
        /** 固定时钟：`expires_at` 的倒计时 / 超时判定才可复现。 */
        const val NOW = 1_000_000L
        const val SESSION = "s-ask"
    }

    /** 真源读通道：没有消息，也没有额外事件（问答只从测试喂进来的事件走）。 */
    private class FakeTranscript : ChatTranscriptSource {
        override suspend fun history(sessionId: String): List<ChatMessage> = emptyList()
        override fun events(): Flow<VadeEvent> = emptyFlow()
    }

    private data class AnswerCall(val sessionId: String, val inputId: String, val value: InputValue)

    private data class CancelCall(val sessionId: String, val inputId: String, val reason: String)

    /**
     * 真通道（`synthetic = false` → [ChatState.isRealSource] 成立，事件投影才会跑）+
     * 记录 `turn/input` / `turn/input.cancel` 的每一帧。写能力以外的方法委托给内存假实现，
     * 免得它们在默认实现上响亮失败。
     */
    private class InputGateway(private val channel: ChatTranscriptSource) : ChatTurnGateway {
        private val writes = InMemoryTurnGateway()
        val answers = mutableListOf<AnswerCall>()
        val cancels = mutableListOf<CancelCall>()

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

        override suspend fun answerInput(sessionId: String, inputId: String, value: InputValue) {
            answers += AnswerCall(sessionId, inputId, value)
        }

        override suspend fun cancelInput(sessionId: String, inputId: String, reason: String) {
            cancels += CancelCall(sessionId, inputId, reason)
        }
    }

    private fun chat(clock: () -> Long = { NOW }): Pair<ChatState, InputGateway> {
        val gateway = InputGateway(FakeTranscript())
        return ChatState(SESSION, turns = gateway, clock = clock) to gateway
    }

    // --- 事件构造（字段一个不少：payload 的形状就是契约） --------------------------

    private fun option(id: String, label: String = id, description: String? = null) =
        InputOption(id = id, label = label, description = description)

    private fun required(
        inputId: String = "in-1",
        kind: String = "free_text",
        prompt: String = "请给这个会话起个名字",
        detail: String? = null,
        options: List<InputOption> = emptyList(),
        expiresAt: Long? = NOW + 60_000,
        seq: Long = 1,
    ): TurnInputRequired = TurnInputRequired(
        seq = seq,
        coalesced = false,
        payload = TurnInputRequiredPayload(
            inputId = inputId,
            turnId = "t-1",
            sessionId = SESSION,
            state = "waiting_input",
            kind = kind,
            prompt = prompt,
            detail = detail,
            schema = JsonNull,
            options = options,
            timeoutS = 60,
            expiresAt = expiresAt,
            default = JsonNull,
            source = InputSource(tool = "ask_user", moduleId = null, depth = 0),
            createdAt = NOW,
        ),
    )

    private fun resolved(
        inputId: String = "in-1",
        status: String = "submitted",
        seq: Long = 99,
    ): TurnInputResolved = TurnInputResolved(
        seq = seq,
        coalesced = false,
        payload = TurnInputResolvedPayload(
            inputId = inputId,
            turnId = "t-1",
            state = "running",
            status = status,
            value = JsonNull,
            by = "device_id",
            reason = status,
            durationMs = 1200.0,
        ),
    )

    // --- 1. 事件 → 弹框 ----------------------------------------------------------

    @Test
    fun inputRequiredOpensTheModalDialogWithPrompt() = runComposeUiTest {
        val (chat, _) = chat()
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                ChatThread(state = chat, animate = false)
            }
        }

        chat.applyLiveEvent(required(prompt = "请给这个会话起个名字"))
        waitForIdle()

        onNodeWithTag(AskUserDialogTag).assertExists()
        onNodeWithText("请给这个会话起个名字").assertExists()
        onNodeWithText("需要你的回答").assertExists()
        onNodeWithText("提交").assertExists()
        assertEquals(1, chat.pendingInputs.size)
        assertEquals("in-1", chat.currentInput?.inputId)
    }

    @Test
    fun duplicateInputRequiredFramesDoNotStackTwoDialogs() = runComposeUiTest {
        val (chat, _) = chat()
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                ChatThread(state = chat, animate = false)
            }
        }

        chat.applyLiveEvent(required())
        chat.applyLiveEvent(required(seq = 2))
        waitForIdle()

        assertEquals(1, chat.pendingInputs.size)
        onNodeWithTag(AskUserDialogTag).assertExists()
    }

    /**
     * 任务硬条件：问答卡**不遮挡顶栏**。它住在本屏层叠里，表头是画在它之后的兄弟 ——
     * 顶栏不仅可见，而且还能被点到（命中表头而不是遮罩）。
     */
    @Test
    fun theModalLeavesTheHeaderSiblingClickable() = runComposeUiTest {
        val (chat, _) = chat()
        var headerClicks = 0
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                Box(Modifier.fillMaxSize()) {
                    ChatThread(state = chat, animate = false, modifier = Modifier.fillMaxSize())
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("test.header")
                            .clickable { headerClicks += 1 },
                    ) {
                        UiText("会话标题")
                    }
                }
            }
        }

        chat.applyLiveEvent(required())
        waitForIdle()
        onNodeWithTag(AskUserDialogTag).assertExists()
        onNodeWithText("会话标题").assertExists()
        onNodeWithTag("test.header").performClick()
        assertEquals(1, headerClicks, "问答遮罩不许盖住顶栏的点击")
    }

    // --- 2. 提交 → RPC 的 value 正确 ----------------------------------------------

    @Test
    fun submittingFreeTextSendsTheRpcValueAndCloses() = runComposeUiTest {
        val (chat, gateway) = chat()
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                ChatThread(state = chat, animate = false)
            }
        }

        chat.applyLiveEvent(required())
        waitForIdle()
        onNodeWithTag(AskUserInputFieldTag).performTextInput("航海日志")
        onNodeWithText("提交").performClick()
        waitForIdle()

        assertEquals(listOf(AnswerCall(SESSION, "in-1", InputValue.Text("航海日志"))), gateway.answers)
        assertTrue(chat.pendingInputs.isEmpty())
        onNodeWithTag(AskUserDialogTag).assertDoesNotExist()
    }

    @Test
    fun emptyFreeTextCannotBeSubmitted() {
        val (chat, gateway) = chat()
        chat.applyLiveEvent(required())
        assertEquals(false, chat.canSubmitInput("in-1"))
        assertFalse(chat.submitInput("in-1"))
        assertEquals(0, gateway.answers.size)
        assertEquals("回答不能为空", chat.lastNotice)
    }

    @Test
    fun singleChoiceSendsOnlyTheLastPickedOption() {
        val (chat, gateway) = chat()
        chat.applyLiveEvent(
            required(
                kind = "single_choice",
                options = listOf(option("a"), option("b"), option("c")),
            ),
        )
        chat.toggleInputOption("in-1", "b")
        chat.toggleInputOption("in-1", "c")
        assertEquals(listOf("c"), chat.currentInput?.selected)

        assertTrue(chat.submitInput("in-1"))
        assertEquals(listOf(AnswerCall(SESSION, "in-1", InputValue.Text("c"))), gateway.answers)
    }

    @Test
    fun multiChoiceSendsEverySelectedIdInDisplayOrder() {
        val (chat, gateway) = chat()
        chat.applyLiveEvent(
            required(
                kind = "multi_choice",
                options = listOf(option("a"), option("b"), option("c")),
            ),
        )
        // 点击顺序 c → a：上帧仍按选项显示顺序 [a, c]，同一组勾选只有一种帧。
        chat.toggleInputOption("in-1", "c")
        chat.toggleInputOption("in-1", "a")

        assertTrue(chat.submitInput("in-1"))
        assertEquals(
            listOf(AnswerCall(SESSION, "in-1", InputValue.Multi(listOf("a", "c")))),
            gateway.answers,
        )
    }

    @Test
    fun multiChoiceUiTogglesOptionsAndSubmitsThem() = runComposeUiTest {
        val (chat, gateway) = chat()
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                ChatThread(state = chat, animate = false)
            }
        }
        chat.applyLiveEvent(
            required(
                kind = "multi_choice",
                options = listOf(option("a", "甲"), option("b", "乙"), option("c", "丙")),
            ),
        )
        waitForIdle()

        onNodeWithTag(askUserOptionTag("c")).performClick()
        onNodeWithTag(askUserOptionTag("a")).performClick()
        onNodeWithText("提交").performClick()
        waitForIdle()

        assertEquals(
            listOf(AnswerCall(SESSION, "in-1", InputValue.Multi(listOf("a", "c")))),
            gateway.answers,
        )
        onNodeWithTag(AskUserDialogTag).assertDoesNotExist()
    }

    @Test
    fun confirmSendsABooleanFromEitherKey() {
        val (chat, gateway) = chat()
        chat.applyLiveEvent(required(kind = "confirm"))
        assertTrue(chat.submitConfirm("in-1", true))

        chat.applyLiveEvent(required(inputId = "in-2", kind = "confirm", seq = 2))
        assertTrue(chat.submitConfirm("in-2", false))

        assertEquals(
            listOf(
                AnswerCall(SESSION, "in-1", InputValue.Flag(true)),
                AnswerCall(SESSION, "in-2", InputValue.Flag(false)),
            ),
            gateway.answers,
        )
    }

    // --- 3. turn.input_resolved → 只关对应的 input_id -----------------------------

    @Test
    fun resolvedClosesOnlyThatInput() = runComposeUiTest {
        val (chat, _) = chat()
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                ChatThread(state = chat, animate = false)
            }
        }
        chat.applyLiveEvent(required(inputId = "in-1", seq = 1))
        chat.applyLiveEvent(required(inputId = "in-2", seq = 2))
        waitForIdle()
        assertEquals(listOf("in-1", "in-2"), chat.pendingInputs.map { it.inputId })

        chat.applyLiveEvent(resolved(inputId = "in-2", status = "cancelled", seq = 3))
        waitForIdle()
        assertEquals(listOf("in-1"), chat.pendingInputs.map { it.inputId })
        onNodeWithTag(AskUserDialogTag).assertExists()

        chat.applyLiveEvent(resolved(inputId = "in-1", status = "submitted", seq = 4))
        waitForIdle()
        assertTrue(chat.pendingInputs.isEmpty())
        onNodeWithTag(AskUserDialogTag).assertDoesNotExist()
    }

    @Test
    fun resolvedForAnUnknownInputIsANoOp() {
        val (chat, _) = chat()
        chat.applyLiveEvent(required(inputId = "in-1"))
        chat.applyLiveEvent(resolved(inputId = "in-other", seq = 2))
        assertEquals(listOf("in-1"), chat.pendingInputs.map { it.inputId })
    }

    // --- 4. 倒计时 / 超时 → 关窗 + 提示 -------------------------------------------

    @Test
    fun expiredInputClosesItselfAndSaysTheTurnContinues() = runComposeUiTest {
        val (chat, gateway) = chat(clock = { NOW })
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                ChatThread(state = chat, animate = false)
            }
        }

        chat.applyLiveEvent(required(expiresAt = NOW - 1))
        waitUntil(timeoutMillis = 5_000) { chat.lastNotice == ChatState.INPUT_TIMEOUT_NOTICE }
        waitForIdle()

        assertTrue(chat.pendingInputs.isEmpty())
        // 超时 = 未作答：不发任何答复帧（default 永远不会变成答案）。
        assertEquals(0, gateway.answers.size)
        onNodeWithTag(AskUserDialogTag).assertDoesNotExist()
        onNodeWithText(ChatState.INPUT_TIMEOUT_NOTICE).assertExists()
    }

    @Test
    fun countdownReadsExpiresAt() {
        val (chat, _) = chat(clock = { NOW })
        chat.applyLiveEvent(required(expiresAt = NOW + 12_400))
        // 12.4s 向上取整 = 13s（倒计时只认 expires_at，不认 timeout_s）。
        assertEquals(13, chat.currentInput?.remainingSeconds(chat.nowMs()))
        assertEquals(1, chat.currentInput?.remainingSeconds(NOW + 11_500))
        assertEquals(0, chat.currentInput?.remainingSeconds(NOW + 12_500))
    }

    // --- 5. 键盘：Esc 取消 / 自由文本 ⌘Enter 提交 --------------------------------

    @Test
    fun escapeCancelsFromInsideTheTextField() = runComposeUiTest {
        val (chat, gateway) = chat()
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                ChatThread(state = chat, animate = false)
            }
        }
        chat.applyLiveEvent(required())
        waitForIdle()

        onNodeWithTag(AskUserInputFieldTag).performTextInput("还没写完")
        onNodeWithTag(AskUserInputFieldTag).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()

        assertEquals(listOf(CancelCall(SESSION, "in-1", ChatState.INPUT_CANCEL_REASON)), gateway.cancels)
        assertTrue(chat.pendingInputs.isEmpty())
        onNodeWithTag(AskUserDialogTag).assertDoesNotExist()
    }

    @Test
    fun keyPolicyMatchesTheFourKinds() {
        // Esc 永远取消（焦点在卡片或任何子节点）。
        assertEquals(InputKeyAction.Cancel, inputKeyAction(InputKind.FREE_TEXT, escape = true, enter = false, primary = false))
        // 自由文本：裸 Enter 是换行，只有 ⌘/Ctrl+Enter 才是提交。
        assertEquals(InputKeyAction.None, inputKeyAction(InputKind.FREE_TEXT, escape = false, enter = true, primary = false))
        assertEquals(InputKeyAction.Submit, inputKeyAction(InputKind.FREE_TEXT, escape = false, enter = true, primary = true))
        // 选择类：Enter 提交。
        assertEquals(InputKeyAction.Submit, inputKeyAction(InputKind.SINGLE_CHOICE, escape = false, enter = true, primary = false))
        assertEquals(InputKeyAction.Submit, inputKeyAction(InputKind.MULTI_CHOICE, escape = false, enter = true, primary = true))
        // confirm 的方向不能靠猜：Enter 不绑定，两键自己说。
        assertEquals(InputKeyAction.None, inputKeyAction(InputKind.CONFIRM, escape = false, enter = true, primary = false))
    }
}
