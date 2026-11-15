package vade.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import vade.ui.component.liquid.rememberGlassBackdrop
import vade.ui.destinations.Destination
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.LocalSkin
import vade.ui.skin.SkinGlass
import vade.ui.skin.material.MaterialSkin

/**
 * g-nav-selected —— 三形态选中态收敛成**一处共享渲染**的守卫。
 *
 * 为什么需要这组测试：`g-nav-visual` 的 D7 记录了三份各写一套的选中态（rail 有 30 dp 方块、
 * fixed 只有变色、floating 是一整个大 lavender 胶囊）。这不是三次粗心，而是**同一条属性被
 * 复制成三份**的必然结果——上两次（语义、矢量图标）都是只补了一部分，而 51 条测试全绿，
 * 因为源码守卫只打开「有 offender 的文件」，合规的那份永远不会被翻开（技能 §14/§17）。
 *
 * 所以本轮不靠「人工检查三处是否一致」，而是**结构上只留一处**：`NavSelection.kt`。这组
 * 测试钉三件事：
 *  ① 三个形态文件**都**调同一个符号（`NavSelectionIconSlot` / `NavTabLabel`），且都不再自带
 *    尺寸/透明度常量 —— 任何人想「就地微调一下」都会红；
 *  ② 尺寸与圆角取**同一个 token**（32 dp 常量 + `shapes.sm`），不是三份字面量；
 *  ③ 真实组合里每个形态都渲染出 4 个 **32 dp** 的块，且 floating 仍保留自己的容器胶囊
 *    （本轮只换选中态，不动容器几何）。
 *
 * 语义（`selected` + `Role.Tab`）不在这里重复断言：`BottomBarSemanticsTest` 已经盯着三形态
 * 同构，本轮的改动没有碰那三个 semantics 块，旧守卫必须继续全绿——它绿就是最好的回归证据。
 */
@OptIn(ExperimentalTestApi::class)
class NavSelectionGuardTest {

    private fun src(rel: String) = File("src/commonMain/kotlin/vade/ui/$rel")

    private fun barItems() = listOf(
        BottomBarItem(id = "sessions", label = "会话"),
        BottomBarItem(id = "activity", label = "活动"),
        BottomBarItem(id = "capabilities", label = "能力"),
        BottomBarItem(id = "me", label = "我的"),
    )

    // === ① 三形态调同一个渲染符号 =================================================

    /**
     * 三个形态文件都必须出现同样的两个调用，且都不许再自带「块」的实现。
     *
     * 反面清单是**具体的历史形状**，不是泛泛的「别重复」：
     *  - `size(30.dp)` —— rail 原来那块 30 dp 方块；
     *  - `accentColor.copy(` —— floating 原来那块整宽胶囊的染色；
     *  - `0.16f` —— 任何形态自带一份 16 % 透明度（只能来自 `NavSelection.kt`）。
     */
    @Test
    fun allThreeFormsRenderSelectionThroughTheSharedSymbols() {
        val forms = listOf(
            "shell/NavRail.kt" to "rail",
            "shell/BottomBar.kt" to "fixed",
            "shell/FloatingBottomBar.kt" to "floating",
        )
        val banned = listOf("size(30.dp)", "accentColor.copy(", "0.16f", "NavSelectionBlockSize")
        val problems = mutableListOf<String>()
        for ((rel, name) in forms) {
            val file = src(rel)
            assertTrue(file.isFile, "源文件不在预期位置：${file.path}")
            val source = file.readText()
            listOf("NavSelectionIconSlot(", "NavTabLabel(").forEach { needle ->
                if (!source.contains(needle)) problems += "$rel($name) 没有调用 `$needle`"
            }
            banned.forEach { needle ->
                if (source.contains(needle)) problems += "$rel($name) 又自带了一份选中态实现：`$needle`"
            }
        }
        assertEquals(emptyList(), problems, "三形态必须是同一个渲染器，不是三份复制：$problems")
    }

