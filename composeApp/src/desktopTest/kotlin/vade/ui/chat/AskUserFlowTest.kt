package vade.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import vade.ui.UiText
import vade.ui.data.AdapterHost
import vade.ui.data.AdapterProvider
import vade.ui.data.FakeSessions
import vade.ui.data.SessionSource
import vade.ui.destinations.Destination
import vade.ui.meta.FakeMetaActions
import vade.ui.meta.SessionMetaActions
import vade.ui.model.ModelActions
import vade.ui.module.ModuleDeclaration
import vade.ui.module.ModulePageSource
import vade.ui.module.ModuleStatus
import vade.ui.protocol.InputKind
import vade.ui.protocol.InputOption
import vade.ui.protocol.InputSource
import vade.ui.protocol.InputValue
import vade.ui.protocol.ModelEntry
import vade.ui.protocol.TurnCancelled
import vade.ui.protocol.TurnCancelledPayload
import vade.ui.protocol.TurnFailed
import vade.ui.protocol.TurnFailedPayload
import vade.ui.protocol.TurnInputRequired
import vade.ui.protocol.TurnInputRequiredPayload
import vade.ui.protocol.TurnInputResolved
import vade.ui.protocol.TurnInputResolvedPayload
import vade.ui.protocol.TurnInterrupted
import vade.ui.protocol.TurnInterruptedPayload
import vade.ui.protocol.VadeEvent
import vade.ui.shell.AppShell
import vade.ui.shell.BackDispatcher
import vade.ui.shell.LocalBackDispatcher
import vade.ui.shell.UiState
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
 *
 * 第二轮（审查 P0/P1）补的是**生产路径**，不再只测孤立子树：
 *  - Esc 在真 `AppShell` 里取消问答而不是退出会话（[escapeInTheRealShellCancelsTheQuestion]）；
 *  - 手机宽度下顶栏在问答期间**可见且可点**（[thePhoneTopBarStaysClickableWhileTheQuestionIsUp]）；
 *  - `ui/attach` 快照的 `pending_inputs` 重建卡片（[pendingInputsFromTheAttachSnapshotRebuildTheDialog]）；
 *  - 回合终态清理与超时补发 cancel（`turnCancelled…` / `timingOutALiveCard…`）。
 */
@OptIn(ExperimentalTestApi::class)
class AskUserFlowTest {

    private companion object {
        /** 固定时钟：`expires_at` 的倒计时 / 超时判定才可复现。 */
        const val NOW = 1_000_000L
        const val SESSION = "s-ask"

        /** 真外壳用例里的会话：必须是 `FakeSessions.all` 里真实存在的一行。 */
        const val SHELL_SESSION = "s-rfc0004"

        /** 手机断点（<600dp）：抽屉模型 + 常驻底栏，正是审查 P0-2 出问题的形态。 */
        val PHONE_WIDTH = 400.dp
        val PHONE_HEIGHT = 900.dp

        /** 手机外壳的根节点（量/点坐标用；`onRoot()` 是测试宿主，不是这个 Box）。 */
        const val PHONE_ROOT_TAG = "test.phone.root"
    }

