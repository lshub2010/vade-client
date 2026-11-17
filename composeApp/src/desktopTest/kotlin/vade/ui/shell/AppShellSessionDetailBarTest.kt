package vade.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import vade.ui.data.FakeSessionSource
import vade.ui.destinations.Destination
import vade.ui.destinations.DetailContentInsetTag
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * m-bar · 手机单栏「底栏常驻 + 一键离开会话」的验收钉子。
 *
 * 口径（用户原话）：会话页底栏**常驻**；点其他 tab **直接离开会话**（不弹确认）。
 *
 * 本文件钉住这些事，全部在真实的 `AppShell` 组合里量，不测助手函数：
 *
 *  1. **详情页上底栏还在** —— 旧 D88 说手机单栏 push 详情时整条栏不组合，所以只需要
 *     断言「四个 tab 的语义节点都在」就能把回归钉死（改回去就少四个节点）；
 *  2. **点别的 tab 直接离开会话** —— `dest` 切过去、`selectedSessionId` 变 null、详情
 *     chrome（「返回」）消失、目标页自己的内容出现；全程没有任何确认框节点；
 *  3. **留白只加在内容链上** —— 容器（[PhoneContentStageTag]）满高；「消息列表 + composer」
 *     那一层（[DetailContentInsetTag]）底边 = 窗口底 − 栏区（桌面 nav = 0）。**抽屉与遮罩
 *     不受影响**：拉开抽屉后它们仍然贴屏底（审查 P1-2/P1-3 的回归探针）；
 *  4. **模态浮层盖住底栏** —— 模型面板的遮罩铺到窗口底，四个 tab 的矩形都落在遮罩里
 *     （旧实现：容器垫 104 dp ⇒ 遮罩停在屏底上方、胶囊漂在遮罩外面还能点）；
 *  5. **「主页」按钮** 回到会话列表（手机 = 抽屉滑出）；抽屉展开时它**不出现**（P2-2）；
 *  6. **拖动胶囊又落回原位不是点击** —— 不许把会话退掉（P2-4）。
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
 * `DetailBarInsetTest` 的纯算术覆盖；底栏玻璃的模糊观感也只能上机看。
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

        onNodeWithContentDescription("会话").performClick()
        waitForIdle()

        assertNull(ui.selectedSessionId, "重复点会话 tab = 退一层（P2-1 的既有语义）")
        assertTrue(ui.sessionsDrawerOpen, "手机上退回列表 = 抽屉滑出（列表是唯一出口）")
        assertEquals(Destination.Sessions, ui.dest)
    }

    // ------------------------------------------------------------ 3 · 留白只加在内容链上

    /**
     * 容器满高、内容链让栏（审查 P1-1/P1-2 的几何钉子）。
     *
     * 旧用例量的是**容器**（`PhoneContentStageTag`）底边 == 窗口底 − 栏区 —— 它只能证明
     * 「我加的那条 padding 等于我自己算的常量」，碰不到 composer 一根手指，也看不见抽屉 /
     * 浮层被一起抬离屏底。现在分两条量：
     *  - 容器满高（底边 == 窗口底）⇒ 抽屉 / 遮罩 / 浮层不可能被容器裁；
     *  - 内容链（[DetailContentInsetTag]）底边 == 窗口底 − 栏区（桌面 nav = 0，与 composer
     *    自带的 16 dp + 0 一起，胶囊底边落在栏区上沿之上 16 dp）。
     */
    @Test
    fun theContainerIsFullBleedAndOnlyTheContentChainReservesTheBarZone() = runComposeUiTest {
        val ui = detailState()
        phoneShell(ui)
        waitUntil(timeoutMillis = 10_000) { nodePresent(onNodeWithTag(PhoneContentStageTag)) }
        waitUntil(timeoutMillis = 10_000) { nodePresent(onNodeWithContentDescription("返回")) }
        waitUntil(timeoutMillis = 10_000) { nodePresent(onNodeWithTag(DetailContentInsetTag)) }

        val stage = onNodeWithTag(PhoneContentStageTag).fetchSemanticsNode().boundsInRoot
        val chain = onNodeWithTag(DetailContentInsetTag).fetchSemanticsNode().boundsInRoot
        val window = onNodeWithTag(PHONE_ROOT_TAG).fetchSemanticsNode().boundsInRoot

        with(density) {
            // 同一个常量、同一个 inset：期望值就是被测代码用的 barZoneFor（不在测试里写 104）。
            val zone = barZoneFor(BottomBarStyle.Floating, 0.dp).toPx()
            assertNear(
                expected = window.bottom,
                actual = stage.bottom,
                tolerance = 1f,
                message = "手机单栏的内容容器必须铺到窗口底 —— 它一垫，抽屉 / 遮罩 / 模型面板 / " +
                    "图片预览就跟着被抬离屏底（P1-2 的根因）",
            )
            assertNear(
                expected = window.bottom - zone,
                actual = chain.bottom,
                tolerance = 1f,
                message = "「消息列表 + composer」那条内容链的底边必须正好停在底栏区上沿：多留 = 白边，" +
                    "少留 = 输入框被玻璃压住",
            )
            assertTrue(
                chain.top >= stage.top - 0.5f,
                "内容链必须仍在容器内（否则量到的不是详情那一层）",
            )
            Destination.entries.forEach { destination ->
                val tab = onNodeWithContentDescription(destination.label)
                    .fetchSemanticsNode().boundsInRoot
                assertTrue(
                    tab.top >= chain.bottom - 0.5f,
                    "「${destination.label}」tab 顶边 ${tab.top} 压在内容链底边 ${chain.bottom} 之上 —— " +
                        "底栏遮住了会话内容",
                )
            }
        }
    }

    /** 列表根（没有详情）时内容链也不许跟着多留一条：留白只服务「详情贴屏底的 composer」。 */
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

    /**
     * 抽屉展开态（审查 P1-2/P1-3）：抽屉与遮罩**贴屏底**，详情内容链**不垫**（满高），
     * 底栏的每个 tab 都落在遮罩的矩形里。
     *
     * 旧实现里容器垫了 108 dp ⇒ 抽屉与遮罩底边停在屏底上方 108 dp，抽屉右下 28 dp 圆角悬在
     * 半空、遮罩下面露出一条裸画布 + 胶囊。「拉开抽屉看一眼列表」是手机切会话的主路径，
     * 这条几乎人人可见。
     *
     * 量的是**几何**：遮罩铺满整个窗口，所以底栏的矩形被它覆盖。抽屉这一态的**画序**是
     * 底栏在上（`AppShell` 只在模型面板 / 图片预览那种整屏模态里把内容链抬到底栏之上）——
     * 抽屉只占 80% 宽，抬上来会埋掉半个胶囊；底栏留在最上层才仍能从抽屉里一键切 tab。
     * 模态浮层（模型面板 / 预览）那一半由 `theModelSheetScrimCoversTheBottomBar` 用真触摸钉。
     */
    @Test
    fun theOpenDrawerAndItsScrimCoverTheWholeWindow() = runComposeUiTest {
        val ui = detailState()
        phoneShell(ui)
        waitUntil(timeoutMillis = 10_000) { nodePresent(onNodeWithContentDescription("返回")) }

        runOnIdle { ui.openSessionsDrawer() }
        waitForIdle()
        waitUntil(timeoutMillis = 10_000) { nodePresent(onNodeWithTag(DRAWER_SCRIM_TAG)) }

        val drawer = onNodeWithTag(DRAWER_TAG).fetchSemanticsNode().boundsInRoot
        val scrim = onNodeWithTag(DRAWER_SCRIM_TAG).fetchSemanticsNode().boundsInRoot
        val chain = onNodeWithTag(DetailContentInsetTag).fetchSemanticsNode().boundsInRoot
        val window = onNodeWithTag(PHONE_ROOT_TAG).fetchSemanticsNode().boundsInRoot

        assertNear(window.bottom, drawer.bottom, 1f, "抽屉必须贴屏底（右下 28 dp 圆角不许悬在半空）")
        assertNear(window.top, drawer.top, 1f, "抽屉必须从窗口顶开始（满高）")
        assertNear(window.bottom, scrim.bottom, 1f, "遮罩必须铺到窗口底 —— 否则屏底露一条裸画布")
        assertNear(window.left, scrim.left, 0.5f, "遮罩必须满宽（左）")
        assertNear(window.right, scrim.right, 0.5f, "遮罩必须满宽（右）")
        assertNear(
            window.bottom,
            chain.bottom,
            1f,
            "抽屉展开时屏上是列表：详情内容链**不垫**（满高），别在遮罩底下再让出一条",
        )
        Destination.entries.forEach { destination ->
            val tab = onNodeWithContentDescription(destination.label).fetchSemanticsNode().boundsInRoot
            assertTrue(
                tab.top >= scrim.top - 0.5f && tab.bottom <= scrim.bottom + 0.5f &&
                    tab.left >= scrim.left - 0.5f && tab.right <= scrim.right + 0.5f,
                "「${destination.label}」tab 不在遮罩矩形里（tab=${tab.left},${tab.top}→${tab.right},${tab.bottom}；" +
                    "遮罩=${scrim.left},${scrim.top}→${scrim.right},${scrim.bottom}）—— 底栏没有被遮罩盖住",
            )
        }
    }

    /**
     * 模型面板（审查 P1-2 的「模态泄漏」那一半）：遮罩铺满窗口（含底栏区），并且**真的**
     * 压住底栏 —— 在胶囊的位置注入一次真触摸不会切页（面板照样开着 / 遮罩吃掉点按）。
     *
     * 旧实现两处都错：容器垫 104–132 dp 让遮罩停在屏底上方（硬边 + 卡片浮在半空），而且
     * 底栏画在整条内容链之上 ⇒ 面板开着还能点 tab 切页，把面板连人一起端掉。
     */
    @Test
    fun theModelSheetScrimCoversTheBottomBar() = runComposeUiTest {
        val ui = detailState()
        phoneShell(ui)
        waitUntil(timeoutMillis = 10_000) { nodePresent(onNodeWithContentDescription("更改会话模型")) }

        onNodeWithContentDescription("更改会话模型").performClick()
        waitForIdle()
        waitUntil(timeoutMillis = 10_000) { nodePresent(onNodeWithContentDescription("模型选择遮罩")) }

        val scrim = onNodeWithContentDescription("模型选择遮罩").fetchSemanticsNode().boundsInRoot
        val window = onNodeWithTag(PHONE_ROOT_TAG).fetchSemanticsNode().boundsInRoot
        assertNear(window.bottom, scrim.bottom, 1f, "模型面板的遮罩必须铺到窗口底（贴屏底，不是停在栏区上沿）")
        assertNear(window.top, scrim.top, 1f, "遮罩必须从窗口顶开始")
        assertNear(window.left, scrim.left, 0.5f, "遮罩必须满宽（左）")
        assertNear(window.right, scrim.right, 0.5f, "遮罩必须满宽（右）")

        val activityTab = onNodeWithContentDescription("活动").fetchSemanticsNode().boundsInRoot
        assertTrue(
            activityTab.top >= scrim.top - 0.5f && activityTab.bottom <= scrim.bottom + 0.5f,
            "「活动」tab 不在遮罩矩形里 —— 底栏没被盖住",
        )
        // 真触摸（不是语义 performClick）：命中测试会先给最上层。遮罩打开时点胶囊的位置
        // 必须被遮罩吃掉，`dest` 不许跟着换。
        onNodeWithTag(PHONE_ROOT_TAG).performTouchInput {
            down(activityTab.center)
            up()
        }
        waitForIdle()
        assertEquals(
            Destination.Sessions,
            ui.dest,
            "模型面板开着时点底栏的位置必须被遮罩吃掉（模态泄漏：旧实现这里会切到活动页并把面板端掉）",
        )
        assertNotNull(ui.selectedSessionId, "面板开着时也不许退掉会话")
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

    /**
     * 审查 P2-2：抽屉展开时屏上是**列表**，这时的「主页」按下去看不见任何变化，却会清掉
     * 抽屉背后那个会话的选中（静默破坏性无反馈按钮）。判据从 `detailOpen` 换成
     * `detailVisible` 之后，它不该出现。
     */
    @Test
    fun theHomeButtonIsNotDrawnWhileTheSessionsDrawerIsOpen() = runComposeUiTest {
        val ui = detailState()
        phoneShell(ui)
        waitUntil(timeoutMillis = 10_000) { nodePresent(onNodeWithContentDescription("返回")) }

        runOnIdle { ui.openSessionsDrawer() }
        waitForIdle()

        assertEquals(
            0,
            countWithDescription("主页"),
            "抽屉展开（屏上是列表）时不许画「主页」：点了没有视觉变化，却会静默清掉背后的选中",
        )
    }

    /**
     * 审查 P2-3：`switchDestination` 里那句 `if (selectedSessionId != null) back()` 是死代码，
     * 而且在 `listBesideDetail = true`（双栏）时**先**清了选中 —— 把 KDoc 承诺的 no-op 破坏掉。
     * 删掉之后双栏重复点会话 tab 必须真的是 no-op。
     */
    @Test
    fun retappingSessionsBesideADetailKeepsTheSelection() {
        val ui = detailState()
        switchDestination(ui, Destination.Sessions, listBesideDetail = true)

        assertEquals(
            "s-u1a",
            ui.selectedSessionId,
            "双栏重复点会话 tab 必须是 no-op：旧实现里的显式 back() 会把用户正在读的会话关掉",
        )
        assertEquals(Destination.Sessions, ui.dest)
    }

    /** 单栏重复点会话 tab 仍是「退一层」——删掉那句 back() 之后语义不变（`select` 自己调）。 */
    @Test
    fun retappingSessionsOnAPhoneStillBacksOutOneLayer() {
        val ui = detailState()
        switchDestination(ui, Destination.Sessions, listBesideDetail = false)

        assertNull(ui.selectedSessionId, "单栏重复点会话 tab = 退一层（由 UiState.select 内部 back() 保证）")
        assertTrue(ui.sessionsDrawerOpen, "退一层 = 抽屉滑出")
    }

    // ------------------------------------------------------------ 5 · 拖动不是点击

    /**
     * 审查 P2-4：浮动胶囊的 `onDragStopped` 曾经无条件上报 ⇒「拖走又拖回原位」会被当成
     * 「点了当前 tab」，在详情页上等于不弹确认就把会话退掉。
     *
     * 这里注入**真触摸**（`performTouchInput`，走 `inspectDragGestures` 那条路），来回各 40 px
     * （远超平台 touchSlop、净位移 0）后松手：会话必须还在。
     */
    @Test
    fun draggingThePillBackToItsOwnTabDoesNotLeaveTheSession() = runComposeUiTest {
        val ui = detailState()
        phoneShell(ui)
        waitUntil(timeoutMillis = 10_000) { nodePresent(onNodeWithContentDescription("返回")) }
        val before = ui.selectedSessionId
        assertNotNull(before, "前置条件：这条用例必须在详情页里跑")

        onNodeWithContentDescription("会话").performTouchInput {
            down(center)
            moveBy(Offset(40f, 0f))
            moveBy(Offset(-40f, 0f))
            up()
        }
        waitForIdle()

        assertEquals(
            before,
            ui.selectedSessionId,
            "拖走又拖回原位的胶囊不是「点当前 tab」：不许退掉会话",
        )
        assertEquals(Destination.Sessions, ui.dest, "同上：不许切页")
    }

    /** 反向守卫：真的拖到另一格时仍然要切换（别把 P2-4 的门做成「拖动全哑」）。 */
    @Test
    fun draggingThePillOntoAnotherTabStillSwitches() = runComposeUiTest {
        val ui = detailState()
        phoneShell(ui)
        waitUntil(timeoutMillis = 10_000) { nodePresent(onNodeWithContentDescription("返回")) }

        val from = onNodeWithContentDescription("会话").fetchSemanticsNode().boundsInRoot.center
        val to = onNodeWithContentDescription("活动").fetchSemanticsNode().boundsInRoot.center
        onNodeWithTag(PHONE_ROOT_TAG).performTouchInput {
            down(from)
            moveTo(to)
            up()
        }
        waitForIdle()

        assertEquals(
            Destination.Activity,
            ui.dest,
            "拖到「活动」那一格再松手必须切换（手势切 tab 是既有能力，不能被 P2-4 的门吞掉）",
        )
    }
}

/** 手机窗口尺寸（<600dp ⇒ 底栏 + 单栏）。 */
private val PHONE_WIDTH = 400.dp
private val PHONE_HEIGHT = 800.dp

/** 外层窗口的 tag —— `onRoot()` 是测试宿主，量窗口底边只能量这个 Box。 */
private const val PHONE_ROOT_TAG = "m-bar.phone.root"
