package vade.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import vade.ui.UiText
import vade.ui.data.FakeSessionSource
import vade.ui.data.FakeSessions
import vade.ui.destinations.Destination
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * g-route-transition · 手机单栏抽屉的验收钉子（2026-11-15 口径变更）：
 *
 *  1. **转场配置**：`animate = true` → 时长 220–300ms、非线性缓动；`animate = false` → 0ms；
 *  2. **开合状态**：点行收抽屉露出详情；返回/Esc 在「列表/详情」间切换且**永不销毁详情**；
 *  3. **遮罩存在**：抽屉开着有遮罩（点它收抽屉），收起后消失；
 *  4. **不重复组合**：两层内容跨开合是**同一个组合实例**（探针计数）——这正是列表滚动
 *     位置保留的机制；
 *  5. **滚动保留**：滚到列表深处 → 开详情 → 返回，那一行**不滚动就仍在原处可见**；
 *  6. **双栏无抽屉**：≥840dp 两栏布局里不存在任何抽屉/遮罩元素；
 *  7. **无障碍焦点**：抽屉重新滑出后焦点回到原来那一行。
 */
@OptIn(ExperimentalTestApi::class)
class SessionsDrawerTest {

    // ------------------------------------------------------------ 1. 转场配置

    @Test
    fun theAnimateOnSpecSitsInsideTheTaskBookWindow() {
        val spec = sessionsDrawerSpec(animate = true)
        assertTrue(
            spec.durationMillis in 220..300,
            "抽屉转场时长必须在 220–300ms（口径）：实测 ${spec.durationMillis}ms",
        )
        assertTrue(
            spec.easing !== androidx.compose.animation.core.LinearEasing,
            "缓动不许是线性（口径「缓动不要线性」）",
        )
        assertTrue(spec.scrimAlpha in 0.2f..0.7f, "遮罩必须真实压暗内容：alpha=${spec.scrimAlpha}")
    }

    @Test
    fun theAnimateOffSpecIsZeroDuration() {
        // 复用 ChatThread(animate = …) 那一族开关：关闭时转场时长必须为 0，内容立即到位。
        assertEquals(0, sessionsDrawerSpec(animate = false).durationMillis)
    }

    @Test
    fun theDrawerGeometryIsTheContractedOne() {
        assertEquals(0.8f, DRAWER_WIDTH_FRACTION, "抽屉宽 = 80% 屏宽（口径）")
        assertEquals(28.dp, DRAWER_END_CORNER, "右缘两个大圆角 ≈28dp（口径）")
        val shape = drawerShape()
        assertEquals(CornerSize(28.dp), shape.topEnd, "topEnd 圆角")
        assertEquals(CornerSize(28.dp), shape.bottomEnd, "bottomEnd 圆角")
        assertEquals(CornerSize(0.dp), shape.topStart, "左缘贴屏幕，直角")
    }

    // ------------------------------------------------------------ 2. 状态机（纯逻辑）

    @Test
    fun drawerFollowsSelectionAndDestinations() {
        val ui = uiState()
        assertTrue(ui.sessionsDrawerOpen, "初始（无深链）抽屉必须开着——屏上是列表")
        ui.open("s-u1a")
        assertFalse(ui.sessionsDrawerOpen, "点一条会话 ⇒ 抽屉收回、详情露出来")
        assertNotNull(ui.selectedSessionId)

        ui.openSessionsDrawer()
        assertTrue(ui.sessionsDrawerOpen, "返回 = 滑出抽屉，详情保留（不是退出详情页）")
        assertNotNull(ui.selectedSessionId, "抽屉滑出后选中会话必须还在——详情没被销毁")

        ui.select(Destination.Activity)
        assertNull(ui.selectedSessionId)
        assertFalse(ui.sessionsDrawerOpen, "离开会话页：抽屉值随统一规则落 false（不渲染，无界面效果）")

        ui.select(Destination.Sessions)
        assertTrue(ui.sessionsDrawerOpen, "回到会话页且无选中 ⇒ 抽屉必须开着（否则手机端停在空白详情）")

        ui.back()
        assertTrue(ui.sessionsDrawerOpen, "选中清空 ⇒ 抽屉开着")

        // 深链/截图环直接进详情：初始收起，与旧行为一致。
        val deepLink = UiState(
            initialBar = BottomBarStyle.Floating,
            initialSkinId = "material",
            initialDest = Destination.Sessions,
            initialSessionId = "s-u1a",
            source = FakeSessionSource(),
        )
        assertFalse(deepLink.sessionsDrawerOpen, "深链直进详情 ⇒ 初始抽屉收起")
    }

