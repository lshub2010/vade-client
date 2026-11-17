package vade.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import vade.ui.data.FakeSessionSource
import vade.ui.destinations.Destination
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 审查 P1-11 + P1-12 的 UI 侧验收钉子。
 *
 * ## P1-11 · 手机空态的「打开会话列表」必须是活的
 *
 * 手机（<600dp）的空态不是 `AppShell` 直接组合的，而是躺在 `SessionsDrawerHost` 的
 * 常驻底层里（`DrawerDetailBase`）。它唯一的出路就是**同一宿主里那层列表抽屉** ——
 * 动作由 `AppShell` 的手机分支用 `LocalSessionListOpener` provide（见该 local 的 KDoc）。
 *
 * 现场怎么造：深链到一个**已不存在**的会话（`initialSessionId = "s-gone"`）——
 * `selectedSessionId != null` 但 `selectedSession == null`，抽屉初始收起（深链语义），
 * 于是屏上真的是空态、按钮是唯一的出路。这正是审查说的「点了没闭路」的那个状态。
 *
 * 断言：按钮存在 → 点它 → `sessionsDrawerOpen` 变 true **且**抽屉层真的滑回屏内
 * （量几何，不看语义树里有没有：抽屉收起时内容照样组合着，只是在屏外）。
 *
 * ## P1-12 · 双栏里重复点当前 tab 不许关掉会话
 *
 * 两个断点各一条：≥840dp 双栏 → 选中保留、右栏不退回空态；600–719 rail 单栏 →
 * 仍旧「退一层」回列表根（P2-1 的语义在这条单栏路径上不变）。
 */
@OptIn(ExperimentalTestApi::class)
class EmptyDetailAndTabRetapTest {

    @Test
    fun thePhoneEmptyStateButtonOpensTheSessionDrawer() = runComposeUiTest {
        // 深链到一条不存在的会话：手机单栏、抽屉初始收起、底层是空态。
        val ui = uiState(initialSessionId = "s-gone")
        shell(ui, width = 411.dp, height = 760.dp)

        waitUntil(timeoutMillis = 10_000) { nodePresent(onNodeWithText("打开会话列表")) }
        assertNull(ui.selectedSession, "现场前提：选中 id 已不在列表里（否则这条路不是空态）")
        assertTrue(!ui.sessionsDrawerOpen, "现场前提：深链直进详情 ⇒ 抽屉初始收起，空态露在外面")

        onNode(hasText("打开会话列表") and hasClickAction()).performClick()

        runOnIdle { assertTrue(ui.sessionsDrawerOpen, "点按钮 ⇒ 抽屉必须滑出（P1-11 的闭路）") }
        // 几何判据：抽屉层从屏外回到屏内（收起时它只是被平移到左边，语义树里一直在）。
        waitUntil(timeoutMillis = 10_000) { drawerLeft() >= -1f }
        waitUntil(timeoutMillis = 10_000) { nodePresent(onNodeWithText(ROW_TITLE)) }
    }

    @Test
    fun twoPaneRetapOnTheSessionsTabKeepsTheDetail() = runComposeUiTest {
        val ui = uiState()
        shell(ui, width = 1000.dp, height = 900.dp) // ≥840 ⇒ rail + 双栏

        waitUntil(timeoutMillis = 10_000) { nodePresent(onNodeWithText(ROW_TITLE)) }
        onNodeWithText(ROW_TITLE).performClick()
        waitUntil(timeoutMillis = 5_000) { ui.selectedSessionId != null }

        // 重复点当前 tab（rail 的「会话」项）。
        onNodeWithTag("vade.nav.rail.icon.sessions", useUnmergedTree = true).performClick()

        runOnIdle {
            assertNotNull(ui.selectedSessionId, "双栏：重复点当前 tab 不许清空选中（P1-12）")
            assertEquals(Destination.Sessions, ui.dest)
        }
        assertTrue(
            onAllNodesWithText("在左侧选择一个会话").fetchSemanticsNodes().isEmpty(),
            "双栏：右栏不许退回空态 —— 那正是「点了个 tab，会话没了」的用户可见后果",
        )
    }

    @Test
    fun railSinglePaneRetapOnTheSessionsTabStillGoesBackToList() = runComposeUiTest {
        val ui = uiState()
        shell(ui, width = 660.dp, height = 900.dp) // 600–719 ⇒ rail 单栏（没有抽屉）

        waitUntil(timeoutMillis = 10_000) { nodePresent(onNodeWithText(ROW_TITLE)) }
        onNodeWithText(ROW_TITLE).performClick()
        waitUntil(timeoutMillis = 5_000) { ui.selectedSessionId != null }

        onNodeWithTag("vade.nav.rail.icon.sessions", useUnmergedTree = true).performClick()

        // 单栏：仍然是「退一层」——选中清空、列表根回来（P2-1 的语义没被 P1-12 改掉）。
        waitUntil(timeoutMillis = 5_000) { ui.selectedSessionId == null }
        waitUntil(timeoutMillis = 10_000) { nodePresent(onNodeWithText(ROW_TITLE)) }
    }

    @Test
    fun twoPaneEmptyStateHasNoOpenListButton() = runComposeUiTest {
        val ui = uiState()
        shell(ui, width = 1000.dp, height = 900.dp)

        // 双栏的空态说的是「在左侧选择一个会话」，列表真的在左边 —— 一个
        // 「打开会话列表」按钮在这里点了没有反应，所以根本不该画（§8.5）。
        waitUntil(timeoutMillis = 10_000) { nodePresent(onNodeWithText("在左侧选择一个会话")) }
        assertTrue(
            onAllNodesWithText("打开会话列表").fetchSemanticsNodes().isEmpty(),
            "双栏没有抽屉：不许画一个点了没反应的「打开会话列表」",
        )
    }

    // ------------------------------------------------------------ harness

    private fun uiState(initialSessionId: String? = null): UiState = UiState(
        initialBar = BottomBarStyle.Floating,
        initialSkinId = "material",
        initialDest = Destination.Sessions,
        initialSessionId = initialSessionId,
        source = FakeSessionSource(),
        loaderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    private fun androidx.compose.ui.test.ComposeUiTest.shell(ui: UiState, width: Dp, height: Dp) {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                Box(Modifier.size(width, height)) { AppShell(ui) }
            }
        }
    }

    /** 抽屉层现在在窗口里的左缘（收起 = 完全平移到屏幕左缘之外）。 */
    private fun androidx.compose.ui.test.ComposeUiTest.drawerLeft(): Float =
        runCatching { onNodeWithTag(DRAWER_TAG).fetchSemanticsNode().boundsInRoot.left }
            .getOrDefault(-Float.MAX_VALUE)

    /** ui-test 1.12 的 desktop 构件没有 `exists()` 扩展：用语义节点抓取成败判存在。 */
    private fun nodePresent(match: androidx.compose.ui.test.SemanticsNodeInteraction): Boolean =
        runCatching { match.fetchSemanticsNode() }.isSuccess

    private companion object {
        /** fixture 会话行标题（`FakeSessions`）：只有会话列表画。 */
        const val ROW_TITLE = "RFC-0004 附件与多模态评审"
    }
}
