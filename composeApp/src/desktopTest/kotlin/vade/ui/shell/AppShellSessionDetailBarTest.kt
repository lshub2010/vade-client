package vade.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import vade.ui.data.FakeSessionSource
import vade.ui.destinations.Destination
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * m-bar · 手机单栏「底栏常驻 + 一键离开会话」的验收钉子。
 *
 * 口径（用户原话）：会话页底栏**常驻**；点其他 tab **直接离开会话**（不弹确认）。
 *
 * 本文件钉住四件事，全部在真实的 `AppShell` 组合里量，不测助手函数：
 *
 *  1. **详情页上底栏还在** —— 旧 D88 说手机单栏 push 详情时整条栏不组合，所以只需要
 *     断言「四个 tab 的语义节点都在」就能把回归钉死（改回去就少四个节点）；
 *  2. **点别的 tab 直接离开会话** —— `dest` 切过去、`selectedSessionId` 变 null、详情
 *     chrome（「返回」）消失、目标页自己的内容出现；全程没有任何确认框节点；
 *  3. **内容不被底栏遮住** —— 用 `PhoneContentStageTag` 那一层的渲染几何对齐
 *     [barZoneFor]（同一个常量、同一个 inset），并断言四个 tab 全都在它下面；列表根
 *     （没有详情）时这一层**不**多留（留白只在详情态存在）；
 *  4. **「主页」按钮** 回到会话列表（手机 = 抽屉滑出）。
 *
 * ## 为什么用 `0.dp` 的 inset
 *
 * 桌面 JVM 的 `navigationBarBottomInset()` actual 是 0（`InsetsGuardTest` 在真窗口里量过，
 * `AppShellBottomZoneTest` 的 KDoc 也写明同一件事），所以这里的期望值就是
 * `barZoneFor(style, 0.dp)`。本文件不重复算数字 —— 它就是被测代码用的那个函数。
 *
 * ## 没验证到的（诚实边界）
 *
 * 桌面测试无法跑 Gradle（本机禁用），Android 真机上的 `24/48 dp` inset 那两档只由
 * `AppShellBottomZoneTest` 的纯算术覆盖；底栏玻璃的模糊观感也只能上机看。
 */
@OptIn(ExperimentalTestApi::class)
class AppShellSessionDetailBarTest {

    // ------------------------------------------------------------ 夹具

    private fun fixtureState(): UiState = UiState(
        initialBar = BottomBarStyle.Floating,
        initialSkinId = "material",
        initialDest = Destination.Sessions,
        source = FakeSessionSource(),
    )

    /** 打开一个 fixture 会话：手机单栏上这就是「详情露出、抽屉收起」。 */
    private fun detailState(): UiState = fixtureState().apply { open("s-u1a") }