    @Test
    fun escTogglesTheDrawerOnSinglePaneAndStillExitsDetailOnTwoPane() {
        val ui = uiState()
        ui.open("s-u1a") // 抽屉收起（详情可见）
        assertTrue(handleShellKeys(keyDown(Key.Escape), ui, twoPane = false))
        assertTrue(ui.sessionsDrawerOpen, "单栏 Esc：详情 → 列表抽屉")
        assertNotNull(ui.selectedSessionId, "Esc 切换不销毁详情")

        assertTrue(handleShellKeys(keyDown(Key.Escape), ui, twoPane = false))
        assertFalse(ui.sessionsDrawerOpen, "单栏 Esc：列表抽屉 → 详情（收抽屉）")
        assertNotNull(ui.selectedSessionId)

        // 双栏没有抽屉：Esc 仍是「退出详情」（FR-8.72 原行为）。
        assertTrue(handleShellKeys(keyDown(Key.Escape), ui, twoPane = true))
        assertNull(ui.selectedSessionId, "双栏 Esc 必须保持退出详情的原语义")
    }

    // ------------------------------------------------------------ 3. UI：开合 / 遮罩 / 预览消失 / 色点仍在

    @Test
    fun tappingARowCollapsesTheDrawerAndRevealsTheDetail() = runComposeUiTest {
        val ui = uiState()
        shellContent(ui, width = 500.dp, height = 900.dp)

        waitUntil(timeoutMillis = 10_000) { nodePresent(onNodeWithText(ROW_TITLE)) }
        waitUntil(timeoutMillis = 10_000) { nodePresent(onNodeWithTag(DRAWER_SCRIM_TAG)) }

        // Q2-B：屏上不再有预览文本；分组色点仍在（色点语义 = 定位锚）。
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText(FIRST_ROW_PREVIEW).fetchSemanticsNodes().isEmpty()
        }
        waitUntil(timeoutMillis = 5_000) {
            // 两行 fixture 同属 g-1（s-rfc0004 / s-agp）⇒ 色点语义至少一条；用计数判存在
            //（单节点 finder 在多匹配时会抛，fetchSemanticsNode 收不出结果）。
            onAllNodesWithContentDescription("分组：项目").fetchSemanticsNodes().isNotEmpty()
        }

        // 点行 → 抽屉收回 → 详情露出来（顶栏出现「返回」= 详情 chrome）。
        onNodeWithText(ROW_TITLE).performClick()
        waitUntil(timeoutMillis = 10_000) { nodePresent(onNodeWithContentDescription("返回")) }
        assertFalse(ui.sessionsDrawerOpen)

        // 抽屉收起后列表**整体平移出屏**（列表层保持组合——滚动位置因此保留）：
        // 断言抽屉层的 bounds 完全退到屏幕左缘之外；invisibleToUser 只对读屏生效，
        // 桌面 ui-test 的搜索不跳过它，所以这里量几何而不是查「语义树里有没有」。
        waitUntil(timeoutMillis = 10_000) { drawerOffscreen() }

