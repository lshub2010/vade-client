package vade.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import vade.ui.skin.SkinRegistry
import vade.ui.skin.ThemeController
import vade.ui.skin.ThemeMode
import vade.ui.skin.material.MaterialSkinEntry
import vade.ui.skin.miuix.MiuixSkinEntry

/**
 * g-dark-follow · 「切换即时生效且不重建整棵树」的真组合验证（desktopTest JVM）。
 *
 * 判据设计（为什么这些断言能区分「重组」和「重建」）：`UiState`（当前会话 id、
 * dest、`rememberLazyListState`）全部 `remember` 在 `App()` 里——
 *
 *  - 正确路径：主题变化只换 `LocalSkin` 的值，组合不销毁 → 运行期产生的导航/滚动
 *    状态原样保留；
 *  - 错误路径（整棵树被重建）：`UiState` 是新对象，dest 回到初始 Sessions、列表
 *    滚回顶部 → 下面每条「状态保留」断言都会红。
 *
 * 切换是否真的发生用两个皮肤工厂闭包读的 plain var（`MaterialSkinEntry.dark` /
 * `MiuixSkinEntry.dark`）作观察点：`ThemeController.currentDark()` 在重组期同步它们，
 * `waitUntil` 等到翻转才继续——没有它，后面的「没变」断言会平凡通过。
 */
@OptIn(ExperimentalTestApi::class)
class ThemeSwitchUiTest {

    private companion object {
        /** 列表首行（置顶 fixture，唯一文本）。 */
        const val FIRST_ROW = "RFC-0004 附件与多模态评审"

        /** 用于点开详情的另一条会话（唯一文本）。 */
        const val SECOND_ROW = "U1-a 骨架验收"

        /** 列表最远一行（fixture 顺序最后，滚动断言的落点）。 */
        const val FAR_ROW = "AGP 9.5.1 矩阵验证"
    }

    /**
     * 手机尺寸窗口（466dp 宽 = 产品默认形态）：`<600dp` 单栏，点开会话是**推入式**
     * 全屏详情，列表卸载。桌面 ui-test 默认 800×600 是双栏（列表与详情并存），
     * 「列表行消失」判据在那里永远不成立。
     */
    @Test
    fun switchingThemeKeepsTheOpenSessionAndSwapsTheSkin() = runSkikoComposeUiTest(
        // density 默认 1 → 像素 = dp；466dp 宽即 466px。
        size = Size(466f, 893f),
    ) {
        // 基线亮色（防上一条测试的残留让下面的 waitUntil 平凡通过）。
        ThemeController.override = ThemeMode.Light
        setContent { App() }
        waitUntil(timeoutMillis = 10_000) { !MaterialSkinEntry.dark && !MiuixSkinEntry.dark }
        assertFalse(MaterialSkinEntry.dark, "基线必须是亮色")
        onNodeWithText(FIRST_ROW).assertExists()

        // 用户路径：点开另一个会话（运行期导航状态——重建就会丢）。
        onNodeWithText(SECOND_ROW).performClick()
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(FIRST_ROW).fetchSemanticsNodes().isEmpty()
        }

        // 手选暗（覆盖优先）→ 重组 → currentDark() 同步两个工厂 var。
        ThemeController.override = ThemeMode.Dark
        waitUntil(timeoutMillis = 10_000) { MaterialSkinEntry.dark && MiuixSkinEntry.dark }
        assertTrue(SkinRegistry.get("material").colors.isDark, "注册表现在必须产出暗色 material")
        assertTrue(SkinRegistry.get("miuix").colors.isDark, "注册表现在必须产出暗色 miuix")

        // 不重建的证据：若整棵树被重建，UiState 是新对象 → dest 回 Sessions →
        // 列表行回来。它必须仍然缺席；当前会话的详情页必须仍在。
        assertTrue(
            onAllNodesWithText(FIRST_ROW).fetchSemanticsNodes().isEmpty(),
            "主题切换后回到会话列表 = 整棵树被重建（当前会话丢了）",
        )
        onNodeWithText(SECOND_ROW).assertExists("切换主题后必须仍停留在刚点开的会话详情")

        // 收尾：别把暗色覆盖留给后面的测试。
        ThemeController.override = ThemeMode.FollowSystem
    }

    /** 466dp 宽保持单栏；高度压到 520dp，保证 6 条 fixture + 分组头必须滚动。 */
    @Test
    fun switchingThemePreservesScrollPosition() = runSkikoComposeUiTest(
        size = Size(466f, 520f),
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
