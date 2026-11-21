package vade.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import vade.ui.chat.ChatPreset
import vade.ui.chat.ComposerBottomBlurTag
import vade.ui.chat.ComposerPanelTag
import vade.ui.destinations.Destination
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.LocalSkin
import vade.ui.skin.glass.GlassLayerProbe
import vade.ui.skin.material.MaterialSkin

/**
 * §5「同屏模糊层数 **≤3**」的**组合断言**（审查 P0-5 / P1-2）。
 *
 * 这条上限以前只活在注释里：`SearchOverlay` 甚至写了「scrim 会是第四层，所以它只压暗」，
 * 而会话页本身一路是 5 层（顶栏胶囊 + 顶栏带 + 底栏带 + composer 面板 + 浮动胶囊），没有
 * 任何断言会红。真机上的"滑动掉帧"就是这 5 次采样 + 1 次整窗 capture 叠出来的。
 *
 * 数的是**真的建出来的 haze 节点**（[GlassLayerProbe] 在组合期进出计数），不是源码里
 * 出现了几次 `hazeBlur(` —— 后者在 `if (drawsHaze)` 里，源码永远数不准。
 *
 * 合并后的账（会话详情 · 手机单栏），三个状态都要过：
 *
 * | 层 | 谁提供模糊 | 节点 |
 * |---|---|---|
 * | 顶栏带 + 胶囊 | 带子（满强度平台段覆盖到胶囊底沿） | 1 |
 * | 底栏带 + 底栏/浮动胶囊 | 带子（最强端就在屏幕底） | 1 |
 * | composer 面板 | 自己 | 1 |
 * | 合计（静止） | | **3** |
 *
 * 浮层打开时**不许再涨**（审查 F6-P1-2 实测当时是 5）：
 *
 *  - 模型面板 / 图片预览的遮罩改成**纯色** `background(scrim)` —— 全屏玻璃遮罩既占一层
 *    采样，又会被降级档补上 34 % 顶部高光（遮罩比画布还亮）；
 *  - 浮层卡自己 `blurBackdrop = false` —— 它已经垫了 88 % 的近实心内容底，再采一次肉眼
 *    看不出来，却实打实多一层。
 *
 * 断言是 **== 3**，不是 `in 2..3`：后者允许 composer 的节点消失（最容易发生的「省一层」
 * 改动）也照样绿 —— 审查 P2-4 的原话。
 */
@OptIn(ExperimentalTestApi::class)
class GlassLayerBudgetTest {

    /**
     * 组合一个 400×900 的手机单栏 shell（底栏 + 抽屉模型 + 会话详情），打开会话后把控制权
     * 交给 [block]。`GlassLayerProbe` 在每个用例前后归零。
     */
    @OptIn(ExperimentalTestApi::class)
    private fun shell(
        preset: ChatPreset = ChatPreset.Live,
        block: ComposeUiTest.(UiState) -> Unit,
    ) = runComposeUiTest {
        GlassLayerProbe.reset()
        val ui = UiState(
            initialBar = BottomBarStyle.Fixed,
            initialSkinId = "material",
            initialDest = Destination.Sessions,
            chatPreset = preset,
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

        // 合并后的两层必须真的存在 —— 否则下面的「== 3」可能是"什么都没画"的假绿。
        onNodeWithTag(TopBarBandTag).assertExists()
        onNodeWithTag(ComposerBottomBlurTag).assertExists()

        block(ui)
        GlassLayerProbe.reset()
    }

    private fun ComposeUiTest.assertThree(what: String) {
        val nodes = GlassLayerProbe.live
        println("§5 层数 · $what: haze nodes = $nodes（预算 3）")
        assertEquals(
            3,
            nodes,
            "$what 同屏 haze 节点 = $nodes（必须是 3：顶栏带 + 底栏带 + composer 面板）—— " +
                "顶栏带/胶囊、底栏带/底栏必须**合并**成一层（`TopBar` 的 blurBackdrop = false、" +
                "`VadeBottomBar` 的 barZoneBlurred）；浮层不许再加层（遮罩用纯色 scrim、" +
                "浮层卡 blurBackdrop = false）",
        )
    }

    @Test
    fun sessionDetailPageKeepsExactlyThreeHazeNodes() = shell { _ ->
        assertThree("会话详情页（静止）")
        // composer 这一层真的在屏上（节点数对 ≠ 那条链上有谁对）。
        onNodeWithText("输入消息…").assertExists()
    }

    /**
     * 模型面板打开：遮罩（纯色）+ 卡片（不采样）都不许新增 haze 节点 —— 仍是 3。
     */
    @Test
    fun modelPickerOpenStateStaysAtThreeHazeNodes() = shell { _ ->
        // 第二行默认收起：点输入区 → chip 出现 → 点 chip → 浮层。
        onNodeWithText("输入消息…").performClick()
        waitForIdle()
        onNodeWithTag(ComposerPanelTag).assertExists()
        onNodeWithContentDescription("更改会话模型").performClick()
        waitForIdle()
        // 浮层真的开了（否则又是一种"什么都没画"的假绿）。
        onNodeWithContentDescription("关闭会话模型").assertExists()
        onNodeWithText("会话模型").assertExists()

        assertThree("模型面板打开态")
    }

    /**
     * 图片预览打开：`ChatPreset.Preview` 直接把预览状态喂给页面（真实 `ChatThread` +
     * 真实遮罩），节点数仍是 3。
     */
    @Test
    fun imagePreviewOpenStateStaysAtThreeHazeNodes() = shell(preset = ChatPreset.Preview) { _ ->
        onNodeWithContentDescription("关闭预览").assertExists()
        assertThree("图片预览打开态")
    }
}
