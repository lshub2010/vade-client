package vade.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import vade.ui.destinations.Destination
import vade.ui.search.SearchHost
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin

/**
 * g-back-handler — 返回栈的验收。任务书四条，每条都有判据在这里变红：
 *
 * 1. 开浮层 → 发返回 → **浮层从语义树上消失**、页面还在（不是退出）；
 * 2. 两层叠放 → 只关最上层（`topmost wins`：后注册的先处理）；
 * 3. 没开浮层 → 返回交还 —— [BackDispatcher.dispatch] 返回 `false`（未消费），
 *    桥的 `enabled` 也不拦截；
 * 4. 浮层关后栈清干净 —— 开关 5 次 [BackDispatcher.depth] 每次都回到 0（不泄漏）。
 *
 * 另外两块接线各有一条真实路径的测试：
 *  - 桌面 Esc 经 `handleShellKeys` 先问返回栈（栈顶优先，栈空回落既有语义）；
 *  - **真的 SearchHost**（不是替身）开着时注册恰好一层，`dispatch()` 后浮层关闭、
 *    层出栈 —— 「调了回调」与「浮层真的没了」在这里由语义树断言区分开。
 */
@OptIn(ExperimentalTestApi::class)
class BackDispatchTest {

    // --- 3 · 栈空交还 -----------------------------------------------------------

    @Test
    fun emptyStackHandsBackToTheSystem() {
        val d = BackDispatcher()
        assertFalse(d.isHandling, "没有浮层时不接管")
        assertEquals(0, d.depth)
        assertFalse(d.dispatch(), "dispatch 必须返回 false = 未消费，交还系统（允许退出）")
    }

    // --- 2 · topmost wins -------------------------------------------------------

    @Test
    fun twoStackedLayersOnlyTheTopmostConsumesBack() {
        val d = BackDispatcher()
        var bottomClosed = 0
        var topClosed = 0
        val bottom = d.push { bottomClosed++ }
        val top = d.push { topClosed++ }

        assertTrue(d.dispatch(), "栈非空时返回被消费")
        assertEquals(1, topClosed, "最上层先处理")
        assertEquals(0, bottomClosed, "下层不能同时被关")

        // 浮层真的关闭（组合离开）→ 出栈 → 下一层顶上
        d.pop(top)
        assertTrue(d.isHandling, "底层浮层还开着，栈必须还有一层")
        assertTrue(d.dispatch())
        assertEquals(1, bottomClosed)

        d.pop(bottom)
        assertFalse(d.isHandling)
        assertEquals(0, d.depth)
        assertFalse(d.dispatch(), "栈空后再次返回交还系统")
    }

    // --- 2b · 优先级：先压入的 ALERT 层压过后压入的普通层（f18 复审 P1-3） --------------

    /**
     * 栈序按**入栈时刻**算：`CoreSwitchPanel` 的 busy 层先组合、后启用，就会压在自签证书
     * 警告（先入栈）之上。优先级让「必然压住整页」的确认层永远先被问到；同优先级仍按栈序。
     */
    @Test
    fun anAlertLayerOutranksALaterPushedDefaultLayer() {
        val d = BackDispatcher()
        var alertClosed = 0
        var busySwallowed = 0

        val alert = d.push(BackDispatcher.PRIORITY_ALERT) { alertClosed++ }
        val busy = d.push { busySwallowed++ } // 后压入 ⇒ 没有优先级时它会赢

        assertTrue(d.dispatch(), "栈非空，返回被消费")
        assertEquals(1, alertClosed, "ALERT 层必须先处理，哪怕普通层后压入")
        assertEquals(0, busySwallowed, "busy 的 no-op 不许吃掉这次返回")

        // 警告关掉（出栈）之后，才轮到 busy 那层 —— 同优先级 / 剩余层仍按栈序。
        d.pop(alert)
        assertTrue(d.dispatch())
        assertEquals(1, busySwallowed)
        d.pop(busy)
        assertEquals(0, d.depth)
        assertFalse(d.dispatch(), "栈空交还系统")
    }

    // --- 1 · 浮层真的没了（语义树级） ---------------------------------------------

    @Test
    fun backClosesTheOverlayForRealAndThePageSurvives() = runComposeUiTest {
        val d = BackDispatcher()
        var open by mutableStateOf(true)
        setContent {
            CompositionLocalProvider(LocalBackDispatcher provides d) {
                Box(Modifier.fillMaxSize()) {
                    BasicText("G1_PAGE", Modifier.align(Alignment.TopStart))
                    if (open) {
                        BackLayer(enabled = true, onBack = { open = false }) {
                            BasicText("G1_OVERLAY")
                        }
                    }
                }
            }
        }

        onNodeWithText("G1_OVERLAY").assertExists()
        assertTrue(d.isHandling)

        assertTrue(d.dispatch(), "返回被浮层消费")
        waitUntil { !open }
        onNodeWithText("G1_OVERLAY").assertDoesNotExist()
        assertFalse(d.isHandling, "浮层关后栈清干净")
        assertEquals(0, d.depth)
        onNodeWithText("G1_PAGE").assertExists()
    }

    // --- 4 · 开关 5 次不泄漏 ------------------------------------------------------

