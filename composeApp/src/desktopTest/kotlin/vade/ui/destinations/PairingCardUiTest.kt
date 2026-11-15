package vade.ui.destinations

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import vade.ui.data.PairingAttempt
import vade.ui.data.PairingFailureKind
import vade.ui.data.PairingRuntime
import vade.ui.data.RawSourceStrings
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import vade.ui.protocol.VadeTransportFactory
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin

/**
 * g-pair-first-run · 引导卡的**真组合**用例：出现/不出现、提交恰好一次尝试（参数 = 输入的
 * 码）、三种失败文案可区分且都带下一步、失败后可重试。可见性判定在卡内 —— 因此直接注入
 * [FirstRunPairingCard] 的 raw/onSubmit 替身，不经过环境变量。
 */
@OptIn(ExperimentalTestApi::class)
class PairingCardUiTest {

    private val realNoCode = RawSourceStrings("real", "127.0.0.1", "8443", null)
    private val realWithCode = RawSourceStrings("real", "127.0.0.1", "8443", "青松-ABC123")
    private val fake = RawSourceStrings(null, null, null, null)

    /** 桥是全局单例 —— 每个用例前后清回未配对态。 */
    @BeforeTest
    fun resetBridgeBefore() = PairingRuntime.clearForTests()

    @AfterTest
    fun resetBridgeAfter() = PairingRuntime.clearForTests()

    private class MarkerFactory : VadeTransportFactory {
        override suspend fun connect(): Nothing = throw IllegalStateException("marker must not connect")
    }

    // ------------------------------------------------------------- 出现 / 不出现

