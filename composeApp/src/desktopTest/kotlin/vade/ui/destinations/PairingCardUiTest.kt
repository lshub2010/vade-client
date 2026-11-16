package vade.ui.destinations

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
    private val nothingSet = RawSourceStrings(null, null, null, null)
    private val explicitFixtures = RawSourceStrings("fixtures", null, null, null)

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
    fun cardAppearsOnlyAfterAnUnpairedBootWithoutACredential() = runComposeUiTest {
        // H1：卡片是**启动**决定 —— 入口的 `sessionSourceFromEnvironment()` 先 noteBooted()。
        PairingRuntime.noteBooted()
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                FirstRunPairingCard(raw = { realNoCode })
            }
        }
        waitUntil { onAllNodesWithText(PAIRING_TITLE).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText(PAIRING_TITLE).assertExists()
        onNodeWithText(PAIRING_PLACEHOLDER).assertExists()
        onNodeWithText("pair_code=", substring = true).assertExists()

        // 默认启动（什么都没配）也是首次运行 → 出卡（新用户唯一的入口）。
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                FirstRunPairingCard(raw = { nothingSet })
            }
        }
        waitUntil { onAllNodesWithText(PAIRING_TITLE).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText(PAIRING_TITLE).assertExists()

        // 有码（环境变量/命令行旧路径）→ 不出现。
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                FirstRunPairingCard(raw = { realWithCode })
            }
        }
        waitForIdle()
        assertEquals(0, onAllNodesWithText(PAIRING_TITLE).fetchSemanticsNodes().size)

        // 显式 fixtures（开发者的逃生口）→ 不出现。
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                FirstRunPairingCard(raw = { explicitFixtures })
            }
        }
        waitForIdle()
        assertEquals(0, onAllNodesWithText(PAIRING_TITLE).fetchSemanticsNodes().size)

        // 非入口路径（没有任何人声明过本次启动）→ 不出现。
        PairingRuntime.clearForTests()
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                FirstRunPairingCard(raw = { realNoCode })
            }
        }
        waitForIdle()
        assertEquals(0, onAllNodesWithText(PAIRING_TITLE).fetchSemanticsNodes().size)
    }

    // ------------------------------------------------------------- 提交

    @Test
    fun submitMakesExactlyOneAttemptWithEnteredCodeAndHidesOnSuccess() = runComposeUiTest {
        val marker = MarkerFactory()
        val calls = mutableListOf<Pair<RawSourceStrings, String>>()
        // H1 契约：卡片是**启动**决定 —— 只有入口（`sessionSourceFromEnvironment`）声明过
        // 「本次启动没有凭据」（`noteBooted()`）才出卡。这两个用例从前省了这一步，等于在
        // 一个永不出现的卡上等占位文案（ComposeTimeoutException）。
        PairingRuntime.noteBooted()
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                FirstRunPairingCard(
                    raw = { realNoCode },
                    onSubmit = { raw, code ->
                        calls.add(raw to code)
                        PairingAttempt.Connected(marker, "10.0.0.9", 19999)
                    },
                )
            }
        }
        waitUntil { onAllNodesWithText(PAIRING_PLACEHOLDER).fetchSemanticsNodes().isNotEmpty() }
        onNode(codeField()).performTextInput("青松-WRONG1")
        onNodeWithText(PAIRING_BUTTON_LABEL).performClick()
        waitUntil { calls.isNotEmpty() }
        waitForIdle()

        assertEquals(1, calls.size, "恰好一次连接尝试")
        assertEquals("青松-WRONG1", calls.single().second, "尝试的参数 = 输入的码（原样）")
        assertTrue(PairingRuntime.pairedFactory === marker, "成功后桥里装的是这次尝试的 factory")
        assertEquals(
            "10.0.0.9:19999",
            PairingRuntime.pairedEndpoint,
            "成功结果里实际连的 endpoint 原样进桥（真源按它建，不是按启动配置）",
        )
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
        // 失败后输入与按钮仍可用 —— 重试就是再点一次。三个输入框都还在（码框用 tag 定位：
        // `hasSetTextAction()` 在卡上撞 3 个节点）。
        onNodeWithText(PAIRING_BUTTON_LABEL).assertIsEnabled()
        onNode(codeField()).assertExists()
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
        // 同 submitMakes…：卡只在「入口声明过本次启动」后出现（H1）。
        PairingRuntime.noteBooted()
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                FirstRunPairingCard(
                    raw = { RawSourceStrings("real", "127.0.0.1", "8443", null) },
                    onSubmit = { _, _ -> PairingAttempt.Rejected(kind, cause) },
                )
            }
        }
        waitUntil { onAllNodesWithText(PAIRING_PLACEHOLDER).fetchSemanticsNodes().isNotEmpty() }
        onNode(codeField()).performTextInput("青松-WRONG1")
        onNodeWithText(PAIRING_BUTTON_LABEL).performClick()
    }

    /**
     * 码输入框的匹配器。卡上现在有三个可输入框（host / port / 码），语义树上都只有
     * 「可输入文本」这一条 —— host/port 的值非空、占位永不显示，所以只能靠
     * [PAIRING_CODE_FIELD_TAG] 这个 tag 定位码框（`hasSetTextAction()` 单独用会撞 3 个节点）。
     */
    private fun codeField() = hasSetTextAction() and hasAnyAncestor(hasTestTag(PAIRING_CODE_FIELD_TAG))

    // ------------------------------------------------------------- 纯文案

    @Test
    fun headlineAndNextStepArePairwiseDistinct() {
        val raw = realNoCode
        // 每加一种失败分类，标题与下一步都必须**成对**出现、且互不可替换 ——
        // 漏一条 when 分支会在这里立刻红（不是编译错误，是文案缺失）。
        val kinds = PairingFailureKind.entries
        assertEquals(5, kinds.size, "失败分类数量变了：新分类必须同时补 headline / nextStep 文案")
        val headlines = kinds.map { pairingHeadline(it, raw) }
        assertEquals(headlines.size, headlines.toSet().size, "每种失败的标题必须互不相同：$headlines")
        val steps = kinds.map { pairingNextStep(it, raw) }
        assertEquals(steps.size, steps.toSet().size, "每种失败的下一步必须互不相同：$steps")

        // 每条下一步都有可执行动作：取码 / 启动命令 / 重试 / 改端口 / 换设备配对。
        assertTrue(pairingNextStep(PairingFailureKind.BadCode, raw).contains("重新取一个新码"))
        assertTrue(pairingNextStep(PairingFailureKind.ServerDown, raw).contains("PYTHONPATH"))
        assertTrue(pairingNextStep(PairingFailureKind.Network, raw).contains("重试"))
        assertTrue(pairingNextStep(PairingFailureKind.BadConfig, raw).contains("端口填 1..65535"))
        assertTrue(pairingNextStep(PairingFailureKind.NotAvailableHere, raw).contains("桌面应用或手机 App"))
    }
}