        // 返回（抽屉滑出）→ 遮罩回来、列表回屏、详情仍被选中（不销毁）。
        runOnIdle { ui.openSessionsDrawer() }
        waitUntil(timeoutMillis = 10_000) { nodePresent(onNodeWithTag(DRAWER_SCRIM_TAG)) }
        waitUntil(timeoutMillis = 10_000) { !drawerOffscreen() }
        waitUntil(timeoutMillis = 10_000) { nodePresent(onNodeWithText(ROW_TITLE)) }
        assertNotNull(ui.selectedSessionId, "返回后详情必须还在底层（不是退出详情页）")

        // 点遮罩 = 收抽屉（露出详情）。
        onNodeWithTag(DRAWER_SCRIM_TAG).performClick()
        waitUntil(timeoutMillis = 10_000) { drawerOffscreen() }
        assertFalse(ui.sessionsDrawerOpen)
    }

    /** 抽屉层是否已完全平移到屏幕左缘之外（收起态的几何判据）。 */
    private fun androidx.compose.ui.test.ComposeUiTest.drawerOffscreen(): Boolean =
        runCatching {
            onNodeWithTag(DRAWER_TAG).fetchSemanticsNode().boundsInRoot.right <= 1f
        }.getOrDefault(false)

    // ------------------------------------------------------------ 4. 内容只组合一次

    @Test
    fun eachLayerIsComposedExactlyOnceAcrossOpenCloseCycles() = runComposeUiTest {
        val ui = uiState()
        ui.selectSession("s-u1a")
        ui.closeSessionsDrawer()
        var baseInstances = 0
        var drawerInstances = 0

        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                Box(Modifier.size(500.dp, 900.dp)) {
                    SessionsDrawerHost(
                        state = ui,
                        adapters = null,
                        contentInsets = PaddingValues(0.dp),
                        animate = true,
                        base = { probe { baseInstances++ } },
                        drawer = { probe { drawerInstances++ } },
                    )
                }
            }
        }
        waitForIdle()
        assertEquals(1, baseInstances, "详情层首组合恰好一次")
        assertEquals(1, drawerInstances, "列表层首组合恰好一次")

        // 开 → 合 → 开 → 合（每次带 260ms 转场）：两层都不得重建。
        repeat(2) {
            runOnIdle { ui.openSessionsDrawer() }
            waitForIdle()
            runOnIdle { ui.closeSessionsDrawer() }
            waitForIdle()
        }
        assertEquals(1, baseInstances, "详情层跨开合必须保持同一组合实例（数据层不重建两次）")
        assertEquals(1, drawerInstances, "列表层跨开合必须保持同一组合实例（滚动位置由此保留）")
    }

    /** 探针内容体：`remember` 只在该组合实例的首次进入时跑一次——重建即计数增加。 */
    @Composable
    private fun probe(onFirst: () -> Unit) {
        remember { onFirst() }
        UiText("drawer-probe", size = 10)
    }

    // ------------------------------------------------------------ 5. 滚动位置保留

    @Test
    fun listScrollPositionSurvivesOpenCloseCycles() = runComposeUiTest {
        val ui = uiState()
        // 500×450：列表视口矮，深处行初始不可见（才需要滚动，才能验证位置保留）。
        shellContent(ui, width = 500.dp, height = 450.dp)

        waitUntil(timeoutMillis = 10_000) { nodePresent(onNodeWithText(ROW_TITLE)) }

        val deepRow = deepRowTitle()
        waitUntil(timeoutMillis = 10_000) { !nodePresent(onNodeWithText(deepRow)) }

        // 滚动直到深处的行进入视口。
        repeat(6) {
            if (!onAllNodesWithText(deepRow).fetchSemanticsNodes().isNotEmpty()) {
                onRoot().performTouchInput { swipeUp() }
                waitForIdle()
            }
        }
        waitUntil(timeoutMillis = 10_000) { nodePresent(onNodeWithText(deepRow)) }

        // 开详情（抽屉收回）→ 返回（抽屉滑出）。
        onNodeWithText(deepRow).performClick()
        waitUntil(timeoutMillis = 10_000) { nodePresent(onNodeWithContentDescription("返回")) }
        runOnIdle { ui.openSessionsDrawer() }
        waitForIdle()

        // 不再滚动：那一行必须直接可见——滚动位置在开合后保留（列表层从未离开组合）。
        waitUntil(timeoutMillis = 10_000) { nodePresent(onNodeWithText(deepRow)) }
        assertTrue(
            onAllNodesWithText(deepRow).fetchSemanticsNodes().isNotEmpty(),
            "返回后列表深处的那一行必须不滚动就可见（滚动位置保留）",
        )
    }

    // ------------------------------------------------------------ 6. 无障碍焦点

    @Test
    fun reopeningTheDrawerReturnsFocusToThePreviouslySelectedRow() = runComposeUiTest {
        val ui = uiState()
        shellContent(ui, width = 500.dp, height = 900.dp)

        waitUntil(timeoutMillis = 10_000) { nodePresent(onNodeWithText(ROW_TITLE)) }
        onNodeWithText(ROW_TITLE).performClick()
        waitUntil(timeoutMillis = 10_000) { nodePresent(onNodeWithContentDescription("返回")) }

        runOnIdle { ui.openSessionsDrawer() }
        waitUntil(timeoutMillis = 10_000) { nodePresent(onNodeWithText(ROW_TITLE)) }
        waitForIdle()

        onNodeWithText(ROW_TITLE).assertIsFocused()
    }

    // ------------------------------------------------------------ 7. 双栏无抽屉

    @Test
    fun twoPaneBreakpointNeverComposesDrawerElements() = runComposeUiTest {
        val ui = uiState()
        shellContent(ui, width = 1000.dp, height = 900.dp) // ≥840 → 两栏
        waitUntil(timeoutMillis = 10_000) { nodePresent(onNodeWithText(ROW_TITLE)) }

        onNodeWithTag(DRAWER_TAG).assertDoesNotExist()
        onNodeWithTag(DRAWER_SCRIM_TAG).assertDoesNotExist()

        // 双栏点行：详情进右栏（原有行为）——双栏顶栏保持列表 chrome（没有「返回」按钮），
        // 抽屉元素仍然不存在，抽屉状态也不参与。
        onNodeWithText(ROW_TITLE).performClick()
        runOnIdle {
            assertNotNull(ui.selectedSessionId, "双栏点行必须选中会话（详情进右栏）")
            // 双栏不渲染抽屉：sessionsDrawerOpen 是死状态（selectSession 照常写它，无界面消费）。
        }
        onNodeWithTag(DRAWER_TAG).assertDoesNotExist()
        onNodeWithTag(DRAWER_SCRIM_TAG).assertDoesNotExist()
        onNodeWithContentDescription("新建会话").assertExists()
    }

    // ------------------------------------------------------------ harness

    private fun uiState(): UiState = UiState(
        initialBar = BottomBarStyle.Floating,
        initialSkinId = "material",
        initialDest = Destination.Sessions,
        source = FakeSessionSource(),
        loaderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    private fun androidx.compose.ui.test.ComposeUiTest.shellContent(ui: UiState, width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp) {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                Box(Modifier.size(width, height)) {
                    AppShell(ui)
                }
            }
        }
    }

    /**
     * ui-test 1.12（desktop 构件）的 `SemanticsNodeInteraction` 没有 `exists()` 扩展，
     * 也没有可导入的顶层符号——统一用「语义节点抓取成败」判存在。
     */
    private fun nodePresent(match: androidx.compose.ui.test.SemanticsNodeInteraction): Boolean =
        runCatching { match.fetchSemanticsNode() }.isSuccess

    private fun deepRowTitle(): String =
        FakeSessions.groups().last().sessions.last().title

    @OptIn(InternalComposeUiApi::class)
    private fun keyDown(key: Key): KeyEvent = KeyEvent(
        key = key,
        type = KeyEventType.KeyDown,
    )

    private companion object {
        const val ROW_TITLE = "RFC-0004 附件与多模态评审"
        const val FIRST_ROW_PREVIEW = "把 POST /ui/blob 的分片与断点续传写进 §3…"
    }
}
