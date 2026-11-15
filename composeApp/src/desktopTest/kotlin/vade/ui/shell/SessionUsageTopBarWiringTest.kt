package vade.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import vade.ui.data.FakeSessionSource
import vade.ui.data.SessionSource
import vade.ui.destinations.Destination
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin
import vade.ui.usage.UsageSource

/**
 * g-topbar-usage · AppShell 接线与回归（假源路径，全夹具、无协议）：
 *
 *  - 详情顶栏显示**会话标题 + 用量一句话**，那句「消息流 / 工具卡为假数据」的过期假话消失；
 *  - 切到另一个会话：顶栏数字变化（夹具路径的确定性派生）；
 *  - **回归**：列表页顶栏标题仍是目的地名（「会话」），detail⇄list 后依旧；
 *  - 组合层三态（无数据 → 无副标题；失败 → 重试入口，点一次请求 1→2）——用 seamOverride
 *    直连 [rememberSessionUsageSubtitleLine] + TopBar，与生产完全同一条渲染路径。
 */
@OptIn(ExperimentalTestApi::class)
class SessionUsageTopBarWiringTest {

    private fun state(source: SessionSource): UiState = UiState(
        initialBar = BottomBarStyle.Floating,
        initialSkinId = "material",
        initialDest = Destination.Sessions,
        source = source,
        loaderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    /** 手机宽度（<600dp ⇒ 单栏 pushed detail，detailOpen 才为 true）。 */
    private fun ComposeUiTest.phoneShell(ui: UiState) {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                Box(Modifier.size(480.dp, 900.dp).background(Color(0xFF141414))) {
                    AppShell(ui)
                }
            }
        }
    }

    private fun ComposeUiTest.subtitleText(): String =
        onAllNodesWithText("tokens", substring = true)
            .fetchSemanticsNodes()
            .first()
            .config[SemanticsProperties.Text]
            .single()
            .text

    // ------------------------------------------------ 1 · 详情顶栏 = 标题 + 用量；假话已删

    @Test
    fun detailTopBarShowsTitleAndUsageFakeSentenceIsGone() = runComposeUiTest {
        val ui = state(FakeSessionSource())
        phoneShell(ui)
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("RFC-0004 附件与多模态评审").fetchSemanticsNodes().isNotEmpty()
        }
        // 回归：列表页顶栏标题仍是目的地名
        assertTrue(
            onAllNodesWithText("会话").fetchSemanticsNodes().isNotEmpty(),
            "列表顶栏标题必须是目的地名「会话」",
        )

        ui.selectSession("s-rfc0004")
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("tokens", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        val subtitle = subtitleText()
        assertEquals(
            sessionUsageSubtitleText(fixtureUsageResultOf("s-rfc0004")),
            subtitle,
            "夹具路径显示确定性假数字：$subtitle",
        )
        assertTrue("轮" in subtitle, "副标题形如「«量» tokens · «轮» 轮」：$subtitle")

        // 那句过期假话（既不是用量、又与「聊天已真接线」矛盾）必须消失
        onNodeWithText("会话详情 · 消息流 / 工具卡为假数据").assertDoesNotExist()
    }
    // ------------------------------------------------ 2 · 切会话：数字变化

    @Test
    fun switchingSessionsChangesTheNumbers() = runComposeUiTest {
        val ui = state(FakeSessionSource())
        phoneShell(ui)
        ui.selectSession("s-rfc0004")
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("tokens", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        val first = subtitleText()

        ui.back()
        ui.selectSession("s-matrix")
        waitUntil(timeoutMillis = 10_000) {
            val nodes = onAllNodesWithText("tokens", substring = true).fetchSemanticsNodes()
            nodes.isNotEmpty() && nodes.first().config[SemanticsProperties.Text].single().text != first
        }
        val second = subtitleText()
        assertNotEquals(first, second, "切到另一个会话后顶栏数字必须变化")
        assertEquals(sessionUsageSubtitleText(fixtureUsageResultOf("s-matrix")), second)
    }

    // ------------------------------------------------ 3 · 回归：detail⇄list 顶栏各归各位

    @Test
    fun listTopBarRestoresDestinationLabelAfterDetail() = runComposeUiTest {
        val ui = state(FakeSessionSource())
        phoneShell(ui)
        ui.selectSession("s-matrix")
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("tokens", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        ui.back() // 回列表 —— 同一段淡换的另一头
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("tokens", substring = true).fetchSemanticsNodes().isEmpty()
        }
        assertTrue(
            onAllNodesWithText("会话").fetchSemanticsNodes().isNotEmpty(),
            "返回列表后顶栏标题仍是目的地名",
        )
        onAllNodesWithText("tokens", substring = true).assertCountEquals(0)
    }

    // ------------------------------------------------ 4 · 组合层三态（seam 注入，真源语义）

    private class SeededSeam(
        private val answer: suspend (String) -> vade.ui.protocol.SessionUsageGetResult,
    ) : UsageSource {
        var requests = 0
        override suspend fun load(sessionId: String): vade.ui.protocol.SessionUsageGetResult {
            requests += 1
            return answer(sessionId)
        }
    }

    @Test
    fun realSourceWithoutDataShowsNoSubtitleNeverZero() = runComposeUiTest {
        val seam = SeededSeam { id ->
            vade.ui.protocol.SessionUsageGetResult(
                id, chat = null, aux = null, totalTokens = null, byModel = emptyList(), day = null, budget = null,
            )
        }
        val source = FakeSessionSource() // 提升到组合外 —— 与生产 `state.source`（恒定 val）同语义
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                val line = rememberSessionUsageSubtitleLine(source, "s1", seamOverride = seam)
                TopBar(
                    title = "T",
                    subtitle = when (line) {
                        is UsageSubtitleLine.Text -> line.text
                        is UsageSubtitleLine.Retry -> line.label
                        UsageSubtitleLine.None -> null
                    },
                    animate = false,
                    onSubtitleClick = (line as? UsageSubtitleLine.Retry)?.onRetry,
                )
            }
        }
        waitUntil(timeoutMillis = 10_000) { seam.requests >= 1 }
        // 无数据 → 不显示副标题：任何含 "tokens" 的节点都不存在（编 0 会产出 "0 tokens · 0 轮"）
        onAllNodesWithText("tokens", substring = true).assertCountEquals(0)
    }

    @Test
    fun failureShowsRetryEntryAndOneClickReissuesOnce() = runComposeUiTest {
        val seam = SeededSeam { _ ->
            throw vade.ui.protocol.VadeException(vade.ui.protocol.VadeErrorCodes.UNAVAILABLE, "真源挂了")
        }
        val source = FakeSessionSource() // 提升到组合外 —— 与生产 `state.source`（恒定 val）同语义
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                val line = rememberSessionUsageSubtitleLine(source, "s1", seamOverride = seam)
                TopBar(
                    title = "T",
                    subtitle = when (line) {
                        is UsageSubtitleLine.Text -> line.text
                        is UsageSubtitleLine.Retry -> line.label
                        UsageSubtitleLine.None -> null
                    },
                    animate = false,
                    onSubtitleClick = (line as? UsageSubtitleLine.Retry)?.onRetry,
                )
            }
        }
        waitUntil(timeoutMillis = 10_000) { seam.requests >= 1 }
        // 失败：数字不显示（旧数字绝不残留），重试入口出现（轮询语义树，等重组帧落地）
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(USAGE_RETRY_LABEL).fetchSemanticsNodes().isNotEmpty()
        }
        onAllNodesWithText("tokens", substring = true).assertCountEquals(0)
        assertEquals(1, seam.requests)
        onNodeWithText(USAGE_RETRY_LABEL).performClick()
        waitUntil(timeoutMillis = 10_000) { seam.requests >= 2 }
        assertEquals(2, seam.requests, "重试入口点一次 → 请求次数 1→2")
    }
}
