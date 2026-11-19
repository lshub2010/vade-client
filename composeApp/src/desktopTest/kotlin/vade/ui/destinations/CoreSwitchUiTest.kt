package vade.ui.destinations

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import vade.ui.data.CoreSwitchOutcome
import vade.ui.data.PairingAttempt
import vade.ui.data.PairingFailureKind
import vade.ui.data.PairingRuntime
import vade.ui.data.SessionRevocation
import vade.ui.protocol.VadeTransport
import vade.ui.protocol.VadeTransportFactory
import vade.ui.shell.BackDispatcher
import vade.ui.shell.BackLayer
import vade.ui.shell.LocalBackDispatcher
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin
import vade.ui.testutil.IsolatedPreferencesTest

/**
 * 缺陷⑩(a) · 设置页入口 + 换 core 子页的**真组合**用例。
 *
 * 钉住三件事：
 *  1. **已配对时入口也能点**（旧形态是 `enabled = false` 的灰行）：点一下真的打开子页；
 *  2. 子页把**当前** endpoint 写出来（用户换之前知道自己现在连的是谁），并给出新地址 + 码的输入；
 *  3. **取消（返回）不清配置**：关掉子页之后桥里还是原来那台 core —— 吊销/清理只发生在
 *     用户真的提交「配对并切换」之后。
 *
 * 换 core 的线上顺序 / 吊销失败 / 超时在数据层 `CoreSwitchTest`（MockEngine）里断言。
 */
@OptIn(ExperimentalTestApi::class)
class CoreSwitchUiTest : IsolatedPreferencesTest() {

    private class MarkerFactory : VadeTransportFactory {
        override suspend fun connect(): VadeTransport = throw IllegalStateException("marker must not connect")
    }

    private val marker = MarkerFactory()

    @BeforeTest
    fun resetBefore() {
        PairingRuntime.clearForTests()
        CoreSwitchNotice.text = null
    }

    @AfterTest
    fun resetAfter() {
        PairingRuntime.clearForTests()
        CoreSwitchNotice.text = null
    }

