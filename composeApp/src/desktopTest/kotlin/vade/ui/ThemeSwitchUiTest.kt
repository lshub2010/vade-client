package vade.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runSkikoComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import vade.ui.shell.DRAWER_SCRIM_TAG
import vade.ui.shell.DRAWER_TAG
import vade.ui.skin.SkinRegistry
import vade.ui.skin.ThemeController
import vade.ui.skin.ThemeMode
import vade.ui.skin.material.MaterialSkinEntry
import vade.ui.skin.miuix.MiuixSkinEntry
import vade.ui.testutil.IsolatedPreferencesTest

/**
 * g-dark-follow · 「切换即时生效且不重建整棵树」的真组合验证（desktopTest JVM）。
 *
 * 判据设计（为什么这些断言能区分「重组」和「重建」）：`UiState`（当前会话 id、
 * dest、`rememberLazyListState`）全部 `remember` 在 `App()` 里——
 *
 *  - 正确路径：主题变化只换 `LocalSkin` 的值，组合不销毁 → 运行期产生的导航/滚动
 *    状态原样保留；
 *  - 错误路径（整棵树被重建）：`UiState` 是新对象，dest 回到初始 Sessions、列表滚回
 *    顶部 / 抽屉重新滑出 → 下面每条「状态保留」断言都会红。
 *
 * 切换是否真的发生用两个皮肤工厂闭包读的 plain var（`MaterialSkinEntry.dark` /
 * `MiuixSkinEntry.dark`）作观察点：`ThemeController.currentDark()` 在重组期同步它们，
 * `waitUntil` 等到翻转才继续——没有它，后面的「没变」断言会平凡通过。
 *
 * ## 等待为什么不是「赌时间」
 *
 * 本类没有任何 `delay`/墙钟等待：桌面端 `waitUntil` 的实现是逐帧泵循环
 * （`renderNextFrame()` 直到条件成立），而条件全部是**状态派生**的——
 *  - 皮肤工厂 var 由 `ThemeController.currentDark()` 在重组期写，等它翻转 = 等
 *    「读到 override 之后的第一次重组」；
 *  - 抽屉开合由 `UiState.sessionsDrawerOpen` 派生（见 [drawerFullyClosed]），
 *    等它收起 = 等 `selectSession` 之后的那次重组（260ms 动画由帧泵走完）。
 *
 * ## g-flake-ui-timing：两条前提随口径变更过期，判据随之重指向
 *
 * 2026-11-15 的 Q2-B / g-route-transition 改了两件事，旧的「现象判据」在新模型下
 * 结构上不可能成立（不是抖动，是前提没了）：
 *  1. **行变矮**（`SessionRow` 收窄 + `heightIn(min)`）：466×520 的视口装得下全部
 *     6 条 fixture + 分组头，滚到最远行首行还在视口里 → 滚动前提失效。修法是
 *     **缩小视口**（360dp：最远行初始根本不在组合里，滚过去首行被 lazy 丢弃），
 *     保住「列表真的溢出、滚动真的发生」的意图，不动任何断言。
 *  2. **手机单栏改抽屉模型**：详情常驻底层、列表抽屉收起后**仍常驻组合**（平移出屏
 *     + `invisibleToUser`），而 CMP 1.12 桌面测试的语义查询**不过滤**该标记的后代
 *     （实测收起后行仍可被 `onAllNodesWithText` 查到）→ 「点开会话后列表行从语义树
 *     消失」在任何视口都不可能再成立。判据改用抽屉模型的确定信号（见下），验证的
 *     意图不变：重建 = 新 `UiState` = 抽屉重新滑出 + 当前会话顶栏标题消失。
 */
@OptIn(ExperimentalTestApi::class)
class ThemeSwitchUiTest : IsolatedPreferencesTest() {

    private companion object {
        /** 列表首行（置顶 fixture，唯一文本）。 */
        const val FIRST_ROW = "RFC-0004 附件与多模态评审"

        /** 用于点开详情的另一条会话（唯一文本）。 */
        const val SECOND_ROW = "U1-a 骨架验收"

        /** 列表最远一行（fixture 顺序最后，滚动断言的落点）。 */
        const val FAR_ROW = "AGP 9.5.1 矩阵验证"
    }

