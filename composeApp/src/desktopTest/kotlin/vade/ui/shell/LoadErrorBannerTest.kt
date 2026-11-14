package vade.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import vade.ui.chat.ChatMessage
import vade.ui.chat.ChatPreset
import vade.ui.chat.ChatRole
import vade.ui.chat.ContentPart
import vade.ui.data.SessionSource
import vade.ui.data.SessionSummary
import vade.ui.data.TimeBucket
import vade.ui.data.VadeErrorCategory
import vade.ui.data.VadeEvent
import vade.ui.destinations.Destination
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.LocalSkin
import vade.ui.skin.SkinRegistry
import vade.ui.skin.Skins
import vade.ui.skin.material.MaterialSkin

/**
 * g-error-retry · the retry entry that makes a failed read recoverable.
 *
 * The gap this file pins: `UiState.sessionsError` / `messagesError` were published by the
 * loader and read by **nobody**, so "core 没起 / 端口不对" left the user with an error and no
 * way out but restarting the app. `UiState.retrySessions()` / `retryMessages()` existed and
 * were never called from the UI.
 *
 * Three layers, deliberately separate:
 *  1. **pure model** — [loadBannerModel] gives the four transitions (failed → retrying →
 *     failed again / success) with no composition and no clock;
 *  2. **the widget** — a real button: clickable, `Role.Button`, non-empty accessible
 *     description, vector icon, keyboard-activatable, disabled while a retry is in flight;
 *  3. **the wiring** — `AppShell` + a real `UiState` + a recording `SessionSource`: the
 *     button must reach `SessionSource.sessions()` / `.messages()` exactly once per click,
 *     and the list retry must never touch the thread read (they are two banners).
 *
 * The wiring tests run the loader on a `StandardTestDispatcher`, so "a read is in flight" is
 * a controlled state (`CompletableDeferred` gate + manual `advanceUntilIdle()`), never a
 * wall-clock race.
 */
@OptIn(ExperimentalTestApi::class)
class LoadErrorBannerTest {

    // ---------------------------------------------------------------- fixtures

    private fun row(id: String, title: String = "会话 $id") = SessionSummary(
        id = id,
        title = title,
        preview = "预览 $id",
        bucket = TimeBucket.Today,
        updatedAt = 1_000L,
        updatedLabel = "刚刚",
    )

    private fun message(id: String, text: String) =
        ChatMessage(id = id, role = ChatRole.User, parts = listOf(ContentPart.Text(text)))

    private fun loadError(
        code: String = VadeErrorCodes.TIMEOUT,
        detail: String = "Read timed out after 12000 ms",
        category: VadeErrorCategory = VadeErrorCategory.Retryable,
    ) = LoadError(category = category, code = code, detail = detail)

    /**
     * A controllable [SessionSource]: counts every fetch, can park a fetch on a gate (the
     * in-flight window the retry button must survive), and can fail on demand.
     */
    private class RecordingSource(
        var rows: List<SessionSummary> = emptyList(),
        var thread: List<ChatMessage> = emptyList(),
    ) : SessionSource {
        val sessionsCalls = AtomicInteger()
        val messagesCalls = AtomicInteger()

        /** Non-null → the fetch throws this (the "still broken" case). */
        var sessionsFailure: Throwable? = null
        var messagesFailure: Throwable? = null

        /** Non-null → the fetch parks until completed, so "in flight" is observable. */
        var sessionsGate: CompletableDeferred<Unit>? = null
        var messagesGate: CompletableDeferred<Unit>? = null

        override suspend fun sessions(): List<SessionSummary> {
            sessionsCalls.incrementAndGet()
            sessionsGate?.await()
            sessionsFailure?.let { throw it }
            return rows
        }

        override suspend fun messages(sessionId: String): List<ChatMessage> {
            messagesCalls.incrementAndGet()
            messagesGate?.await()
            messagesFailure?.let { throw it }
            return thread
        }

        override fun observeEvents(): Flow<VadeEvent> = emptyFlow()
    }