    /**
     * 真源读通道：没有消息；问答从测试喂进来的事件走（[send]），快照可以带
     * `pending_inputs`（[snapshotPending]）。
     *
     * `replay = 1` 让「先发事件、后订阅」也能收到（真实装配里订阅先于 attach，测试不必
     * 抢那一帧的时序）。
     */
    private class FakeTranscript(
        private val snapshotPending: List<TurnInputRequiredPayload> = emptyList(),
    ) : ChatTranscriptSource {
        private val live = MutableSharedFlow<VadeEvent>(replay = 1, extraBufferCapacity = 8)

        override suspend fun history(sessionId: String): List<ChatMessage> = emptyList()

        override suspend fun snapshot(sessionId: String): ChatHistory =
            ChatHistory(messages = emptyList(), pendingInputs = snapshotPending)

        override fun events(): Flow<VadeEvent> = live

        fun send(event: VadeEvent) {
            live.tryEmit(event)
        }
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

    /**
     * 能挂载适配器的源：`AppShell` 只认 `source as? AdapterHost`（假源故意不实现它）。
     * 于是这条用例里会话详情拿到的是**真** `ChatState` + 上面那个假传输层。
     */
    private class HostSource(private val provider: AdapterProvider) : SessionSource by FakeSessions, AdapterHost {
        override val adapters: AdapterProvider get() = provider
    }

    /** 只实现会话详情真正会碰的那几面：模型 / 元数据 / 模块页都退成无害的空实现。 */
    private class Provider(private val gateway: ChatTurnGateway) : AdapterProvider {
        private val models = object : ModelActions {
            override val models: List<ModelEntry>? = emptyList()
            override val defaultModelId: String? = null
            override fun sessionOverride(sessionId: String): String? = null
            override fun setSessionModel(sessionId: String, modelId: String?) = Unit
            override fun setDefaultModel(modelId: String?) = Unit
        }

        private val pages = object : ModulePageSource {
            override fun declarations(): List<ModuleDeclaration> = emptyList()
            override fun status(moduleId: String): ModuleStatus = ModuleStatus(online = false, version = null)
            override fun loadPage(moduleId: String, pageId: String): JsonObject? = null
            override fun loadState(moduleId: String, instanceId: String, pageId: String): JsonObject? = null
        }

        override fun meta(sessionId: String): SessionMetaActions = FakeMetaActions()
        override fun model(sessionId: String): ModelActions = models
        override fun modelDefault(): ModelActions = models
        override fun turns(sessionId: String): ChatTurnGateway = gateway
        override fun uploader(): AttachmentUploader = AttachmentUploader { _, _, _ -> "blb_test" }
        override fun modulePages(): ModulePageSource = pages
    }

    private fun chat(
        clock: () -> Long = { NOW },
        transcript: ChatTranscriptSource = FakeTranscript(),
    ): Pair<ChatState, InputGateway> {
        val gateway = InputGateway(transcript)
        // Unconfined：作答 / 取消都跑在 wireScope 上（生产是 Dispatchers.Default），
        // 注入 Unconfined 让 `turns.answerInput` / `cancelInput` 在 `submitInput()` /
        // `timeoutInput()` 返回前落地 —— 否则紧接着的 `gateway.answers` 断言是在跟
        // 后台协程赛跑（f8 返工新增的纯状态用例就是这样红的）。
        return ChatState(
            SESSION,
            turns = gateway,
            clock = clock,
            wireDispatcher = Dispatchers.Unconfined,
        ) to gateway
    }

    /** 真外壳 + 手机断点 + 生产同款返回栈（`App.kt` 组合根就是这么 provide 的）。 */
    private fun ComposeUiTest.phoneShell(ui: UiState, dispatcher: BackDispatcher) {
        setContent {
            CompositionLocalProvider(
                LocalSkin provides MaterialSkin(),
                LocalBackDispatcher provides dispatcher,
            ) {
                Box(Modifier.size(PHONE_WIDTH, PHONE_HEIGHT).testTag(PHONE_ROOT_TAG)) { AppShell(ui) }
            }
        }
    }

    private fun ComposeUiTest.nodeExists(tag: String): Boolean =
        runCatching { onNodeWithTag(tag).fetchSemanticsNode() }.isSuccess

    private fun shellState(gateway: ChatTurnGateway): UiState = UiState(
        initialBar = BottomBarStyle.Floating,
        initialSkinId = "material",
        initialDest = Destination.Sessions,
        source = HostSource(Provider(gateway)),
        loaderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    // --- 事件构造（字段一个不少：payload 的形状就是契约） --------------------------

    private fun option(id: String, label: String = id, description: String? = null) =
        InputOption(id = id, label = label, description = description)

    private fun required(
        inputId: String = "in-1",
        turnId: String = "t-1",
        kind: String = "free_text",
        prompt: String = "请给这个会话起个名字",
        detail: String? = null,
        options: List<InputOption> = emptyList(),
        timeoutS: Int? = 60,
        expiresAt: Long? = NOW + 60_000,
        seq: Long = 1,
    ): TurnInputRequired = TurnInputRequired(
        seq = seq,
        coalesced = false,
        payload = TurnInputRequiredPayload(
            inputId = inputId,
            turnId = turnId,
            sessionId = SESSION,
            state = "waiting_input",
            kind = kind,
            prompt = prompt,
            detail = detail,
            schema = JsonNull,
            options = options,
            timeoutS = timeoutS,
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

    private fun turnCancelled(turnId: String = "t-1", seq: Long = 90): TurnCancelled = TurnCancelled(
        seq = seq,
        coalesced = false,
        payload = TurnCancelledPayload(turnId = turnId, state = "cancelled", by = "device_id"),
    )

    private fun turnFailed(turnId: String = "t-1", seq: Long = 91): TurnFailed = TurnFailed(
        seq = seq,
        coalesced = false,
        payload = TurnFailedPayload(
            turnId = turnId,
            state = "failed",
            code = "UPSTREAM_ERROR",
            message = "boom",
            usage = null,
            data = null,
        ),
    )

    private fun turnInterrupted(turnId: String = "t-1", seq: Long = 92): TurnInterrupted = TurnInterrupted(
        seq = seq,
        coalesced = false,
        payload = TurnInterruptedPayload(
            turnId = turnId,
            state = "interrupted",
            by = "user",
            usage = null,
            partialMessageId = null,
            partialChars = null,
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
     *
     * 这一条只钉「卡片遮罩的相对层级」；真实外壳里的 zIndex 由
     * [thePhoneTopBarStaysClickableWhileTheQuestionIsUp] 在 `AppShell` 里钉。
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

    /**
     * 审查 P0-2 · **手机宽度**（400dp，真 `AppShell`）：问答卡在顶栏**之下**，顶栏的「返回」
     * 既可见又真的能点到（点击 = 滑出列表抽屉）。
     *
     * 旧实现把 `chat.hasPendingInput` 并进 `overlayOpen` ⇒ 整条内容 Row 拿到 `zIndex(1f)`，
     * 全屏遮罩盖在玻璃顶栏之上并吃掉它的点按。`performClick` 走真实命中测试：遮罩在上面
     * 就点不到按钮、`sessionsDrawerOpen` 不会翻 —— 所以这条用例把那个回归钉死。
     */
    @Test
    fun thePhoneTopBarStaysClickableWhileTheQuestionIsUp() = runComposeUiTest {
        val transcript = FakeTranscript()
        val gateway = InputGateway(transcript)
        val ui = shellState(gateway)
        phoneShell(ui, BackDispatcher())

        waitUntil(timeoutMillis = 10_000) { ui.sessionRows.isNotEmpty() }
        runOnIdle { ui.open(SHELL_SESSION) }
        waitUntil(timeoutMillis = 10_000) { nodeExists(ComposerOverlayTag) }

        runOnIdle { transcript.send(required()) }
        waitUntil(timeoutMillis = 10_000) { nodeExists(AskUserDialogTag) }
        assertEquals(SHELL_SESSION, ui.selectedSessionId, "前置条件：还在会话详情里")

        onNodeWithContentDescription("返回").assertExists()
        onNodeWithContentDescription("返回").performClick()
        waitForIdle()

        assertTrue(
            ui.sessionsDrawerOpen,
            "问答卡不得遮挡/禁用顶栏：遮罩盖住顶栏时这一记点击会被它吞掉（审查 P0-2）",
        )
        assertEquals(SHELL_SESSION, ui.selectedSessionId, "手机返回 = 滑出列表，选中保留")
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
        waitForIdle()
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
        waitForIdle()
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

    // --- 4. 倒计时 / 超时 → 关窗 + 提示 + 幂等 cancel ----------------------------

    /**
     * 审查 P1-3 · 本地超时**也要**发 `turn/input.cancel`（reason = `timeout`）。
     *
     * 只本地摘卡时，core 仍认为问题开着（它自己的 60–600s 超时还没到），期间回合不可答：
     * 读者每次提交都被 core 拒绝，而屏上连卡都没有。这一帧是幂等的 —— core 已经关掉这张卡
     * 时按 `ok=false` 静默（`TurnGatewayReal.cancelInput`）。
     *
     * 时钟用可变变量：卡按 `expires_at` 立起来，再把本机时钟推过期限，等 UI 自己摘卡。
     */
    @Test
    fun timingOutALiveCardAlsoSendsAnIdempotentCancel() = runComposeUiTest {
        var now = NOW
        val (chat, gateway) = chat(clock = { now })
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                ChatThread(state = chat, animate = false)
            }
        }

        chat.applyLiveEvent(required(expiresAt = NOW + 60_000))
        waitForIdle()
        onNodeWithTag(AskUserDialogTag).assertExists()

        now = NOW + 61_000
        waitUntil(timeoutMillis = 5_000) { chat.pendingInputs.isEmpty() }
        waitForIdle()

        // 超时 = 未作答：不发任何**答复**帧；只补一发 cancel 让 core 提前收尾。
        assertEquals(0, gateway.answers.size)
        assertEquals(
            listOf(CancelCall(SESSION, "in-1", ChatState.INPUT_TIMEOUT_CANCEL_REASON)),
            gateway.cancels,
        )
        assertEquals(ChatState.INPUT_TIMEOUT_NOTICE, chat.lastNotice)
        onNodeWithTag(AskUserDialogTag).assertDoesNotExist()
        onNodeWithText(ChatState.INPUT_TIMEOUT_NOTICE).assertExists()
    }

    /**
     * 审查 P1-3 的另一半 · `expires_at` 已落在本机时钟的过去 ⇒ 判定为**时钟偏差**，
     * 改用相对预算（`timeout_s` 从「本机看到它」起算），而不是一到就判死。
     */
    @Test
    fun aCoreDeadlineAlreadyInThePastIsTreatedAsClockSkew() {
        val (chat, _) = chat(clock = { NOW })
        chat.applyLiveEvent(required(expiresAt = NOW - 5_000, timeoutS = 60))

        assertNotNull(chat.currentInput, "时钟偏差不许让卡在到达那一帧就消失")
        assertEquals(60, chat.currentInput?.remainingSeconds(NOW))
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

    /** `expires_at` 缺失时退到 `created_at + timeout_s`（两者都是可选字段）。 */
    @Test
    fun aMissingExpiryFallsBackToCreatedAtPlusTimeoutS() {
        val (chat, _) = chat(clock = { NOW })
        chat.applyLiveEvent(required(timeoutS = 30, expiresAt = null))

        assertEquals(30, chat.currentInput?.remainingSeconds(NOW), "created_at + timeout_s 起算")
        assertEquals(true, chat.currentInput?.isExpired(NOW + 30_000))
    }

    // --- 5. 键盘：Esc 取消 / 自由文本 ⌘Enter 提交 --------------------------------

    /**
     * 卡片自己的 preview 兜底路径（返回栈里没有别的层时生效，例如组件预览）。
     * 真实外壳里的那条路由 [escapeInTheRealShellCancelsTheQuestion] 钉。
     */
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

    /**
     * 审查 P0-1 · **真 `AppShell`** 里 Esc 必须取消问答，不许退出会话。
     *
     * `AppShell` 根部挂着一个 `onPreviewKeyEvent`，Compose 的 preview 是**根 → 焦点**的
     * 隧道 ⇒ 根上的 handler 一定早于卡片自己的 preview。它把 Esc 先交给 `BackDispatcher`：
     * 手机详情态恒有一层「返回 = 滑出列表」，于是「按 Esc」= 退出会话，问题随后再也拿不到。
     * 旧用例只组合 `ChatThread`（树里没有那个根 handler），断言的行为在生产里不可能发生。
     *
     * 这里断言四件事：cancel 帧发出、卡消失、**仍在会话里**、返回栈没有被动过（栈深回到 1）。
     */
    @Test
    fun escapeInTheRealShellCancelsTheQuestion() = runComposeUiTest {
        val transcript = FakeTranscript()
        val gateway = InputGateway(transcript)
        val ui = shellState(gateway)
        val dispatcher = BackDispatcher()
        phoneShell(ui, dispatcher)

        waitUntil(timeoutMillis = 10_000) { ui.sessionRows.isNotEmpty() }
        runOnIdle { ui.open(SHELL_SESSION) }
        waitUntil(timeoutMillis = 10_000) { nodeExists(ComposerOverlayTag) }

        runOnIdle { transcript.send(required()) }
        waitUntil(timeoutMillis = 10_000) { nodeExists(AskUserDialogTag) }
        // 前置条件：卡真的登记了一层（栈 = 详情 + 问答卡）。没有这一层，Esc 就是退出会话。
        waitUntil(timeoutMillis = 5_000) { dispatcher.depth == 2 }
        assertEquals(2, dispatcher.depth, "问答卡必须是返回栈的栈顶（详情 1 + 卡 1）")

        onNodeWithTag(AskUserInputFieldTag).performTextInput("还没写完")
        onNodeWithTag(AskUserInputFieldTag).performKeyInput { pressKey(Key.Escape) }
        waitUntil(timeoutMillis = 5_000) { gateway.cancels.isNotEmpty() }
        waitForIdle()

        assertEquals(
            listOf(CancelCall(SHELL_SESSION, "in-1", ChatState.INPUT_CANCEL_REASON)),
            gateway.cancels,
        )
        onNodeWithTag(AskUserDialogTag).assertDoesNotExist()
        assertEquals(SHELL_SESSION, ui.selectedSessionId, "Esc 只取消问答：不许退出会话")
        assertFalse(ui.sessionsDrawerOpen, "Esc 也不许滑出列表抽屉")
        waitUntil(timeoutMillis = 5_000) { dispatcher.depth == 1 }
        assertEquals(1, dispatcher.depth, "卡退出组合后它的返回层必须出栈")
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

    // --- 6. 快照恢复（重进 / 重连） ------------------------------------------------

    /**
     * 审查 P1-1 · `ui/attach` 快照里的 `pending_inputs` 必须在 `loadHistory` 里落地，
     * 否则重进会话 / 断线重连之后卡永久消失（回合卡在 `waiting_input`）。
     */
    @Test
    fun pendingInputsFromTheAttachSnapshotRebuildTheDialog() = runComposeUiTest {
        val pending = required(inputId = "in-snap", prompt = "断线前问的问题").payload
        val transcript = FakeTranscript(snapshotPending = listOf(pending))
        val gateway = InputGateway(transcript)
        val chat = ChatState(
            SESSION,
            turns = gateway,
            clock = { NOW },
            wireDispatcher = Dispatchers.Unconfined,
        )

        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                ChatThread(state = chat, animate = false)
            }
        }

        waitUntil(timeoutMillis = 5_000) { chat.currentInput?.inputId == "in-snap" }
        onNodeWithTag(AskUserDialogTag).assertExists()
        onNodeWithText("断线前问的问题").assertExists()
        assertEquals(ChatPhase.Ready, chat.phase)
    }

    /** 快照按 `input_id` 幂等：已经在屏上的卡不会被第二份快照叠成两张。 */
    @Test
    fun aSecondSnapshotDoesNotStackASecondDialog() {
        val pending = required(inputId = "in-snap").payload
        val transcript = FakeTranscript(snapshotPending = listOf(pending))
        val gateway = InputGateway(transcript)
        val chat = ChatState(
            SESSION,
            turns = gateway,
            clock = { NOW },
            wireDispatcher = Dispatchers.Unconfined,
        )

        runBlocking {
            chat.loadHistory()
            chat.loadHistory()
        }
        assertEquals(listOf("in-snap"), chat.pendingInputs.map { it.inputId })
    }

    /**
     * 迟到的快照不许**复活**一张本地已经收尾的卡：快照是异步读的，读的过程中
     * `turn.input_resolved` 可能已经先到（快照点更早，里面还留着它）。
     */
    @Test
    fun aLateSnapshotDoesNotResurrectAnAnsweredInput() {
        val pending = required(inputId = "in-1").payload
        val transcript = FakeTranscript(snapshotPending = listOf(pending))
        val gateway = InputGateway(transcript)
        val chat = ChatState(
            SESSION,
            turns = gateway,
            clock = { NOW },
            wireDispatcher = Dispatchers.Unconfined,
        )

        chat.applyLiveEvent(required(inputId = "in-1"))
        chat.applyLiveEvent(resolved(inputId = "in-1"))
        assertTrue(chat.pendingInputs.isEmpty())

        runBlocking { chat.loadHistory() }
        assertTrue(chat.pendingInputs.isEmpty(), "resolved 过的 input_id 必须挡住迟到的快照重建")
    }

    /**
     * 快照里**真的已经过期**的卡（离线期间超时）不该留在屏上：立刻按超时收尾，并补发
     * 幂等 cancel。与「实时事件 + 时钟偏差」那条
     * ([aCoreDeadlineAlreadyInThePastIsTreatedAsClockSkew]) 是两份口径：快照可能比本机
     * 时钟旧得多，不能一律当成时钟偏差。
     */
    @Test
    fun anExpiredCardInsideTheSnapshotIsClosedAndCancelled() {
        val pending = required(expiresAt = NOW - 1_000).payload
        val transcript = FakeTranscript(snapshotPending = listOf(pending))
        val gateway = InputGateway(transcript)
        val chat = ChatState(
            SESSION,
            turns = gateway,
            clock = { NOW },
            wireDispatcher = Dispatchers.Unconfined,
        )

        runBlocking { chat.loadHistory() }

        assertTrue(chat.pendingInputs.isEmpty())
        assertEquals(
            listOf(CancelCall(SESSION, "in-1", ChatState.INPUT_TIMEOUT_CANCEL_REASON)),
            gateway.cancels,
        )
        assertEquals(ChatState.INPUT_TIMEOUT_NOTICE, chat.lastNotice)
    }

    // --- 7. 回合终态 → 清掉该回合还开着的卡 ---------------------------------------

    @Test
    fun turnCancelledDropsThePendingQuestionForThatTurn() {
        val (chat, _) = chat()
        chat.applyLiveEvent(required(inputId = "in-1"))
        chat.applyLiveEvent(required(inputId = "in-2", seq = 2))
        assertEquals(2, chat.pendingInputs.size)

        chat.applyLiveEvent(turnCancelled(turnId = "t-1", seq = 3))

        assertTrue(chat.pendingInputs.isEmpty(), "回合被取消：该回合的卡必须一起收起")
        assertEquals(ChatState.INPUT_TURN_ENDED_NOTICE, chat.lastNotice)
    }

    @Test
    fun turnFailedDropsThePendingQuestionForThatTurn() {
        val (chat, _) = chat()
        chat.applyLiveEvent(required(inputId = "in-1"))

        chat.applyLiveEvent(turnFailed(turnId = "t-1", seq = 2))

        assertTrue(chat.pendingInputs.isEmpty())
        assertEquals(ChatState.INPUT_TURN_ENDED_NOTICE, chat.lastNotice)
    }

    @Test
    fun turnInterruptedDropsThePendingQuestionForThatTurn() {
        val (chat, _) = chat()
        chat.applyLiveEvent(required(inputId = "in-1"))

        chat.applyLiveEvent(turnInterrupted(turnId = "t-1", seq = 2))

        assertTrue(chat.pendingInputs.isEmpty())
        assertEquals(ChatState.INPUT_TURN_ENDED_NOTICE, chat.lastNotice)
    }

    /** 只清**那一个回合**：同一会话里别的回合还等着回答的卡不许被连带收掉。 */
    @Test
    fun aTerminalEventOnlyDropsItsOwnTurnsInputs() {
        val (chat, _) = chat()
        chat.applyLiveEvent(required(inputId = "in-1", turnId = "t-1", seq = 1))
        chat.applyLiveEvent(required(inputId = "in-2", turnId = "t-2", seq = 2))

        chat.applyLiveEvent(turnCancelled(turnId = "t-1", seq = 3))

        assertEquals(listOf("in-2"), chat.pendingInputs.map { it.inputId })
    }
}
