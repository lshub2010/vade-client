package vade.ui.destinations

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import vade.ui.data.PairingRuntime
import vade.ui.data.SessionRevocation
import vade.ui.protocol.VadeTransport
import vade.ui.protocol.VadeTransportFactory
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
}
