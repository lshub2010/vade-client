package vade.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue
import vade.ui.chat.ComposerBottomBlurTag
import vade.ui.destinations.Destination
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.LocalSkin
import vade.ui.skin.glass.GlassLayerProbe
import vade.ui.skin.material.MaterialSkin

/**
 * §5「同屏模糊层数 **≤3**」的**组合断言**（审查 P0-5）。
 *
 * 这条上限以前只活在注释里：`SearchOverlay` 甚至写了「scrim 会是第四层，所以它只压暗」，
 * 而会话页本身一路是 5 层（顶栏胶囊 + 顶栏带 + 底栏带 + composer 面板 + 浮动胶囊），没有
 * 任何断言会红。真机上的"滑动掉帧"就是这 5 次采样 + 1 次整窗 capture 叠出来的。
 *
 * 数的是**真的建出来的 haze 节点**（[GlassLayerProbe] 在组合期进出计数），不是源码里
 * 出现了几次 `hazeBlur(` —— 后者在 `if (drawsHaze)` 里，源码永远数不准。
 *
 * 合并后的账（会话详情 · 手机单栏）：
 *
 * | 层 | 谁提供模糊 | 节点 |
 * |---|---|---|
 * | 顶栏带 + 胶囊 | 带子（满强度平台段覆盖到胶囊底沿） | 1 |
 * | 底栏带 + 底栏/浮动胶囊 | 带子（最强端就在屏幕底） | 1 |
 * | composer 面板 | 自己 | 1 |
 * | 合计 | | **3** |
 */
class GlassLayerBudgetTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun sessionDetailPageKeepsAtMostThreeHazeNodes() = runComposeUiTest {
        GlassLayerProbe.reset()
        val ui = UiState(
            initialBar = BottomBarStyle.Fixed,
            initialSkinId = "material",
            initialDest = Destination.Sessions,
        )
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                // <600dp：手机单栏（底栏 + 抽屉模型 + 会话详情）。
                Box(Modifier.size(400.dp, 900.dp)) { AppShell(ui) }
            }
        }
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("RFC-0004 附件与多模态评审", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        waitForIdle()

        // 列表页：顶栏带 + 底栏（这里底栏**自己**采样 —— 栏区还没有别的玻璃层）。
        val listPageNodes = GlassLayerProbe.live
        assertTrue(
            listPageNodes in 1..3,
            "列表页同屏 haze 节点 = $listPageNodes；§5 上限是 3",
        )

        runOnIdle { ui.open("s-rfc0004") }
        waitForIdle()

        // 合并后的两层必须真的存在 —— 否则下面的「≤3」可能是"什么都没画"的假绿。
        onNodeWithTag(TopBarBandTag).assertExists()
        onNodeWithTag(ComposerBottomBlurTag).assertExists()

        val detailNodes = GlassLayerProbe.live
        assertTrue(
            detailNodes in 2..3,
            "会话详情页同屏 haze 节点 = $detailNodes（应 ≥2：顶栏带 + 底栏带；§5 上限 3）—— " +
                "顶栏带/胶囊、底栏带/底栏必须**合并**成一层（`TopBar` 的 blurBackdrop = false、" +
                "`VadeBottomBar` 的 barZoneBlurred），别把它们各拆回一个节点",
        )
        GlassLayerProbe.reset()
    }
}