    private fun newState(
        source: SessionSource,
        dispatcher: TestDispatcher,
        initialSessionId: String? = null,
    ) = UiState(
        initialBar = BottomBarStyle.Fixed,
        initialSkinId = "material",
        initialDest = Destination.Sessions,
        initialSessionId = initialSessionId,
        // `Interrupted` = a settled thread: no streaming animation loop, so these tests stay
        // cheap and deterministic. The banner is orthogonal to the chat preset.
        chatPreset = ChatPreset.Interrupted,
        source = source,
        loaderScope = CoroutineScope(dispatcher + SupervisorJob()),
    )

    /**
     * Composes the **real** shell for [state]. 1000dp wide = the 840–1199 desktop two-pane
     * tier, so the list banner and the thread banner can be on screen at the same time (the
     * independence test needs both).
     */
    private fun shellTest(
        state: UiState,
        dispatcher: TestDispatcher,
        body: ComposeUiTest.() -> Unit,
    ) = runComposeUiTest {
        Skins.installBuiltIns()
        setContent {
            // Density 1: the test window is ~1024 px wide, so a 1000dp box really lands in
            // the two-pane tier instead of being clamped into the single-pane one.
            CompositionLocalProvider(
                LocalSkin provides SkinRegistry.get(state.skinId),
                LocalDensity provides Density(1f, 1f),
            ) {
                Box(Modifier.width(1000.dp)) { AppShell(state) }
            }
        }
        try {
            dispatcher.scheduler.advanceUntilIdle()
            waitForIdle()
            body()
        } finally {
            state.dispose()
        }
    }