    @Test
    fun cardAppearsOnlyForRealSourceWithoutCode() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                FirstRunPairingCard(raw = { realNoCode })
            }
        }
        waitUntil { onAllNodesWithText(PAIRING_TITLE).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText(PAIRING_TITLE).assertExists()
        onNodeWithText(PAIRING_PLACEHOLDER).assertExists()
        onNodeWithText("pair_code=", substring = true).assertExists()

        // 有码（环境变量/命令行旧路径）→ 不出现。
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                FirstRunPairingCard(raw = { realWithCode })
            }
        }
        assertEquals(0, onAllNodesWithText(PAIRING_TITLE).fetchSemanticsNodes().size)

        // 假源（fixtures）→ 不出现。
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                FirstRunPairingCard(raw = { fake })
            }
        }
        assertEquals(0, onAllNodesWithText(PAIRING_TITLE).fetchSemanticsNodes().size)
    }

    // ------------------------------------------------------------- 提交

    @Test
    fun submitMakesExactlyOneAttemptWithEnteredCodeAndHidesOnSuccess() = runComposeUiTest {
        val marker = MarkerFactory()
        val calls = mutableListOf<Pair<RawSourceStrings, String>>()
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                FirstRunPairingCard(
                    raw = { realNoCode },
                    onSubmit = { raw, code ->
                        calls.add(raw to code)
                        PairingAttempt.Connected(marker)
                    },
                )
            }
        }
        waitUntil { onAllNodesWithText(PAIRING_PLACEHOLDER).fetchSemanticsNodes().isNotEmpty() }
        onNode(hasSetTextAction()).performTextInput("青松-WRONG1")
        onNodeWithText(PAIRING_BUTTON_LABEL).performClick()
        waitUntil { calls.isNotEmpty() }
        waitForIdle()

        assertEquals(1, calls.size, "恰好一次连接尝试")
        assertEquals("青松-WRONG1", calls.single().second, "尝试的参数 = 输入的码（原样）")
        assertTrue(PairingRuntime.pairedFactory === marker, "成功后桥里装的是这次尝试的 factory")
        // 成功 → 卡片消失（桥状态驱动，不是本地记忆）。
        assertEquals(0, onAllNodesWithText(PAIRING_TITLE).fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithText(PAIRING_BUTTON_LABEL).fetchSemanticsNodes().size)
    }

    // ------------------------------------------------------------- 三种失败

    @Test
    fun badCodeCopyIsDistinctAndActionable() = runComposeUiTest {
        failingCard(PairingFailureKind.BadCode, VadeException(VadeErrorCodes.AUTH_FAILED, "HTTP 401: bad pair code"))
        waitUntil { onAllNodesWithText("配对码不对", substring = true).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("配对码不对：可能已过期、已被用掉，或者抄错了").assertExists()
        onNodeWithText("HTTP 401: bad pair code", substring = true).assertExists()
        onNodeWithText("下一步：回 core 的终端重新取一个新码", substring = true).assertExists()
        // 另两种文案不在场。
        assertEquals(0, onAllNodesWithText("core 没有在", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithText("网络不通", substring = true).fetchSemanticsNodes().size)
        // 失败后输入与按钮仍可用 —— 重试就是再点一次。
        onNodeWithText(PAIRING_BUTTON_LABEL).assertIsEnabled()
        onNode(hasSetTextAction()).assertExists()
    }

    @Test
    fun serverDownCopyCarriesTheStartupCommand() = runComposeUiTest {
        failingCard(PairingFailureKind.ServerDown, java.net.ConnectException("Connection refused: /127.0.0.1:8443"))
        waitUntil { onAllNodesWithText("core 没有在", substring = true).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("core 没有在 127.0.0.1:8443 上运行", substring = true).assertExists()
        onNodeWithText("PYTHONPATH", substring = true).assertExists()
        onNodeWithText("先把 core 跑起来再重试", substring = true).assertExists()
        assertEquals(0, onAllNodesWithText("配对码不对", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithText("网络不通", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun networkCopyIsRetryable() = runComposeUiTest {
        failingCard(PairingFailureKind.Network, java.net.SocketTimeoutException("connect timed out"))
        waitUntil { onAllNodesWithText("网络不通", substring = true).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("网络不通：连不上 core").assertExists()
        onNodeWithText("稍后再点一次「配对」重试", substring = true).assertExists()
        assertEquals(0, onAllNodesWithText("配对码不对", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithText("core 没有在", substring = true).fetchSemanticsNodes().size)
        onNodeWithText(PAIRING_BUTTON_LABEL).assertIsEnabled()
    }

    /** 组一张卡，输入一个码并点「配对」，提交按 kind 失败。 */
    private fun androidx.compose.ui.test.ComposeUiTest.failingCard(kind: PairingFailureKind, cause: Throwable) {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                FirstRunPairingCard(
                    raw = { RawSourceStrings("real", "127.0.0.1", "8443", null) },
                    onSubmit = { _, _ -> PairingAttempt.Rejected(kind, cause) },
                )
            }
        }
        waitUntil { onAllNodesWithText(PAIRING_PLACEHOLDER).fetchSemanticsNodes().isNotEmpty() }
        onNode(hasSetTextAction()).performTextInput("青松-WRONG1")
        onNodeWithText(PAIRING_BUTTON_LABEL).performClick()
    }

    // ------------------------------------------------------------- 纯文案

    @Test
    fun headlineAndNextStepArePairwiseDistinct() {
        val raw = realNoCode
        val h1 = pairingHeadline(PairingFailureKind.BadCode, raw)
        val h2 = pairingHeadline(PairingFailureKind.ServerDown, raw)
        val h3 = pairingHeadline(PairingFailureKind.Network, raw)
        assertNotEquals(h1, h2)
        assertNotEquals(h2, h3)
        assertNotEquals(h1, h3)
        val n1 = pairingNextStep(PairingFailureKind.BadCode, raw)
        val n2 = pairingNextStep(PairingFailureKind.ServerDown, raw)
        val n3 = pairingNextStep(PairingFailureKind.Network, raw)
        assertNotEquals(n1, n2)
        assertNotEquals(n2, n3)
        assertNotEquals(n1, n3)
        // 每条下一步都有可执行动作：取码 / 启动命令 / 重试。
        assertTrue(n1.contains("重新取一个新码"))
        assertTrue(n2.contains("PYTHONPATH"))
        assertTrue(n3.contains("重试"))
    }
}