    private fun androidx.compose.ui.test.ComposeUiTest.setProfile() {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                ProfileScreen(
                    skinId = "material",
                    barStyle = BottomBarStyle.Fixed,
                    containerLabel = "test",
                    onSkinChange = {},
                    onBarChange = {},
                )
            }
        }
        waitForIdle()
    }

    // ------------------------------------------------------------- 入口可点

    @Test
    fun theEntryOpensThePanelWhenAlreadyPaired() = runComposeUiTest {
        PairingRuntime.noteBooted()
        PairingRuntime.install(marker, "10.0.0.9", 19999)
        setProfile()

        val entry = onAllNodesWithText(CORE_SWITCH_TITLE).onFirst()
        entry.assertHasClickAction()
        onNodeWithText("已连接 10.0.0.9:19999 · 点按更换").assertExists()

        entry.performScrollTo().performClick()
        waitForIdle()

        onNodeWithTag(CORE_SWITCH_PANEL_TAG).assertExists()
        onNodeWithText("已连接 10.0.0.9:19999").assertExists()
        // 新地址 + 新码的输入都在（复用配对表单，不是另写一份）。
        onNodeWithText(PAIRING_PLACEHOLDER).assertExists()
        onNodeWithText(SWITCH_BUTTON_LABEL).assertExists()
    }

    @Test
    fun theEntryOpensThePairingPanelWhenNotPaired() = runComposeUiTest {
        // 未配对也必须是同一个入口（它就是首配的正门，不是只有「已配对」才看得见的东西）。
        PairingRuntime.noteBooted()
        setProfile()

        val entry = onAllNodesWithText(CORE_SWITCH_TITLE).onFirst()
        entry.assertHasClickAction()
        onNodeWithText("未连接 · 点按配对").assertExists()

        entry.performScrollTo().performClick()
        waitForIdle()

        onNodeWithTag(CORE_SWITCH_PANEL_TAG).assertExists()
        onNodeWithText("还没有连接 core").assertExists()
        onNodeWithText(PAIRING_PLACEHOLDER).assertExists()
    }

    // ------------------------------------------------------------- 取消不清配置

    @Test
    fun closingThePanelKeepsTheCurrentCore() = runComposeUiTest {
        PairingRuntime.noteBooted()
        PairingRuntime.install(marker, "10.0.0.9", 19999)
        setProfile()

        onAllNodesWithText(CORE_SWITCH_TITLE).onFirst().performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag(CORE_SWITCH_PANEL_TAG).assertExists()

        // 返回 = 取消：只关子页，一个请求都不发、桥一个字节都不动。
        onNodeWithContentDescription("返回").performClick()
        waitForIdle()

        assertEquals(
            0,
            onAllNodesWithTag(CORE_SWITCH_PANEL_TAG).fetchSemanticsNodes().size,
            "返回之后子页离开组合",
        )
        assertEquals("10.0.0.9:19999", PairingRuntime.pairedEndpoint, "取消切换不清配置")
        assertNotNull(PairingRuntime.pairedFactory)
        assertTrue(PairingRuntime.pairedFactory === marker, "桥里还是原来那台 core 的 factory")
    }

    // ------------------------------------------------------------- 吊销失败的文案

    @Test
    fun revocationFailureIsSaidInWordsAndCarriesTheDetail() {
        val failed = SessionRevocation.Failed(IllegalStateException("HTTP 500"))
        val text = revocationNotice(failed, "已切换到 new.local:9443")
        assertTrue(
            text.startsWith(REVOKE_FAILED_NOTICE),
            "吊销失败必须说「$REVOKE_FAILED_NOTICE」：$text",
        )
        assertTrue("HTTP 500" in text, "传输原话要带出来：$text")
        assertTrue("已切换到 new.local:9443" in text, "切换本身是成功的，别让用户以为没切：$text")

        assertEquals("已切换到 new.local:9443", revocationNotice(SessionRevocation.Revoked, "已切换到 new.local:9443"))
        assertEquals("已断开当前 core。", revocationNotice(SessionRevocation.NotNeeded, CORE_SWITCH_DISCONNECTED))
    }

    @Test
    fun theSettingsPageRepeatsTheLastRevocationFailure() = runComposeUiTest {
        // 换 core 成功会重建 UiState，子页可能随之离开组合 —— 入口那一行必须还记得这条坏消息。
        PairingRuntime.noteBooted()
        PairingRuntime.install(marker, "10.0.0.9", 19999)
        CoreSwitchNotice.text = revocationNotice(
            SessionRevocation.Failed(IllegalStateException("HTTP 500")),
            "已切换到 10.0.0.8:8443",
        )
        setProfile()

        onNodeWithText(REVOKE_FAILED_NOTICE, substring = true).assertExists()
    }

    // ==================================================== R3 · 提交中不许被返回取消

    /**
     * 码输入框的匹配器 —— 面板上三个可输入框（host / port / 码）在语义树上都是「可输入文本」，
     * host/port 的占位永不显示，只有码框带 [PAIRING_CODE_FIELD_TAG]（与 `PairingCardUiTest` 同一判据）。
     */
    private fun codeField() = hasSetTextAction() and hasAnyAncestor(hasTestTag(PAIRING_CODE_FIELD_TAG))

    /**
     * R3 · 换 core 在途时，**两条返回路都不放行**。
     *
     * 事故形态：`submitting` 关在 [PairingForm] 里，顶栏返回与系统返回都不知道它在跑；
     * 用户一按返回，提交协程随面板离开组合被取消 —— 停在「旧已吊销、新未装」，且一句提示
     * 都没有（`onConnected` 永不执行）。这里用真实组合 + 真实的返回栈断言三件事：
     *  1. 顶栏返回在提交中**没有 click 动作**（不是 `enabled=false` 那种假禁用）；
     *  2. 系统返回被面板这一层消费（栈深 2），宿主那一层与 `onBack` 都没被调用；
     *  3. 落地之后返回恢复，回执写进 [CoreSwitchNotice]。
     */
    @Test
    fun backIsSwallowedWhileTheSwitchIsInFlightThenReEnabled() = runComposeUiTest {
        PairingRuntime.noteBooted()
        PairingRuntime.install(marker, "10.0.0.9", 19999)
        CoreSwitchNotice.text = null

        val gate = CompletableDeferred<CoreSwitchOutcome>()
        val dispatcher = BackDispatcher()
        var panelClosed = false
        var hostClosed = false
        var switches = 0

        setContent {
            CompositionLocalProvider(
                LocalSkin provides MaterialSkin(),
                LocalBackDispatcher provides dispatcher,
            ) {
                // 与 ProfileScreen 同一形状：宿主先登记自己那一层，面板后组成（压在上面）。
                BackLayer(enabled = true, onBack = { hostClosed = true })
                CoreSwitchPanel(
                    onBack = { panelClosed = true },
                    onSwitch = { _, _ ->
                        switches += 1
                        gate.await()
                    },
                )
            }
        }
        waitForIdle()

        onNode(codeField()).performTextInput("青松-R3R3R3")
        onNodeWithText(SWITCH_BUTTON_LABEL).performClick()
        waitUntil(timeoutMillis = 5_000) { switches == 1 }
        waitUntil(timeoutMillis = 5_000) { dispatcher.depth == 2 }
        waitForIdle()

        // ① 顶栏返回：提交中不挂 clickable。
        onNodeWithContentDescription("返回").assertHasNoClickAction()
        // ② 系统返回：被面板这一层吃掉，宿主层不动、面板不关。
        assertTrue(dispatcher.dispatch(), "提交中系统返回必须被面板这一层消费（不能落到宿主层）")
        waitForIdle()
        assertFalse(panelClosed, "提交中返回不许关面板")
        assertFalse(hostClosed, "更不许把返回漏给宿主那一层")

        // ③ 落地：返回恢复，回执留在 CoreSwitchNotice（面板真的走了也看得见）。
        gate.complete(
            CoreSwitchOutcome(
                revocation = SessionRevocation.Revoked,
                attempt = PairingAttempt.Connected(marker, "10.0.0.8", 8443),
            ),
        )
        waitUntil(timeoutMillis = 5_000) { CoreSwitchNotice.text != null }
        waitForIdle()
        assertEquals("已切换到 10.0.0.8:8443", CoreSwitchNotice.text)
        assertEquals(1, dispatcher.depth, "提交结束后面板这一层出栈（只剩宿主那一层）")
        onNodeWithContentDescription("返回").assertHasClickAction()
        onNodeWithContentDescription("返回").performClick()
        waitForIdle()
        assertTrue(panelClosed, "落地之后返回恢复原语义")
    }

    /**
     * R3 · 提交**活过面板**也必须留回执。
     *
     * 这条钉的是作用域：`rememberCoroutineScope()` 会随面板离开组合被取消（换 core 成功会
     * 重建 `UiState`，面板必然先走一步）。这里把面板直接移出组合，再让提交落地 ——
     * 用组合作用域时 `CoreSwitchNotice` 永远是 null（「零提示」），用 [CoreSwitchNotice]
     * 回执的长生命周期作用域才有那句「旧凭证吊销失败」。
     */
    @Test
    fun aSubmitThatOutlivesThePanelStillLeavesAReceipt() = runComposeUiTest {
        PairingRuntime.noteBooted()
        PairingRuntime.install(marker, "10.0.0.9", 19999)
        CoreSwitchNotice.text = null

        val gate = CompletableDeferred<CoreSwitchOutcome>()
        var switches = 0
        var showPanel by mutableStateOf(true)

        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                if (showPanel) {
                    CoreSwitchPanel(
                        onBack = {},
                        onSwitch = { _, _ ->
                            switches += 1
                            gate.await()
                        },
                    )
                }
            }
        }
        waitForIdle()

        onNode(codeField()).performTextInput("青松-R3R3R3")
        onNodeWithText(SWITCH_BUTTON_LABEL).performClick()
        waitUntil(timeoutMillis = 5_000) { switches == 1 }

        // 面板先走（与「换 core 成功 → UiState 重建」同一个后果）。
        runOnIdle { showPanel = false }
        waitForIdle()
        assertEquals(null, CoreSwitchNotice.text, "还没落地，当然没有回执")

        gate.complete(
            CoreSwitchOutcome(
                revocation = SessionRevocation.Failed(IllegalStateException("HTTP 500")),
                attempt = PairingAttempt.Connected(marker, "10.0.0.8", 8443),
            ),
        )
        waitUntil(timeoutMillis = 5_000) { CoreSwitchNotice.text != null }
        assertTrue(
            CoreSwitchNotice.text!!.startsWith(REVOKE_FAILED_NOTICE),
            "面板没了，吊销失败也必须留下回执：${CoreSwitchNotice.text}",
        )
    }

    /**
     * R3 · 双失败必须**都看得见**：吊销失败在表单上方、带 Assertive 的 live region；表单里
     * 新 core 被拒的文案同时在场。
     *
     * 旧位置是页尾：长屏上两条隔着整张表单卡，用户只看得见一条。这里同时钉几何
     * （notice.bottom ≤ 表单顶）与语义（`LiveRegion` = Assertive）。
     */
    @Test
    fun bothFailuresAreVisibleAndTheRevokeNoticeSitsAboveTheForm() = runComposeUiTest {
        PairingRuntime.noteBooted()
        PairingRuntime.install(marker, "10.0.0.9", 19999)
        CoreSwitchNotice.text = null

        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                CoreSwitchPanel(
                    onBack = {},
                    onSwitch = { _, _ ->
                        CoreSwitchOutcome(
                            revocation = SessionRevocation.Failed(IllegalStateException("HTTP 500")),
                            attempt = PairingAttempt.Rejected(
                                PairingFailureKind.ServerDown,
                                java.net.ConnectException("Connection refused: /10.0.0.8:8443"),
                            ),
                        )
                    },
                )
            }
        }
        waitForIdle()

        onNode(codeField()).performTextInput("青松-R3R3R3")
        onNodeWithText(SWITCH_BUTTON_LABEL).performClick()
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText(REVOKE_FAILED_NOTICE, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        waitForIdle()

        // ① 吊销失败：在屏上、在 form 上方、并且是「立刻播报」。
        val notice = onNodeWithTag(CORE_SWITCH_NOTICE_TAG).fetchSemanticsNode()
        assertTrue(
            notice.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Assertive,
            "换 core 的结果必须立刻播报（polite 会排在页面其它播报之后）",
        )
        onNodeWithTag(CORE_SWITCH_NOTICE_TAG).assertIsDisplayed()
        assertTrue(
            notice.boundsInRoot.height > 0f,
            "回执必须有可见高度（bringIntoView）：${notice.boundsInRoot}",
        )

        // ② 双失败的另一半：新 core 被拒的标题与下一步也在屏上。
        //    标题里的 endpoint 是**表单里填的**（这里就是初值 10.0.0.9:19999 —— 表单快照口径）。
        onNodeWithText("core 没有在 10.0.0.9:19999 上运行", substring = true).assertIsDisplayed()
        onNodeWithText("先把 core 跑起来再重试", substring = true).assertExists()

        // ③ 几何：回执在表单（码输入框）**上方** —— 不是页尾。
        val form = onNode(codeField()).fetchSemanticsNode()
        assertTrue(
            notice.boundsInRoot.bottom <= form.boundsInRoot.top,
            "吊销失败必须在表单上方：notice=${notice.boundsInRoot} form=${form.boundsInRoot}",
        )
    }

    // ================================================================ R3 · 两步确认（断开）

    @Test
    fun disconnectingNeedsASecondTapOnTheSameCard() = runComposeUiTest {
        PairingRuntime.noteBooted()
        PairingRuntime.install(marker, "10.0.0.9", 19999)
        CoreSwitchNotice.text = null
        var disconnects = 0

        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                CoreSwitchPanel(
                    onBack = {},
                    onDisconnect = {
                        disconnects += 1
                        SessionRevocation.Revoked
                    },
                )
            }
        }
        waitForIdle()

        // 第一次点：只开闸门 —— 按钮换文案 + 一句说明，一个请求都不发。
        onNodeWithText(CORE_SWITCH_DISCONNECT).performClick()
        waitForIdle()
        assertEquals(0, disconnects, "第一次点只开闸门，不执行")
        onNodeWithText(CORE_SWITCH_DISCONNECT_CONFIRM).assertExists()
        onNodeWithText(CORE_SWITCH_DISCONNECT_HINT).assertExists()
        assertEquals(
            0,
            onAllNodesWithText(CORE_SWITCH_DISCONNECT).fetchSemanticsNodes().size,
            "同一个按钮（不是第二个），旧文案已经换掉",
        )

        // 窗口内的第二次：真的断开。
        onNodeWithText(CORE_SWITCH_DISCONNECT_CONFIRM).performClick()
        waitUntil(timeoutMillis = 5_000) { disconnects == 1 }
        waitForIdle()
        onNodeWithText(CORE_SWITCH_DISCONNECTED).assertExists()
        // 没有弹层：面板始终在，确认也只是同一张卡里的一句话。
        onNodeWithTag(CORE_SWITCH_PANEL_TAG).assertExists()
    }

    @Test
    fun theTwoStepWindowOpensExecutesAndFallsBack() {
        val confirm = TwoStepConfirm()
        assertEquals(DISCONNECT_CONFIRM_WINDOW_MS, confirm.window, "窗口是 3 秒")
        assertEquals(3_000L, confirm.window)

        assertFalse(confirm.tap(), "第一次点只开闸门")
        assertTrue(confirm.armed)
        assertTrue(confirm.tap(), "窗口内的第二次才执行")
        assertFalse(confirm.armed, "执行之后闸门复位")

        assertFalse(confirm.tap(), "再点一次重新开闸")
        confirm.expire()
        assertFalse(confirm.armed, "窗口到期自动回退")
        assertFalse(confirm.tap(), "回退之后下一次点击从头确认")
    }
}