    /** 反向：共享渲染器本身只能有一份定义（`fun NavSelectionIconSlot` 全仓恰好 1 处）。 */
    @Test
    fun theSharedRendererIsDefinedExactlyOnce() {
        val root = File("src/commonMain/kotlin")
        val hits = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .count { it.readText().contains("internal fun NavSelectionIconSlot(") }
        assertEquals(1, hits, "`NavSelectionIconSlot` 的定义出现了 $hits 次（应当只有 NavSelection.kt 一处）")
    }

    // === ② 尺寸 / 圆角 / 染色都来自同一 token =====================================

    /**
     * 块的几何：32 dp 见方、圆角取皮肤形状阶梯的 `sm`（v2 = 16 dp）、染色 accent @ 16 %。
     * 圆角写成**字面量**就失去「换皮肤跟着变」的能力，所以这里断言它等于 `shapes.sm`
     * **并且**不等于 `xs`（否则把 sm 误写成 xs 也绿）。
     */
    @Test
    fun blockGeometryComesFromOneSizeAndTheShapeToken() {
        assertEquals(32.dp, NavSelectionBlockSize, "指示块必须是 32 dp（用户拍板的 ≈32dp）")
        assertEquals(20.dp, NavSelectionIconSize, "块内图标必须仍是 20 dp")
        assertEquals(0.16f, NavSelectionAccentAlpha, "染色必须是 accent @ 16%")

        val shapes = MaterialSkin().shapes
        assertEquals(16.dp, shapes.sm, "前提：v2 的 sm 就是 16 dp（token 被改则本测试的前提要重看）")
        assertEquals(shapes.sm, navSelectionCorner(shapes), "圆角必须取 shapes.sm 这个 token")
        assertEquals(
            false,
            navSelectionCorner(shapes) == shapes.xs,
            "圆角退回了 xs（12 dp）—— 说明取错了阶梯",
        )
    }

    /** 未选中**必须无填充**；选中是 accent 的 16 %，且不动 RGB（只降 alpha）。 */
    @Test
    fun unselectedPaintsNothingAndSelectedIsAccentAt16() {
        val accent = MaterialSkin().colors.primary
        val unselected = navSelectionFill(selected = false, accent = accent)
        assertEquals(Color.Transparent, unselected, "未选中项必须显式透明（不是父底色的第二份拷贝）")

        val selected = navSelectionFill(selected = true, accent = accent)
        assertEquals(0.16f, selected.alpha, 1f / 255f, "选中块染色必须仍是 16%")
        assertEquals(accent.red, selected.red, "只允许改 alpha，不许改色相")
        assertEquals(accent.green, selected.green)
        assertEquals(accent.blue, selected.blue)
    }