    private fun ComposeUiTest.nodeCount(tag: String): Int =
        onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun ComposeUiTest.textCount(text: String): Int =
        onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun ComposeUiTest.showBanner(
        phase: LoadPhase,
        error: LoadError?,
        onRetry: () -> Unit,
    ) {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                Column {
                    SessionListLoadBanner(phase = phase, error = error, onRetry = onRetry)
                }
            }
        }
    }

    // ================================================================ 1 · model

    @Test
    fun failedListReadShowsItsClassificationAndAnEnabledRetry() {
        val model = assertNotNull(
            loadBannerModel(LoadBannerKind.Sessions, LoadPhase.Failed, loadError(), retained = null),
            "失败态必须出横幅",
        )
        assertEquals("会话列表加载失败", model.title)
        assertEquals("可重试 · TIMEOUT", model.categoryLine)
        assertEquals("Read timed out after 12000 ms", model.detail)
        assertTrue(model.hint.isNotBlank(), "横幅必须告诉用户能做什么")
        assertFalse(model.retrying)
        assertTrue(model.retryEnabled, "失败态的重试入口必须可点")
        assertEquals("重试", model.retryLabel)
        assertEquals("重试加载会话列表", model.retryDescription)
    }

    @Test
    fun retryInFlightKeepsTheBannerUpAndDisablesTheButton() {
        // What the loader publishes the instant a retry starts: error cleared, phase Loading.
        val model = assertNotNull(
            loadBannerModel(LoadBannerKind.Sessions, LoadPhase.Loading, error = null, retained = loadError()),
            "重试期间横幅不能凭空消失——否则用户不知道点了有没有用",
        )
        assertTrue(model.retrying, "重试中必须自报进行中")
        assertFalse(model.retryEnabled, "重试中必须不可重复点")
        assertEquals("重试中…", model.retryLabel)
        assertEquals("正在重试会话列表…", model.title)
        assertEquals("可重试 · TIMEOUT", model.categoryLine, "上一次的分类信息要保留，别让用户丢失上下文")
    }

    @Test
    fun retryOfAStaleRowsListLoadAlsoDisablesTheButton() {
        // Rows on screen → refreshSessions keeps `phase = Failed` (stale-while-revalidate)
        // and only clears the error; the in-flight window is still real.
        val model = assertNotNull(
            loadBannerModel(LoadBannerKind.Sessions, LoadPhase.Failed, error = null, retained = loadError()),
        )
        assertTrue(model.retrying)
        assertFalse(model.retryEnabled, "stale-while-revalidate 的重试也必须挡住第二次点击")
    }

    @Test
    fun successHidesTheBannerCompletely() {
        assertNull(
            loadBannerModel(LoadBannerKind.Sessions, LoadPhase.Ready, error = null, retained = loadError()),
            "读成功就不该再有任何横幅（哪怕是保留的旧文案）",
        )
        assertNull(loadBannerModel(LoadBannerKind.Sessions, LoadPhase.Ready, error = null, retained = null))
    }

    @Test
    fun aSecondFailureReplacesTheClassification() {
        val first = loadError(VadeErrorCodes.TIMEOUT, "超时", VadeErrorCategory.Retryable)
        val firstModel = assertNotNull(loadBannerModel(LoadBannerKind.Sessions, LoadPhase.Failed, first, null))
        val second = loadError(
            VadeErrorCodes.UNAVAILABLE,
            "core 未启动（127.0.0.1:7788 连接被拒绝）",
            VadeErrorCategory.NeedsReconnect,
        )
        val secondModel = assertNotNull(loadBannerModel(LoadBannerKind.Sessions, LoadPhase.Failed, second, first))
        assertEquals("可重试 · TIMEOUT", firstModel.categoryLine)
        assertEquals("需要重连 · UNAVAILABLE", secondModel.categoryLine, "再失败必须换成新的分类，不能沿用旧文案")
        assertEquals("core 未启动（127.0.0.1:7788 连接被拒绝）", secondModel.detail)
        assertTrue(secondModel.retryEnabled)
        assertTrue(
            secondModel.hint.contains("端口"),
            "需要重连的提示要指向真实病因（core 没起 / 端口不对），实际：${secondModel.hint}",
        )
    }

    @Test
    fun theTwoReadsNeverShareTagsOrDescriptions() {
        val list = assertNotNull(loadBannerModel(LoadBannerKind.Sessions, LoadPhase.Failed, loadError(), null))
        val thread = assertNotNull(loadBannerModel(LoadBannerKind.Messages, LoadPhase.Failed, loadError(), null))
        assertTrue(list.tag != thread.tag)
        assertTrue(list.retryTag != thread.retryTag, "两个重试按钮必须有各自的 tag（不许合并成一个按钮）")
        assertTrue(list.retryDescription != thread.retryDescription)
        assertTrue(thread.title.contains("消息"), "会话详情横幅必须说清是消息读取失败：${thread.title}")
        assertTrue(list.title.contains("会话列表"))
    }

    // ============================================================== 2 · widget

    @Test
    fun retryEntryIsARealClickableButton() = runComposeUiTest {
        val clicks = AtomicInteger()
        showBanner(LoadPhase.Failed, loadError()) { clicks.incrementAndGet() }
        val button = onNodeWithTag(LoadBannerKind.Sessions.retryTag)
        button.assertExists()
        button.assertIsEnabled()
        button.assertHasClickAction()
        button.performClick()
        assertEquals(1, clicks.get(), "点一次必须真的调用 onRetry()")
    }

    @Test
    fun retryEntryIsDisabledAndIgnoresClicksWhileInFlight() = runComposeUiTest {
        val clicks = AtomicInteger()
        val inFlight = assertNotNull(
            loadBannerModel(LoadBannerKind.Sessions, LoadPhase.Loading, error = null, retained = loadError()),
        )
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                Column { LoadErrorBanner(model = inFlight, onRetry = { clicks.incrementAndGet() }) }
            }
        }
        val button = onNodeWithTag(LoadBannerKind.Sessions.retryTag)
        button.assertExists()
        button.assertIsNotEnabled()
        assertTrue(textCount("重试中…") >= 1, "进行中状态必须可见")
        // Gesture level, not semantics level: a user's second physical click must be ignored.
        button.performMouseInput { click() }
        button.performMouseInput { click() }
        waitForIdle()
        assertEquals(0, clicks.get(), "重试中连点必须被吞掉")
    }

    @Test
    fun retryEntryIsKeyboardActivatable() = runComposeUiTest {
        val clicks = AtomicInteger()
        showBanner(LoadPhase.Failed, loadError()) { clicks.incrementAndGet() }
        val button = onNodeWithTag(LoadBannerKind.Sessions.retryTag)
        button.performSemanticsAction(SemanticsActions.RequestFocus)
        waitForIdle()
        button.performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(1, clicks.get(), "桌面端 Tab 聚焦 + Enter 必须能重试（键盘可达）")
    }

    @Test
    fun retryEntryCarriesANonEmptyAccessibleDescription() = runComposeUiTest {
        showBanner(LoadPhase.Failed, loadError()) { }
        val node = onNodeWithTag(LoadBannerKind.Sessions.retryTag).fetchSemanticsNode()
        val descriptions = node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
        assertTrue(descriptions.isNotEmpty(), "重试按钮必须有非空无障碍描述")
        assertTrue(descriptions.any { it.isNotBlank() && it.length > 1 }, "描述不能是单个字符：$descriptions")
        assertTrue(
            descriptions.any { it.contains("重试") && it.contains("会话列表") },
            "描述要说清重试的是什么：$descriptions",
        )
        val hasRole = node.config.contains(SemanticsProperties.Role)
        assertEquals(true, hasRole, "必须是 Button 语义，不是文字链")
        assertEquals(Role.Button, node.config[SemanticsProperties.Role], "必须是 Button 语义，不是文字链")
    }

    @Test
    fun retryIconIsAVectorNotAGlyphCharacter() = runComposeUiTest {
        showBanner(LoadPhase.Failed, loadError()) { }
        // A VadeIcon renders an Image node carrying only its description; the button's label
        // is a *multi-character* word, never a glyph standing in for an icon.
        onNodeWithContentDescription("重试加载会话列表", useUnmergedTree = true).assertExists()
        val button = onNodeWithTag(LoadBannerKind.Sessions.retryTag).fetchSemanticsNode()
        val texts = button.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text }
        assertTrue(texts.isNotEmpty(), "按钮上必须有文字标签")
        assertTrue(texts.all { it.length > 1 }, "标签不许是单字符图标：$texts")
        assertFalse(texts.any { it in BANNED_GLYPHS }, "标签里出现了字符图标：$texts")

        // Source guard: the banner draws VadeIcons (ImageVector) and carries no glyph literal
        // as a Text/UiText argument.
        val code = codeOf(File("src/commonMain/kotlin/vade/ui/shell/LoadErrorBanner.kt"))
        assertTrue(code.contains("VadeIcons.Refresh"), "重试图标必须是 VadeIcons.Refresh（ImageVector）")
        assertTrue(code.contains("VadeIcons.Waiting"), "进行中图标必须是 VadeIcons.Waiting（ImageVector）")
        val glyphLiteral = Regex("""\b(?:UiText|Text)\(\s*"([$BANNED_GLYPHS])"""")
        val offenders = glyphLiteral.findAll(code).map { it.value }.toList()
        assertTrue(offenders.isEmpty(), "LoadErrorBanner.kt 里出现了字符图标：$offenders")
    }

    // =============================================================== 3 · wiring

    @Test
    fun listFailureRendersTheBannerAndItsRetryReachesTheSource() {
        val dispatcher = StandardTestDispatcher()
        val src = RecordingSource(rows = listOf(row("s1"))).apply {
            sessionsFailure = VadeException(VadeErrorCodes.UNAVAILABLE, "core 未启动（127.0.0.1:7788 连接被拒绝）")
        }
        val state = newState(src, dispatcher)
        shellTest(state, dispatcher) {
            assertEquals(1, src.sessionsCalls.get(), "启动时读一次会话列表")
            assertEquals(1, nodeCount(LoadBannerKind.Sessions.tag), "列表读失败必须出横幅（而不是静默空列表）")
            assertTrue(textCount("会话列表加载失败") >= 1)
            assertTrue(textCount("需要重连 · UNAVAILABLE") >= 1, "横幅要给出分类 + C-04 码")

            // Now make the retry succeed, then click for real.
            src.sessionsFailure = null
            onNodeWithTag(LoadBannerKind.Sessions.retryTag).performClick()
            dispatcher.scheduler.advanceUntilIdle()
            waitForIdle()

            assertEquals(2, src.sessionsCalls.get(), "点击重试必须真的再读一次 SessionSource.sessions()")
            assertEquals(0, nodeCount(LoadBannerKind.Sessions.tag), "重试成功后横幅必须消失")
            assertTrue(textCount("会话 s1") >= 1, "重试成功后内容必须出现")
        }
    }

    @Test
    fun threeClicksDuringARetryIssueExactlyOneExtraFetch() {
        val dispatcher = StandardTestDispatcher()
        val firstGate = CompletableDeferred<Unit>()
        val src = RecordingSource(rows = listOf(row("s1"))).apply {
            sessionsFailure = VadeException(VadeErrorCodes.TIMEOUT, "超时")
            sessionsGate = firstGate
        }
        val state = newState(src, dispatcher)
        shellTest(state, dispatcher) {
            firstGate.complete(Unit)
            dispatcher.scheduler.advanceUntilIdle()
            waitForIdle()
            assertEquals(1, src.sessionsCalls.get())
            assertEquals(1, nodeCount(LoadBannerKind.Sessions.tag))

            // Arm a gate for the retry so the in-flight window stays open.
            val retryGate = CompletableDeferred<Unit>()
            src.sessionsGate = retryGate
            src.sessionsFailure = null
            val button = onNodeWithTag(LoadBannerKind.Sessions.retryTag)
            button.performClick()
            // The click only queues the fetch on the test dispatcher; run it up to the gate.
            dispatcher.scheduler.advanceUntilIdle()
            waitForIdle()
            assertEquals(2, src.sessionsCalls.get(), "第一次点击发起重试")
            button.assertIsNotEnabled()

            // Two more clicks while the retry is in flight: gestures must be swallowed.
            button.performMouseInput { click() }
            button.performMouseInput { click() }
            waitForIdle()
            assertEquals(2, src.sessionsCalls.get(), "重试中连点 3 次，源只能被调 1 次（总计 2）")

            retryGate.complete(Unit)
            dispatcher.scheduler.advanceUntilIdle()
            waitForIdle()
            assertEquals(0, nodeCount(LoadBannerKind.Sessions.tag))
        }
    }

    @Test
    fun aFailedRetryKeepsTheBannerWithTheNewClassification() {
        val dispatcher = StandardTestDispatcher()
        val src = RecordingSource(rows = listOf(row("s1"))).apply {
            sessionsFailure = VadeException(VadeErrorCodes.TIMEOUT, "Read timed out after 12000 ms")
        }
        val state = newState(src, dispatcher)
        shellTest(state, dispatcher) {
            assertTrue(textCount("可重试 · TIMEOUT") >= 1)

            // The retry fails again, differently (core reachable but broken).
            src.sessionsFailure = VadeException(VadeErrorCodes.UNAVAILABLE, "core closed the socket")
            onNodeWithTag(LoadBannerKind.Sessions.retryTag).performClick()
            dispatcher.scheduler.advanceUntilIdle()
            waitForIdle()

            assertEquals(2, src.sessionsCalls.get())
            assertEquals(1, nodeCount(LoadBannerKind.Sessions.tag), "再失败横幅必须还在")
            assertTrue(textCount("需要重连 · UNAVAILABLE") >= 1, "再失败必须换成新的分类文案")
            assertEquals(0, textCount("可重试 · TIMEOUT"), "旧分类必须被替换，不能两条并存")
            onNodeWithTag(LoadBannerKind.Sessions.retryTag).assertIsEnabled()
        }
    }

    @Test
    fun threadFailureRetriesTheThreadAndLeavesTheListAlone() {
        val dispatcher = StandardTestDispatcher()
        val src = RecordingSource(
            rows = listOf(row("s1")),
            thread = listOf(message("m1", "你好")),
        ).apply {
            messagesFailure = VadeException(VadeErrorCodes.TIMEOUT, "ui/attach 超时")
        }
        val state = newState(src, dispatcher, initialSessionId = "s1")
        shellTest(state, dispatcher) {
            assertEquals(1, src.sessionsCalls.get())
            assertEquals(1, src.messagesCalls.get())
            assertEquals(0, nodeCount(LoadBannerKind.Sessions.tag), "列表读成功就不该有列表横幅")
            assertEquals(1, nodeCount(LoadBannerKind.Messages.tag), "消息读失败必须有自己的横幅")

            src.messagesFailure = null
            onNodeWithTag(LoadBannerKind.Messages.retryTag).performClick()
            dispatcher.scheduler.advanceUntilIdle()
            waitForIdle()

            assertEquals(2, src.messagesCalls.get(), "详情横幅的重试必须调 SessionSource.messages()")
            assertEquals(1, src.sessionsCalls.get(), "详情重试不许连带重读会话列表")
            assertEquals(0, nodeCount(LoadBannerKind.Messages.tag))
            assertEquals(0, nodeCount(LoadBannerKind.Sessions.tag))
        }
    }

    @Test
    fun theTwoRetriesAreIndependent() {
        val dispatcher = StandardTestDispatcher()
        val src = RecordingSource(
            rows = listOf(row("s1")),
            thread = listOf(message("m1", "你好")),
        ).apply {
            messagesFailure = VadeException(VadeErrorCodes.TIMEOUT, "ui/attach 超时")
        }
        val state = newState(src, dispatcher, initialSessionId = "s1")
        shellTest(state, dispatcher) {
            // 1 · list OK, thread failed → only the thread banner is up.
            assertEquals(1, src.sessionsCalls.get())
            assertEquals(1, src.messagesCalls.get())
            assertEquals(0, nodeCount(LoadBannerKind.Sessions.tag), "列表读成功就不该有列表横幅")
            assertEquals(1, nodeCount(LoadBannerKind.Messages.tag))

            // 2 · the list read breaks *after* it had rows (core dies mid-session): the rows
            //     stay, the list banner appears — both banners are now on screen at once.
            src.sessionsFailure = VadeException(VadeErrorCodes.UNAVAILABLE, "core 未启动")
            state.retrySessions()
            dispatcher.scheduler.advanceUntilIdle()
            waitForIdle()
            assertEquals(2, src.sessionsCalls.get())
            assertEquals(1, nodeCount(LoadBannerKind.Sessions.tag))
            assertEquals(1, nodeCount(LoadBannerKind.Messages.tag), "两个横幅必须同时在场、互不吞并")

            // 3 · the list retry must not touch the thread banner nor its read.
            src.sessionsFailure = null
            onNodeWithTag(LoadBannerKind.Sessions.retryTag).performClick()
            dispatcher.scheduler.advanceUntilIdle()
            waitForIdle()
            assertEquals(3, src.sessionsCalls.get())
            assertEquals(1, src.messagesCalls.get(), "列表重试不许触发消息读取")
            assertEquals(0, nodeCount(LoadBannerKind.Sessions.tag))
            assertEquals(1, nodeCount(LoadBannerKind.Messages.tag), "列表重试成功后，消息横幅必须原地不动")

            // 4 · …and the other direction: the thread retry issues no list fetch.
            src.messagesFailure = null
            onNodeWithTag(LoadBannerKind.Messages.retryTag).performClick()
            dispatcher.scheduler.advanceUntilIdle()
            waitForIdle()
            assertEquals(2, src.messagesCalls.get())
            assertEquals(3, src.sessionsCalls.get(), "消息重试不许触发会话列表读取")
            assertEquals(0, nodeCount(LoadBannerKind.Messages.tag))
        }
    }

    @Test
    fun theFixturePathNeverShowsALoadBanner() = runComposeUiTest {
        // Regression: the default (fake) source is synchronous and never fails, so the shell
        // must not grow a permanent banner and must keep its 假数据 note.
        Skins.installBuiltIns()
        setContent {
            CompositionLocalProvider(LocalSkin provides SkinRegistry.get("material")) {
                // `remember`: the fixture path must not rebuild its state on every frame.
                val fixtureState = remember {
                    UiState(
                        initialBar = BottomBarStyle.Fixed,
                        initialSkinId = "material",
                        initialDest = Destination.Sessions,
                    )
                }
                AppShell(fixtureState)
            }
        }
        waitForIdle()
        assertEquals(0, nodeCount(LoadBannerKind.Sessions.tag))
        assertEquals(0, nodeCount(LoadBannerKind.Messages.tag))
        assertTrue(
            textCount("假数据 · 未连接 core（U1-a 只做骨架，U1-b 接线）") >= 1,
            "假数据路径的说明不能被吃掉",
        )
    }


    // ---------------------------------------------------------------- helpers

    private fun codeOf(file: File): String {
        assertTrue(file.isFile, "守卫目标不存在：${file.path}")
        val noBlock = file.readText().replace(Regex("(?s)/\\*.*?\\*/"), "")
        return noBlock.split('\n').joinToString("\n") { it.substringBefore("//") }
    }

    private companion object {
        /** Same vocabulary `IconGuardTest` bans — reused so the two guards cannot drift. */
        const val BANNED_GLYPHS = "↑↓←→▲▼■□●○◆＋…✕✓▾▸‹⌕✎•×◂▸⧗"
    }
}
