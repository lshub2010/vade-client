package vade.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.composables.icons.tabler.Tabler
import com.composables.icons.tabler.filled.ChartAreaLine
import com.composables.icons.tabler.filled.LayoutGrid
import com.composables.icons.tabler.filled.MessageCircle
import com.composables.icons.tabler.filled.User
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import vade.ui.component.liquid.rememberGlassBackdrop
import vade.ui.destinations.Destination
import vade.ui.icon.ShellGlyphs
import vade.ui.icon.VadeIcons
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
 * g-nav-selected2 在这层之上再加一层（用户拍板「两者结合：小圆角块 + 实心图标 + 文字变色」）：
 *  ④ 选中项的图标换成**登记在 `ShellGlyphs.NavFilledVariants` 里的 Tabler 实心变体**，
 *    未选中项保持描边 —— 登记全覆盖（四目的地两条解析路径都命中）、登记值逐字钉住
 *    （getter 名全部 javap 核过 `icons-tabler-filled-cmp-jvm-2.2.1.jar`）；
 *  ⑤ 三形态各一组选中/未选中断言：有块 / 是 filled / 文字 accent；描边 / 灰文字；
 *  ⑥ 同屏内不可能「同一选中态混用 filled 与 outline」—— 每个 tab 的图标家族必须等于
 *    它的选中态，未登记 vector 选中时保留描边并如实标 outline（静默混用立刻红）。
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
     *  - `0.16f` —— 任何形态自带一份 16 % 透明度（只能来自 `NavSelection.kt`）；
     *  - `Tabler.Filled` / `Tabler.Outline` / `ShellGlyphs` / `NavFilledVariants` /
     *    `navFilledVariantOf(` —— g-nav-selected2 的实心变体解析只许在共享渲染器里做，
     *    任何形态自己解析（或直接引用 Tabler 家族）都是第二套选中态。
     */
    @Test
    fun allThreeFormsRenderSelectionThroughTheSharedSymbols() {
        val forms = listOf(
            "shell/NavRail.kt" to "rail",
            "shell/BottomBar.kt" to "fixed",
            "shell/FloatingBottomBar.kt" to "floating",
        )
        val banned = listOf(
            "size(30.dp)",
            "accentColor.copy(",
            "0.16f",
            "NavSelectionBlockSize",
            "Tabler.Filled",
            "Tabler.Outline",
            "ShellGlyphs",
            "NavFilledVariants",
            "navFilledVariantOf(",
        )
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

    // === ④/⑤/⑥ g-nav-selected2：小圆角块 + 实心图标 + 文字变色 ======================

    private val barLabels = listOf("会话", "活动", "能力", "我的")

    /** 语义节点的合并文本（`AnnotatedString` 列表拼回一个串）。 */
    private fun SemanticsNode.combinedText(): String =
        config[SemanticsProperties.Text].joinToString("") { it.toString() }

    /**
     * outer 在 0.5 px 容差内包住 inner。家族标记节点（filled/outline tag 的那个 Box）与图标
     * 等大；指示块（32 dp）比图标（20 dp）大——两者都必须「包住」对应图标。
     */
    private fun wraps(outer: Rect, inner: Rect): Boolean =
        outer.left <= inner.left + 0.5f && outer.right >= inner.right - 0.5f &&
            outer.top <= inner.top + 0.5f && outer.bottom >= inner.bottom - 0.5f

    /**
     * ④ 登记全覆盖 + 登记值逐字钉住。
     *
     * 两条解析入口都要命中：rail 用 `Destination.icon`（枚举里那份描边 vector），底栏
     * （items 不带显式 icon 时）走 `VadeIcons.navIconOrNull(id)` —— 返回的是同一批对象，
     * 但这条测试让「哪天有人把两条路径拆成两份」也红。
     *
     * 登记值用 `assertSame` 钉住真实 `Tabler.Filled.*` getter：getter 名已用 javap 核过
     * `icons-tabler-filled-cmp-jvm-2.2.1.jar`（`Message_circleKt` / `Chart_area_lineKt` /
     * `Layout_gridKt` / `UserKt`）——编译期能解析这些引用本身就是存在性证明，本仓不猜名。
     */
    @Test
    fun navFilledVariantMappingCoversEveryDestinationWithRealFilledVectors() {
        for (destination in Destination.entries) {
            val viaEnum = ShellGlyphs.navFilledVariantOf(destination.icon)
            assertTrue(
                viaEnum != null,
                "「${destination.label}」没登记实心变体 —— 选中时会静默画描边（同屏混用）",
            )
            val viaId = ShellGlyphs.navFilledVariantOf(VadeIcons.navIconOrNull(destination.id)!!)
            assertSame(
                viaEnum,
                viaId,
                "「${destination.label}」两条解析路径（枚举 icon / id 查表）命中不同登记",
            )
        }
        assertSame(Tabler.Filled.MessageCircle, ShellGlyphs.navFilledVariantOf(VadeIcons.Chat))
        // 「活动」上游没有 Filled.Activity（activity 是纯描边心电图线，filled 工件里没有
        // ActivityKt）。登记的是最接近的 ChartAreaLine（折线 + 填充面积）——这是写进报告的
        // **登记在案**替代；同屏不混用的不变量不依赖「同名变体」，只依赖「选中一律 filled」。
        assertSame(Tabler.Filled.ChartAreaLine, ShellGlyphs.navFilledVariantOf(VadeIcons.Activity))
        assertSame(Tabler.Filled.LayoutGrid, ShellGlyphs.navFilledVariantOf(VadeIcons.Grid))
        assertSame(Tabler.Filled.User, ShellGlyphs.navFilledVariantOf(VadeIcons.Person))
        // 中性兜底 Circle 有意不登记：选中时保留描边并如实标 outline，不冒充实心。
        assertNull(ShellGlyphs.navFilledVariantOf(VadeIcons.Circle))
    }

    /** ④ 解析纯函数：家族随 selected 切换，tag 与实际解析出的 vector 一致。 */
    @Test
    fun navSelectionVectorSwitchesFamilyWithSelection() {
        for (destination in Destination.entries) {
            val filled = ShellGlyphs.navFilledVariantOf(destination.icon)!!
            assertSame(
                destination.icon,
                navSelectionVector(destination.icon, selected = false),
                "未选中必须保持描边（${destination.label}）",
            )
            assertSame(
                filled,
                navSelectionVector(destination.icon, selected = true),
                "选中必须换成登记的实心变体（${destination.label}）",
            )
            assertEquals(
                NavSelectionOutlineIconTag,
                navSelectionIconVariantTag(destination.icon, selected = false),
                "未选中的家族标记必须是 outline（${destination.label}）",
            )
            assertEquals(
                NavSelectionFilledIconTag,
                navSelectionIconVariantTag(destination.icon, selected = true),
                "选中的家族标记必须是 filled（${destination.label}）",
            )
        }
        // 未登记 vector（中性 Circle）：选中时保留描边，tag 如实标 outline —— 这是
        // 「不许静默混用」的可见形态：混用走查看到 outline-tagged 的选中项就红。
        assertSame(VadeIcons.Circle, navSelectionVector(VadeIcons.Circle, selected = true))
        assertEquals(
            NavSelectionOutlineIconTag,
            navSelectionIconVariantTag(VadeIcons.Circle, selected = true),
        )
    }

    /** 变体解析只许住在共享渲染器里（调用点全仓恰好 1 处）。 */
    @Test
    fun theVariantResolutionLivesOnlyInTheSharedRenderer() {
        val shared = src("shell/NavSelection.kt").readText()
        assertTrue(
            shared.contains("ShellGlyphs.navFilledVariantOf("),
            "共享渲染器不再解析实心变体了 —— 选中态的家族切换必须留在 NavSelection.kt",
        )
        val hits = File("src/commonMain/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .count { it.readText().contains("ShellGlyphs.navFilledVariantOf(") }
        assertEquals(
            1,
            hits,
            "`ShellGlyphs.navFilledVariantOf(` 的调用点出现了 $hits 处（只许 NavSelection.kt 一处）",
        )
    }

    /**
     * ⑤/⑥ 的 bar 形态共用走查（fixed / floating 各调一次）：
     *   有块       —— 4 个块节点，且选中项图标被块包住；
     *   是 filled  —— 恰好 1 个 filled 家族节点，落在选中项图标上；
     *   描边       —— 其余 3 个 tab 都是 outline 家族、没有一个 filled；
     *   文字 accent / 灰文字 —— selected 标签恰 1 个（= 选中项），idle 3 个（= 其余三项）。
     *
     * 颜色本身（accent / onSurfaceVariant）已在纯函数级钉住
     * （[labelColourRuleIsAccentVersusOnSurfaceVariant]）；渲染层钉「状态 tag 与选中项一致」
     * —— tag 由共享渲染器从 selected 写出，颜色由同一个 selected 经 `navTabColor` 决定，
     * 两条链共用一个输入，谁拆开都红。
     */
    private fun ComposeUiTest.assertSelectedFamilyAndLabels(selectedLabel: String, form: String) {
        val blocks = onAllNodesWithTag(NavSelectionBlockTag, useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertEquals(4, blocks.size, "$form：四个 tab 都要有指示块节点")
        val selectedIcon = onNodeWithContentDescription(selectedLabel, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            blocks.any { wraps(it.boundsInRoot, selectedIcon) },
            "$form：选中项「$selectedLabel」的图标没有被指示块包住",
        )

        val filled = onAllNodesWithTag(NavSelectionFilledIconTag, useUnmergedTree = true)
            .fetchSemanticsNodes().map { it.boundsInRoot }
        val outline = onAllNodesWithTag(NavSelectionOutlineIconTag, useUnmergedTree = true)
            .fetchSemanticsNodes().map { it.boundsInRoot }
        assertEquals(1, filled.size, "$form：选中 tab 必须恰好 1 个 filled 家族节点")
        assertEquals(3, outline.size, "$form：未选中 tab 必须恰好 3 个 outline 家族节点")
        assertTrue(
            filled.any { wraps(it, selectedIcon) },
            "$form：filled 家族节点没有落在选中项「$selectedLabel」的图标上 —— 同屏混用",
        )
        barLabels.filter { it != selectedLabel }.forEach { label ->
            val rect = onNodeWithContentDescription(label, useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            assertTrue(
                outline.any { wraps(it, rect) } && filled.none { wraps(it, rect) },
                "$form：未选中项「$label」不是 outline 家族 —— 同屏混用",
            )
        }

        val selectedLabels = onAllNodesWithTag(NavSelectionLabelSelectedTag, useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertEquals(1, selectedLabels.size, "$form：accent 标签必须恰好 1 个")
        assertEquals(
            listOf(selectedLabel),
            selectedLabels.map { it.combinedText() },
            "$form：accent 标签不是选中项的文字",
        )
        val idleLabels = onAllNodesWithTag(NavSelectionLabelIdleTag, useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertEquals(3, idleLabels.size, "$form：灰文字标签必须恰好 3 个")
        assertEquals(
            barLabels.filter { it != selectedLabel }.toSet(),
            idleLabels.map { it.combinedText() }.toSet(),
            "$form：灰文字标签与未选中项对不上",
        )
    }

    private fun ComposeUiTest.setFixedBar(selected: Int) {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                val backdrop = rememberGlassBackdrop()
                Box(Modifier) {
                    VadeBottomBar(
                        items = barItems(),
                        selected = selected,
                        onSelect = {},
                        style = BottomBarStyle.Fixed,
                        backdrop = backdrop,
                    )
                }
            }
        }
    }

    /** ⑤ fixed：选中「活动」—— 有块 / 是 filled / 文字 accent；未选中描边 / 灰文字。 */
    @Test
    fun fixedBarSelectedTabShowsBlockFilledIconAndAccentLabel() = runComposeUiTest {
        setFixedBar(selected = 1)
        assertSelectedFamilyAndLabels(selectedLabel = "活动", form = "fixed")
    }

    /** ⑤ floating：选中「会话」（胶囊容器不变，选中态与 fixed 同源）。 */
    @Test
    fun floatingBarSelectedTabShowsBlockFilledIconAndAccentLabel() = runComposeUiTest {
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
        assertSelectedFamilyAndLabels(selectedLabel = "会话", form = "floating")
    }

    /**
     * ⑤ rail：选中「我的」。rail 的图标没有 contentDescription（标签在图标下方），所以
     * 图标用 rail 自己的 `vade.nav.rail.icon.<id>` tag 定位。
     */
    @Test
    fun railSelectedTabShowsBlockFilledIconAndAccentLabel() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                NavRail(width = 80.dp, selected = Destination.Me, badgeOf = { null }, onSelect = {})
            }
        }
        val blocks = onAllNodesWithTag(NavSelectionBlockTag, useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertEquals(4, blocks.size, "rail：四个目的地都要有指示块节点")
        val selectedIcon = onNodeWithTag("vade.nav.rail.icon.me", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            blocks.any { wraps(it.boundsInRoot, selectedIcon) },
            "rail：选中项「我的」的图标没有被指示块包住",
        )

        val filled = onAllNodesWithTag(NavSelectionFilledIconTag, useUnmergedTree = true)
            .fetchSemanticsNodes().map { it.boundsInRoot }
        val outline = onAllNodesWithTag(NavSelectionOutlineIconTag, useUnmergedTree = true)
            .fetchSemanticsNodes().map { it.boundsInRoot }
        assertEquals(1, filled.size, "rail：选中 tab 必须恰好 1 个 filled 家族节点")
        assertEquals(3, outline.size, "rail：未选中 tab 必须恰好 3 个 outline 家族节点")
        assertTrue(
            filled.any { wraps(it, selectedIcon) },
            "rail：filled 家族节点没有落在「我的」的图标上 —— 同屏混用",
        )
        listOf("sessions", "activity", "capabilities").forEach { id ->
            val rect = onNodeWithTag("vade.nav.rail.icon.$id", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            assertTrue(
                outline.any { wraps(it, rect) } && filled.none { wraps(it, rect) },
                "rail：未选中项 $id 不是 outline 家族 —— 同屏混用",
            )
        }

        val selectedLabels = onAllNodesWithTag(NavSelectionLabelSelectedTag, useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertEquals(1, selectedLabels.size, "rail：accent 标签必须恰好 1 个")
        assertEquals(listOf("我的"), selectedLabels.map { it.combinedText() })
        val idleLabels = onAllNodesWithTag(NavSelectionLabelIdleTag, useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertEquals(3, idleLabels.size, "rail：灰文字标签必须恰好 3 个")
        assertEquals(setOf("会话", "活动", "能力"), idleLabels.map { it.combinedText() }.toSet())
    }

    /**
     * ⑥ 同屏不混用：rail + fixed **两种形态真的放同一屏**，逐 tab 断言
     * 「图标家族 == 该 tab 的选中态」—— 任一 tab 的家族与状态不符（未登记 vector 选中时
     * 冒充实心、或未选中项画了实心）都红。
     *
     * 判据的实现细节：家族 tag 由**实际解析出的 vector** 决定（`navSelectionIconVariantTag`），
     * 不是由 selected 标位 —— 所以「选中了但登记缺失」在这里表现为 filled 计数塌掉，
     * 而不是悄悄绿着。
     */
    @Test
    fun oneScreenNeverMixesFilledAndOutlineNavIconsForTheSameSelectionState() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                val backdrop = rememberGlassBackdrop()
                // NavRail 自身是 fillMaxHeight()：必须给它一个有界容器，否则它会吃光整列，
                // 下面的 fixed bar 被挤成零高、图标 bounds 全塌（第一次跑就是红在这）。
                androidx.compose.foundation.layout.Column(Modifier) {
                    androidx.compose.foundation.layout.Box(Modifier.height(400.dp)) {
                        NavRail(width = 80.dp, selected = Destination.Me, badgeOf = { null }, onSelect = {})
                    }
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
        val filled = onAllNodesWithTag(NavSelectionFilledIconTag, useUnmergedTree = true)
            .fetchSemanticsNodes().map { it.boundsInRoot }
        val outline = onAllNodesWithTag(NavSelectionOutlineIconTag, useUnmergedTree = true)
            .fetchSemanticsNodes().map { it.boundsInRoot }
        assertEquals(2, filled.size, "rail + fixed 各 1 个选中 tab，同屏必须恰好 2 个 filled 家族")
        assertEquals(6, outline.size, "rail + fixed 各 3 个未选中 tab，同屏必须恰好 6 个 outline 家族")

        // fixed 的 4 个图标有 contentDescription，可直接按标签定位。
        barLabels.forEach { label ->
            val rect = onNodeWithContentDescription(label, useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val family = when {
                filled.any { wraps(it, rect) } && outline.none { wraps(it, rect) } -> "filled"
                outline.any { wraps(it, rect) } && filled.none { wraps(it, rect) } -> "outline"
                else -> "无/不唯一"
            }
            assertEquals(
                if (label == "活动") "filled" else "outline",
                family,
                "「$label」的图标家族与选中态不符 —— 同一屏混用了实心与描边",
            )
        }
        // rail 的 4 个图标没有 contentDescription，用 per-icon tag 定位（选中 = 我的）。
        Destination.entries.forEach { destination ->
            val rect = onNodeWithTag("vade.nav.rail.icon.${destination.id}", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val family = when {
                filled.any { wraps(it, rect) } && outline.none { wraps(it, rect) } -> "filled"
                outline.any { wraps(it, rect) } && filled.none { wraps(it, rect) } -> "outline"
                else -> "无/不唯一"
            }
            assertEquals(
                if (destination == Destination.Me) "filled" else "outline",
                family,
                "rail「${destination.label}」的图标家族与选中态不符 —— 同一屏混用了实心与描边",
            )
        }
    }
}