    /** 文字两态：选中 accent / 未选中 onSurfaceVariant —— 三形态共用的唯一规则。 */
    @Test
    fun labelColourRuleIsAccentVersusOnSurfaceVariant() = runComposeUiTest {
        val colors = MaterialSkin().colors
        var selectedColour: Color? = null
        var idleColour: Color? = null
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                selectedColour = navTabColor(selected = true)
                idleColour = navTabColor(selected = false)
            }
        }
        assertEquals(colors.primary, selectedColour, "选中文字/图标必须是 accent")
        assertEquals(colors.onSurfaceVariant, idleColour, "未选中文字/图标必须是 onSurfaceVariant")
        assertTrue(colors.primary != colors.onSurfaceVariant, "两态同色 —— 选中态就只剩块了")
    }

    // === ③ 真实组合：三形态各渲染出 4 个 32 dp 块 ==================================

    private fun ComposeUiTest.blockNodes() =
        onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, NavSelectionBlockTag), useUnmergedTree = true).fetchSemanticsNodes()

    @Test
    fun fixedBarDrawsFour32dpBlocks() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                val backdrop = rememberGlassBackdrop()
                Box(Modifier) {
                    VadeBottomBar(
                        items = barItems(),
                        selected = 1,
                        onSelect = {},
                        style = BottomBarStyle.Fixed,
                        backdrop = backdrop,
                    )
                }
            }
        }
        assertEquals(
            4,
            onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, NavSelectionBlockTag), useUnmergedTree = true).fetchSemanticsNodes().size,
            "固定底栏每个 tab 都要有指示块",
        )
        onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, NavSelectionBlockTag), useUnmergedTree = true).onFirst().assertWidthIsEqualTo(32.dp)
        onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, NavSelectionBlockTag), useUnmergedTree = true).onFirst().assertHeightIsEqualTo(32.dp)
    }

    @Test
    fun floatingBarDrawsFour32dpBlocksAndKeepsItsPill() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                val backdrop = rememberGlassBackdrop()
                Box(Modifier) {
                    VadeBottomBar(
                        items = barItems(),
                        selected = 2,
                        onSelect = {},
                        style = BottomBarStyle.Floating,
                        backdrop = backdrop,
                    )
                }
            }
        }
        assertEquals(
            4,
            onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, NavSelectionBlockTag), useUnmergedTree = true).fetchSemanticsNodes().size,
            "浮动形态每个 tab 都要有指示块",
        )
        onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, NavSelectionBlockTag), useUnmergedTree = true).onFirst().assertWidthIsEqualTo(32.dp)
        onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, NavSelectionBlockTag), useUnmergedTree = true).onFirst().assertHeightIsEqualTo(32.dp)
        // 容器胶囊仍在（本轮只换选中态）：图标节点仍带着各自标签，容器高度见
        // `floatingContainerGeometryDidNotMove`。
        onNodeWithContentDescription("会话", useUnmergedTree = true).assertExists()
    }

    /** 图标必须**落在块里面**：块是「图标外包」，不是旁边一块色。 */
    @Test
    fun floatingIconSitsInsideItsSelectionBlock() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                val backdrop = rememberGlassBackdrop()
                Box(Modifier) {
                    VadeBottomBar(
                        items = barItems(),
                        selected = 0,
                        onSelect = {},
                        style = BottomBarStyle.Floating,
                        backdrop = backdrop,
                    )
                }
            }
        }
        val block = onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, NavSelectionBlockTag), useUnmergedTree = true).onFirst().fetchSemanticsNode()
        val icon = onNodeWithContentDescription("会话", useUnmergedTree = true).fetchSemanticsNode()
        val blockRect = block.boundsInRoot
        val iconRect = icon.boundsInRoot
        assertTrue(
            iconRect.left >= blockRect.left && iconRect.right <= blockRect.right &&
                iconRect.top >= blockRect.top && iconRect.bottom <= blockRect.bottom,
            "图标不在指示块内：block=$blockRect icon=$iconRect",
        )
        assertTrue(
            blockRect.height > iconRect.height,
            "块必须比图标大（外包），实际 block=${blockRect.height} icon=${iconRect.height}",
        )
    }

    @Test
    fun railDrawsFour32dpBlocks() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                NavRail(width = 80.dp, selected = Destination.Capabilities, badgeOf = { null }, onSelect = {})
            }
        }
        assertEquals(
            4,
            onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, NavSelectionBlockTag), useUnmergedTree = true).fetchSemanticsNodes().size,
            "rail 每个目的地都要有指示块",
        )
        onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, NavSelectionBlockTag), useUnmergedTree = true).onFirst().assertWidthIsEqualTo(32.dp)
        onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, NavSelectionBlockTag), useUnmergedTree = true).onFirst().assertHeightIsEqualTo(32.dp)
    }

    // === 容器没有被动过 ==========================================================

    /**
     * 本棒只换选中态：floating 的容器几何（64 dp 胶囊 / 4 dp 内边距 / 26 %–34 % 或 100 % 染色）
     * 一像素不动。这条与 `BottomBarSemanticsTest` 的染色预算断言互补——那边管「颜色不许变」，
     * 这边管「几何不许变」。
     */
    @Test
    fun floatingContainerGeometryDidNotMove() {
        assertEquals(64.dp, FloatingBarPillHeight, "胶囊总高被改了（本轮不许动容器几何）")
        val source = src("shell/FloatingBottomBar.kt").readText()
        assertTrue(source.contains(".height(64.dp)"), "胶囊行高 64 dp 不见了")
        assertTrue(source.contains(".padding(4.dp)"), "胶囊内边距 4 dp 不见了")
        assertEquals(24f, SkinGlass().blurRadiusDp, "共享模糊半径被改了")
    }
}