    /**
     * 手机尺寸窗口（466dp 宽 = 产品默认形态）：`<600dp` 单栏，点开会话走**抽屉模型**
     * （详情常驻底层、列表抽屉收起）。桌面 ui-test 默认 800×600 是双栏（列表与详情
     * 并存），那里的语义和手机形态对不上。
     */
    @Test
    fun switchingThemeKeepsTheOpenSessionAndSwapsTheSkin() = runSkikoComposeUiTest(
        // density 默认 1 → 像素 = dp；466dp 宽即 466px。
        size = Size(466f, 893f),
    ) {
        /**
         * 抽屉模型的「会话已打开（抽屉已收）」确定信号，两层状态派生、两者都验：
         *  - 遮罩层 Disabled：`clickable(enabled = expanded)` 在 expanded=false 的那一帧
         *    翻成 Disabled（打开态没有这个键）；
         *  - 列表层完全出屏：`translationX = -(1 - progress) × width`，收起后
         *    `boundsInRoot` 右缘 ≤ 0（实测打开 = 373、收起 = 0）。
         * 等它们成立是等确定状态，不是赌帧数；重建会让抽屉重新滑出（信号翻转回 false）。
         */
        fun drawerFullyClosed(): Boolean {
            // 只看抽屉是否完全出屏：这是「已收起」的本质信号。旧版还要求遮罩处于
            // Disabled，那是 `clickable(enabled=false)` 的实现细节；该写法会吞掉底下
            // 空态按钮的点击（P1-11），已改成收起时不挂 clickable，语义里不再有 Disabled。
            val drawer = onAllNodesWithTag(DRAWER_TAG).fetchSemanticsNodes()
            return drawer.isNotEmpty() && drawer.all { it.boundsInRoot.right <= 0f }
        }

        /** 视口内、文本为 [text] 的节点数（屏外的抽屉行不算「在场」）。 */
        fun displayedInViewport(text: String): Int {
            val root = onRoot().fetchSemanticsNode().boundsInRoot
            return onAllNodesWithText(text).fetchSemanticsNodes().count { node ->
                val b = node.boundsInRoot
                b.left < root.right && b.right > root.left && b.top < root.bottom && b.bottom > root.top
            }
        }

        // 基线亮色（防上一条测试的残留让下面的 waitUntil 平凡通过）。
        ThemeController.override = ThemeMode.Light
        setContent { App() }
        waitUntil(timeoutMillis = 10_000) { !MaterialSkinEntry.dark && !MiuixSkinEntry.dark }
        assertFalse(MaterialSkinEntry.dark, "基线必须是亮色")
        onNodeWithText(FIRST_ROW).assertExists()

        // 用户路径：点开另一个会话（运行期导航状态——重建就会丢）。
        onNodeWithText(SECOND_ROW).performClick()
        waitUntil(timeoutMillis = 10_000) { drawerFullyClosed() }

        // 手选暗（覆盖优先）→ 重组 → currentDark() 同步两个工厂 var。
        ThemeController.override = ThemeMode.Dark
        waitUntil(timeoutMillis = 10_000) { MaterialSkinEntry.dark && MiuixSkinEntry.dark }
        assertTrue(SkinRegistry.get("material").colors.isDark, "注册表现在必须产出暗色 material")
        assertTrue(SkinRegistry.get("miuix").colors.isDark, "注册表现在必须产出暗色 miuix")

        // 不重建的证据（抽屉模型口径）：若整棵树被重建，UiState 是新对象 → dest 回初始
        // Sessions、selectedSessionId 清空 → 抽屉重新滑出。它必须仍然收着；当前会话的
        // 顶栏标题必须仍在视口内（抽屉里那行同名文本在屏外，不计入）。
        assertTrue(drawerFullyClosed(), "主题切换后抽屉重新滑出 = 整棵树被重建（当前会话丢了）")
        assertEquals(1, displayedInViewport(SECOND_ROW), "切换主题后必须仍停留在刚点开的会话详情")

        // 收尾：别把暗色覆盖留给后面的测试。
        ThemeController.override = ThemeMode.FollowSystem
    }

    /**
     * 466dp 宽保持单栏；高度压到 360dp 保证列表**真的溢出**。g-flake-ui-timing：Q2-B
     * 把行收窄变矮后，旧的 520dp 视口装得下全部 6 条 fixture + 分组头（滚到最远行首行
     * 还在视口里），「必须滚动」的前提失效——改用 360dp：最远行初始根本不在组合里
     * （实测 `farExists=0`），滚过去它必须出现、首行必须被 lazy 丢弃（实测
     * `firstNodes=0`）。意图不变（列表溢出、滚动真的发生），断言一条未动。
     */
    @Test
    fun switchingThemePreservesScrollPosition() = runSkikoComposeUiTest(
        size = Size(466f, 360f),
    ) {
        ThemeController.override = ThemeMode.Light
        setContent { App() }
        waitUntil(timeoutMillis = 10_000) { !MaterialSkinEntry.dark }

        // 滚到列表最远一行（6 条 fixture + 分组头，超出测试视口）。
        onNode(hasScrollAction()).performScrollToNode(hasText(FAR_ROW))
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(FAR_ROW).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText(FAR_ROW).assertIsDisplayed()
        // 滚动真的发生了：首行要么已被 lazy 丢弃，要么不在视口。
        val firstNodes = onAllNodesWithText(FIRST_ROW).fetchSemanticsNodes()
        if (firstNodes.isNotEmpty()) {
            // 首行必须滚出视口，否则滚动断言平凡通过。
            onAllNodesWithText(FIRST_ROW)[0].assertIsNotDisplayed()
        }

        // 切暗色：滚动位置必须原样保留（主题切换后最远行必须仍在视口——
        // 列表滚回顶部 = rememberLazyListState 被重建）。
        ThemeController.override = ThemeMode.Dark
        waitUntil(timeoutMillis = 10_000) { MaterialSkinEntry.dark }
        onNodeWithText(FAR_ROW).assertIsDisplayed()
        val firstAfter = onAllNodesWithText(FIRST_ROW).fetchSemanticsNodes()
        if (firstAfter.isNotEmpty()) {
            onAllNodesWithText(FIRST_ROW)[0].assertIsNotDisplayed()
        }

        ThemeController.override = ThemeMode.FollowSystem
    }
}