    @Test
    fun repeatedOpenCloseDoesNotLeakLayers() = runComposeUiTest {
        val d = BackDispatcher()
        var open by mutableStateOf(false)
        setContent {
            CompositionLocalProvider(LocalBackDispatcher provides d) {
                if (open) {
                    BackLayer(enabled = true, onBack = { open = false }) {
                        BasicText("G1_LEAK_PROBE")
                    }
                }
            }
        }

        repeat(5) { round ->
            open = true
            waitUntil { d.isHandling }
            assertEquals(1, d.depth, "第 ${round + 1} 次打开：恰好一层")
            onNodeWithText("G1_LEAK_PROBE").assertExists()

            assertTrue(d.dispatch())
            waitUntil { !open }
            waitUntil { d.depth == 0 }
            assertFalse(d.isHandling, "第 ${round + 1} 次关闭后栈必须清干净（不泄漏）")
            onNodeWithText("G1_LEAK_PROBE").assertDoesNotExist()
        }
    }

    // --- 组合序注册：两层同屏只关最上层 ---------------------------------------------

    @Test
    fun compositionOrderRegistersTopmostLast() = runComposeUiTest {
        val d = BackDispatcher()
        var sheetOpen by mutableStateOf(true)
        var dialogOpen by mutableStateOf(true)
        var sheetClosedByBack = 0
        var dialogClosedByBack = 0
        setContent {
            CompositionLocalProvider(LocalBackDispatcher provides d) {
                // 组合序 = 栈序：后组成的（dialog）是视觉/逻辑上的最上层。
                BackLayer(enabled = sheetOpen, onBack = { sheetClosedByBack++; sheetOpen = false })
                if (dialogOpen) {
                    BackLayer(enabled = true, onBack = { dialogClosedByBack++; dialogOpen = false }) {
                        BasicText("G1_TOPMOST")
                    }
                }
            }
        }

        waitUntil { d.isHandling }
        assertEquals(2, d.depth, "两层同时开着")
        onNodeWithText("G1_TOPMOST").assertExists()

        assertTrue(d.dispatch())
        assertEquals(1, dialogClosedByBack, "只关最上层")
        assertEquals(0, sheetClosedByBack)
        assertTrue(sheetOpen, "底层浮层必须还开着")
        waitUntil { d.depth == 1 }

        assertTrue(d.dispatch())
        assertEquals(1, sheetClosedByBack)
        waitUntil { d.depth == 0 }
        assertFalse(d.isHandling)
    }

    // --- 桌面 Esc：经 handleShellKeys 先问返回栈 ------------------------------------

    @OptIn(InternalComposeUiApi::class)
    private fun keyDown(key: Key, ctrl: Boolean = false, meta: Boolean = false): KeyEvent = KeyEvent(
        key = key,
        type = KeyEventType.KeyDown,
        isCtrlPressed = ctrl,
        isMetaPressed = meta,
    )

    private fun uiState() = UiState(BottomBarStyle.Fixed, "material", Destination.Sessions)

    @Test
    fun escIsClaimedByTheStackBeforeTheLegacyBranches() {
        val d = BackDispatcher()
        var topmostClosed = 0
        val top = d.push { topmostClosed++ }

        val state = uiState()
        state.openSearch() // 既有语义也会认 Esc —— 但栈必须先拿到它

        assertTrue(handleShellKeys(keyDown(Key.Escape), state, backDispatcher = d), "Esc 被返回栈消费")
        assertEquals(1, topmostClosed)
        assertTrue(state.search.open, "栈优先：既有的「Esc 关搜索」分支不得抢先（这里只有假层，搜索没人关）")

        d.pop(top)
        // 栈空 → dispatch() = false → 回落既有语义（此时只开着搜索）：关搜索。
        assertTrue(handleShellKeys(keyDown(Key.Escape), state, backDispatcher = d), "栈空后 Esc 走既有语义")
        assertFalse(state.search.open)
    }

    @Test
    fun emptyStackEscStillFallsThroughToNotOurs() {
        val d = BackDispatcher()
        val state = uiState()
        assertFalse(handleShellKeys(keyDown(Key.Escape), state, backDispatcher = d), "无浮层无会话：Esc 不是我们的")
        assertFalse(state.search.open)
        assertEquals(null, state.selectedSessionId)
    }

    // --- 真的 SearchHost：注册恰好一层，dispatch 后浮层真的没了 -----------------------

    @Test
    fun realSearchHostRegistersOneLayerAndBackClosesTheOverlay() = runComposeUiTest {
        // 浮层两种形态（<600dp 卡片 / ≥600dp 命令面板）的标题，开着时必有其一。
        fun overlayTitleExists() =
            onAllNodesWithText("命令面板").fetchSemanticsNodes().isNotEmpty() ||
                onAllNodesWithText("搜索会话").fetchSemanticsNodes().isNotEmpty()

        val d = BackDispatcher()
        val state = UiState(BottomBarStyle.Fixed, "material", Destination.Sessions)
        setContent {
            CompositionLocalProvider(
                LocalSkin provides MaterialSkin(),
                LocalBackDispatcher provides d,
            ) {
                SearchHost(state)
            }
        }

        state.openSearch()
        state.updateSearchQuery("AGP")
        waitUntil { d.isHandling }
        assertEquals(1, d.depth, "搜索浮层开着时注册恰好一层")
        assertTrue(overlayTitleExists(), "浮层在语义树上")

        assertTrue(d.dispatch(), "返回被搜索浮层消费")
        waitUntil { !state.search.open }
        waitUntil { !d.isHandling }
        assertEquals(0, d.depth, "浮层关闭后栈清干净")
        assertFalse(overlayTitleExists(), "浮层真的从语义树消失（不是「调了个回调」）")
    }
}