    private fun ComposeUiTest.phoneShell(ui: UiState) {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                // 400×800 ⇒ <600dp 手机单栏（底栏 + 抽屉宿主）。外层带 tag 是为了量
                // 「窗口底边」——`onRoot()` 是测试宿主，不是这个 Box。
                Box(Modifier.size(PHONE_WIDTH, PHONE_HEIGHT).testTag(PHONE_ROOT_TAG)) { AppShell(ui) }
            }
        }
    }

    private fun nodePresent(match: SemanticsNodeInteraction): Boolean =
        runCatching { match.fetchSemanticsNode() }.isSuccess

    /** 合并语义树里每个 tab 恰好一个节点带 label（`BottomBarSemanticsTest` 的另一半口径）。 */
    private fun ComposeUiTest.countWithDescription(text: String): Int =
        onAllNodesWithContentDescription(text).fetchSemanticsNodes().size

    /** 带容差的像素比较（`kotlin.test.assertEquals` 的 Float 重载没有 tolerance 版本够直观）。 */
    private fun assertNear(expected: Float, actual: Float, tolerance: Float, message: String) {
        assertTrue(
            kotlin.math.abs(expected - actual) <= tolerance,
            "$message（期望 $expected ± $tolerance，实际 $actual）",
        )
    }

    // ------------------------------------------------------------ 1 · 底栏常驻

    @Test
    fun sessionDetailOnAPhoneKeepsTheBottomBarComposed() = runComposeUiTest {
        val ui = detailState()
        phoneShell(ui)

        waitUntil(timeoutMillis = 10_000) { nodePresent(onNodeWithContentDescription("返回")) }
        assertTrue(
            ui.selectedSessionId != null,
            "前置条件：进入详情（没有选中会话时这一屏根本不是详情）",
        )

        Destination.entries.forEach { destination ->
            assertEquals(
                1,
                countWithDescription(destination.label),
                "会话详情页上底栏必须常驻：缺「${destination.label}」这个 tab 就是退回 D88（栏不组合）",
            )
        }
    }

    // ------------------------------------------------------------ 2 · 点其他 tab 直接离开

    @Test
    fun tappingAnotherTabLeavesTheSessionDetailWithoutAnyConfirmation() = runComposeUiTest {
        val ui = detailState()
        phoneShell(ui)
        waitUntil(timeoutMillis = 10_000) { nodePresent(onNodeWithContentDescription("返回")) }

        onNodeWithContentDescription("活动").performClick()
        waitForIdle()

        assertEquals(Destination.Activity, ui.dest, "点「活动」必须真的切到活动页")
        assertNull(ui.selectedSessionId, "点其他 tab 必须直接离开会话（不许留着详情等确认）")
        assertEquals(
            0,
            countWithDescription("返回"),
            "离开会话后详情 chrome 必须消失：还留着「返回」说明详情没退掉",
        )
        // 没有任何确认框：确认框会把「取消/确认」这类文案塞进语义树。
        assertEquals(
            0,
            onAllNodesWithText("确认").fetchSemanticsNodes().size,
            "离开会话不许弹确认框（用户明确要求「直接离开」）",
        )
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("运行中").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** 点回**会话 tab 本身**也走同一条路：先离开会话，再落到列表根（手机 = 抽屉滑出）。 */
    @Test
    fun tappingTheSessionsTabFromDetailBacksOutToTheListRoot() = runComposeUiTest {
        val ui = detailState()
        phoneShell(ui)
        waitUntil(timeoutMillis = 10_000) { nodePresent(onNodeWithContentDescription("返回")) }

        // DIAG（临时）：点之前把「会话」节点的语义打印出来，定位点到的是不是底栏项。
        run {
            val nodes = onAllNodesWithContentDescription("会话").fetchSemanticsNodes()
            val dump = nodes.joinToString(" | ") { n ->
                val cfg = n.config
                "role=" + cfg.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Role) +
                    " selected=" + cfg.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Selected) +
                    " onClick=" + (cfg.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick) != null) +
                    " bounds=" + n.boundsInRoot
            }
            assertTrue(false, "DIAG nodes=${nodes.size}: $dump")
        }
        onNodeWithContentDescription("会话").performClick()
        waitForIdle()

        assertNull(ui.selectedSessionId, "重复点会话 tab = 退一层（P2-1 的既有语义）")
        assertTrue(ui.sessionsDrawerOpen, "手机上退回列表 = 抽屉滑出（列表是唯一出口）")
        assertEquals(Destination.Sessions, ui.dest)
    }

    // ------------------------------------------------------------ 3 · 内容不被底栏遮住

    @Test
    fun theDetailStageStopsExactlyAtTheBarZone() = runComposeUiTest {
        val ui = detailState()
        phoneShell(ui)
        waitUntil(timeoutMillis = 10_000) { nodePresent(onNodeWithTag(PhoneContentStageTag)) }
        waitUntil(timeoutMillis = 10_000) { nodePresent(onNodeWithContentDescription("返回")) }

        val stage = onNodeWithTag(PhoneContentStageTag).fetchSemanticsNode().boundsInRoot
        val window = onNodeWithTag(PHONE_ROOT_TAG).fetchSemanticsNode().boundsInRoot

        with(density) {
            // 同一个常量、同一个 inset：期望值就是被测代码用的 barZoneFor（不在测试里写 104）。
            val zone = barZoneFor(BottomBarStyle.Floating, 0.dp).toPx()
            assertNear(
                expected = window.bottom - zone,
                actual = stage.bottom,
                tolerance = 1f,
                message = "详情层（消息列表 + composer）底边必须正好停在底栏区上沿：多留 = 白边，少留 = 被玻璃压住",
            )
            Destination.entries.forEach { destination ->
                val tab = onNodeWithContentDescription(destination.label)
                    .fetchSemanticsNode().boundsInRoot
                assertTrue(
                    tab.top >= stage.bottom - 0.5f,
                    "「${destination.label}」tab 顶边 ${tab.top} 压在内容底边 ${stage.bottom} 之上 —— 底栏遮住了会话内容",
                )
            }
        }
    }

    /** 列表根（没有详情）时不许跟着多留一条：留白只服务「详情贴屏底的 composer」。 */
    @Test
    fun theListRootReservesNoExtraBottomSpace() = runComposeUiTest {
        val ui = fixtureState()
        phoneShell(ui)
        waitUntil(timeoutMillis = 10_000) { nodePresent(onNodeWithTag(PhoneContentStageTag)) }

        val stage = onNodeWithTag(PhoneContentStageTag).fetchSemanticsNode().boundsInRoot
        val window = onNodeWithTag(PHONE_ROOT_TAG).fetchSemanticsNode().boundsInRoot
        with(density) {
            assertNear(
                expected = window.bottom,
                actual = stage.bottom,
                tolerance = 1f,
                message = "列表根的宿主必须铺满到窗口底 —— 列表行靠 contentInsets 滚到栏后，容器不再留一条空带",
            )
        }
    }

    // ------------------------------------------------------------ 4 · 顶栏「主页」

    @Test
    fun theHomeButtonReturnsToTheSessionsList() = runComposeUiTest {
        val ui = detailState()
        phoneShell(ui)
        waitUntil(timeoutMillis = 10_000) { nodePresent(onNodeWithContentDescription("主页")) }

        onNodeWithContentDescription("主页").performClick()
        waitForIdle()

        assertNull(ui.selectedSessionId, "「主页」必须离开会话，回到会话列表")
        assertTrue(ui.sessionsDrawerOpen, "手机上回到会话列表 = 列表抽屉滑出")
        assertEquals(Destination.Sessions, ui.dest)
        assertEquals(0, countWithDescription("返回"), "回到列表后详情 chrome 必须消失")
    }
}

/** 手机窗口尺寸（<600dp ⇒ 底栏 + 单栏）。 */
private val PHONE_WIDTH = 400.dp
private val PHONE_HEIGHT = 800.dp

/** 外层窗口的 tag —— `onRoot()` 是测试宿主，量窗口底边只能量这个 Box。 */
private const val PHONE_ROOT_TAG = "m-bar.phone.root"
