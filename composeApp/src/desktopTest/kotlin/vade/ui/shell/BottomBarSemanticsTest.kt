package vade.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import vade.ui.destinations.Destination
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.LocalSkin
import vade.ui.skin.SkinGlass
import vade.ui.skin.material.MaterialSkin
import vade.ui.testutil.IsolatedPreferencesTest

/**
 * g-nav-fix —— `g-nav-visual` 拍到的两个真问题的回归面。
 *
 * **① 固定底栏缺两态语义。** `NavRail` 与 `FloatingBottomBarItem` 都发 `selected` + `Role.Tab`，
 * `FixedBottomBarItem` 只有 `.clickable` —— 而 `App(initialBar = BottomBarStyle.Fixed)` 就是默认
 * 手机形态。设备 dump 的对照：rail `selected=true` 1 个、浮动胶囊 1 个、**固定底栏 0 个**。
 * 这里的断言分三层：**恰好 1 个 true**（每个 tab 都轮一遍）· **未选中显式 false**（键存在、值为
 * false，不是「没说」）· **四项全是 `Role.Tab`**。
 *
 * **③ 无模糊胶囊半透明透底。** 模糊路径的 26 %/34 % 染色不能动（顶栏是唯一被认可的视觉基线），
 * 所以只在 `isBlurEnabled == false` 这一条分支上把胶囊压成不透明。这一层断言的是**像素预算**：
 * `(1 - α) × 255` 是背景文字能渗到 label 上的上限，必须 ≤ 8/255（与 `FloatingHaloAlpha*` 同一口径）。
 *
 * 还有两条**不许回归**的护栏：rail / 浮动胶囊的语义计数不变；共享玻璃 token
 * （`SkinGlass.tintLight/tintDark`、`colors.surfaceGlass`）像素级不动。
 */
@OptIn(ExperimentalTestApi::class)
class BottomBarSemanticsTest : IsolatedPreferencesTest() {

    private fun src(rel: String) = File("src/commonMain/kotlin/vade/ui/$rel")

    private fun barItems() = listOf(
        BottomBarItem(id = "sessions", label = "会话"),
        BottomBarItem(id = "activity", label = "活动"),
        BottomBarItem(id = "capabilities", label = "能力"),
        BottomBarItem(id = "me", label = "我的"),
    )

    private val labels = listOf("会话", "活动", "能力", "我的")

    /**
     * `Color` 把 sRGB 色按 8 bit 打包，`Color.White.copy(alpha = 0.26f).alpha` 读回来是
     * 66/255 = 0.25882354，不是 0.26 本身。1/255 的容差就是「同一个 8 bit 值」。
     */
    private val alphaTolerance = 1f / 255f

    // --- 语义树读取（合并树，不给 useUnmergedTree：读屏看到的正是合并后的节点） ---------

    /** 所有**声明了** `Selected` 的节点 —— false 也算，这正是「未选中必须显式 false」的取证方式。 */
    private fun ComposeUiTest.selectionNodes(): List<SemanticsNode> =
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).fetchSemanticsNodes()

    private fun ComposeUiTest.tabRoleNodes(): List<SemanticsNode> =
        onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .fetchSemanticsNodes()

    private fun SemanticsNode.markedSelected(): Boolean = config[SemanticsProperties.Selected]

    private fun SemanticsNode.labelOf(): String = when {
        config.contains(SemanticsProperties.Text) ->
            config[SemanticsProperties.Text].joinToString("") { it.toString() }

        config.contains(SemanticsProperties.ContentDescription) ->
            config[SemanticsProperties.ContentDescription].joinToString("") { it.toString() }

        else -> ""
    }

    private fun List<SemanticsNode>.selectedLabels(): List<String> =
        filter { it.markedSelected() }.map { it.labelOf() }

    private fun ComposeUiTest.assertOneSelection(label: String, style: BottomBarStyle) {
        val nodes = selectionNodes()
        assertEquals(4, nodes.size, "四个 tab 都必须**声明** selected（$style）")
        assertEquals(listOf(label), nodes.selectedLabels(), "$style 下 selected=true 必须恰好是「$label」一个")
        assertEquals(4, tabRoleNodes().size, "$style 下四个 tab 都必须带 Role.Tab")
    }

    private fun ComposeUiTest.setBar(
        style: BottomBarStyle,
        selectedIndex: Int = 0,
        dark: Boolean = false,
        onSelect: (Int) -> Unit = {},
    ) {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin(dark = dark)) {
                Box(Modifier) {
                    VadeBottomBar(
                        items = barItems(),
                        selected = selectedIndex,
                        onSelect = onSelect,
                        style = style,
                    )
                }
            }
        }
    }

    // === ① 固定底栏：两态语义 =====================================================

    /**
     * 固定底栏（默认形态）选中项恰好 1 个 —— 就是 `g-nav-visual` 实测为 0 的那个数字。
     * 每个 tab 都轮一遍：不是「碰巧第一项对了」，而是四项都能成为那个 1。
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun fixedBarHasExactlyOneSelectedTabForEveryTab() = runComposeUiTest {
        var selected by mutableStateOf(0)
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                Box(Modifier) {
                    VadeBottomBar(
                        items = barItems(),
                        selected = selected,
                        onSelect = { selected = it },
                        style = BottomBarStyle.Fixed,
                    )
                }
            }
        }
        labels.forEachIndexed { index, label ->
            runOnIdle { selected = index }
            waitForIdle()
            val nodes = selectionNodes()
            assertEquals(4, nodes.size, "固定底栏四个 tab 都必须声明 selected（index=$index 时少了一个）")
            assertEquals(listOf(label), nodes.selectedLabels(), "选中「$label」时 selected=true 必须恰好 1 个")
        }
    }

    /** 未选中项必须是 `selected = false`，**不是不设**：读屏要能区分「未选中」与「没有选中信息」。 */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun fixedBarUnselectedTabsExplicitlyReportFalse() = runComposeUiTest {
        setBar(style = BottomBarStyle.Fixed, selectedIndex = 2)
        val nodes = selectionNodes()
        assertEquals(4, nodes.size)
        assertEquals(1, nodes.count { it.markedSelected() }, "选中项必须恰好 1 个")
        assertEquals(
            3,
            nodes.count { !it.markedSelected() },
            "三个未选中项必须显式带 selected=false（键缺失会让这条变成 0）",
        )
        // 键存在性再确认一次：false 值也算 keyIsDefined 命中，上面那条才有意义。
        assertEquals(
            4,
            onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).fetchSemanticsNodes().size,
        )
        assertEquals(listOf("能力"), nodes.selectedLabels())
    }

    /** `Role.Tab` 在四个 tab 上都在（rail / 胶囊一直有，固定底栏此前一条都没有）。 */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun fixedBarItemsAllCarryTheTabRole() = runComposeUiTest {
        setBar(style = BottomBarStyle.Fixed)
        val roleNodes = tabRoleNodes()
        assertEquals(4, roleNodes.size, "固定底栏四项都必须 role = Role.Tab")
        assertEquals(labels.sorted(), roleNodes.map { it.labelOf() }.sorted())
    }

    /** 合并语义不能把点击吃掉：`clickable` 仍在同一节点上，点哪个 tab 就回哪个 index。 */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun fixedBarTabsStillDispatchTheirIndex() = runComposeUiTest {
        var clicked = -1
        setBar(style = BottomBarStyle.Fixed, onSelect = { clicked = it })
        onNodeWithText("能力").performClick()
        assertEquals(2, clicked, "点「能力」必须回 index 2（合并语义后点击不能再断）")
    }

    /** 深色主题同一套：token 换了，语义不能跟着少。 */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun fixedBarKeepsItsSelectionSemanticsInDarkTheme() = runComposeUiTest {
        setBar(style = BottomBarStyle.Fixed, selectedIndex = 1, dark = true)
        val nodes = selectionNodes()
        assertEquals(4, nodes.size, "深色主题下同样要四项都声明 selected")
        assertEquals(listOf("活动"), nodes.selectedLabels())
        assertEquals(4, tabRoleNodes().size)
    }

    // === 回归：rail 与浮动胶囊的语义计数不许变 =====================================

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun navRailKeepsItsOneSelectedTabAndTabRoles() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                NavRail(width = 80.dp, selected = Destination.Activity, badgeOf = { null }, onSelect = {})
            }
        }
        val nodes = selectionNodes()
        assertEquals(4, nodes.size, "rail 四项都要声明 selected")
        assertEquals(listOf("活动"), nodes.selectedLabels())
        assertEquals(4, tabRoleNodes().size, "rail 四项都要带 Role.Tab")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun floatingPillKeepsItsOneSelectedTabAndTabRoles() = runComposeUiTest {
        setBar(style = BottomBarStyle.Floating, selectedIndex = 3)
        assertOneSelection(label = "我的", style = BottomBarStyle.Floating)
    }

    /** 有模糊形态：语义与无模糊形态一致（这条同时守住「两种形态各自独立计数」的另一半）。 */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun blurredPillKeepsItsOneSelectedTabAndTabRoles() = runComposeUiTest {
        setBar(style = BottomBarStyle.FloatingBlur, selectedIndex = 1)
        assertOneSelection(label = "活动", style = BottomBarStyle.FloatingBlur)
    }

    /**
     * 同一棵树里两种形态并存：**各自 1 个 true**，合计 2 个 —— 计数互不串味
     * （`selected` 是每个 item 自己的状态，不是任何全局单例）。
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun twoFormsCountTheirSelectionIndependently() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                Column(Modifier) {
                    // 固定底栏选中「会话」(0)
                    VadeBottomBar(
                        items = barItems(),
                        selected = 0,
                        onSelect = {},
                        style = BottomBarStyle.Fixed,
                    )
                    Row(Modifier) {
                        // 浮动胶囊选中「能力」(2)
                        VadeBottomBar(
                            items = barItems(),
                            selected = 2,
                            onSelect = {},
                            style = BottomBarStyle.Floating,
                        )
                    }
                }
            }
        }
        val nodes = selectionNodes()
        assertEquals(8, nodes.size, "两种形态各四项，都必须声明 selected")
        assertEquals(2, nodes.count { it.markedSelected() }, "每个形态各 1 个 true，合计 2")
        assertEquals(listOf("会话", "能力"), nodes.selectedLabels())
        assertEquals(8, tabRoleNodes().size)
    }

    // === ③ 无模糊胶囊：不透明度预算 ===============================================

    /**
     * 无模糊胶囊的底色必须不透明，且「渗出」预算 `(1-α)×255 ≤ 8/255` —— 8/255 是本仓
     * 已有的可见门槛（`FloatingHaloAlpha*` 用的就是同一口径）。α = 0.84 时这条会算出 40.8/255。
     */
    @Test
    fun noBlurCapsuleIsOpaqueWithinTheBleedBudget() {
        val glass = MaterialSkin().colors.surfaceGlass
        // 前提：token 本身是半透明的 —— 否则这条测试什么也没证明。
        assertTrue(glass.alpha < 0.9f, "前提失效：surfaceGlass 已经不是半透明了（α=${glass.alpha}）")
        val capsule = noBlurPillContainer(glass)
        assertEquals(1f, capsule.alpha, "无模糊胶囊必须不透明（α=${capsule.alpha}）")
        val bleed = (1f - capsule.alpha) * 255f
        assertTrue(bleed <= 8f, "背景文字渗出上限 ${bleed}/255 超过 8/255 预算")
        // 色相不变：只动 alpha，不动 RGB。
        assertEquals(glass.red, capsule.red)
        assertEquals(glass.green, capsule.green)
        assertEquals(glass.blue, capsule.blue)
    }

    /** 无模糊路径在深色主题同样不透明（暗色 canvas 上「压字」更刺眼）。 */
    @Test
    fun noBlurCapsuleIsOpaqueInDarkThemeToo() {
        val glass = MaterialSkin(dark = true).colors.surfaceGlass
        val capsule = noBlurPillContainer(glass)
        assertEquals(1f, capsule.alpha, "深色无模糊胶囊同样必须不透明")
        assertEquals(0f, (1f - capsule.alpha) * 255f, "渗出必须是 0/255")
    }

    /** 有模糊路径一像素不动：26 % 浅色 / 34 % 深色（这条红 = 有人动了被认可的玻璃基线）。 */
    @Test
    fun blurredCapsuleKeepsTheApprovedTint() {
        val light = blurPillContainer(dark = false)
        val dark = blurPillContainer(dark = true)
        assertEquals(0.26f, light.alpha, alphaTolerance, "浅色玻璃染色必须仍是 26%")
        assertEquals(0.34f, dark.alpha, alphaTolerance, "深色玻璃染色必须仍是 34%")
        // 色相也要对上：白 / #0E0E10（只比组件，避免 Color 内部表示差异造成假红）。
        assertEquals(1f, light.red, "浅色染色必须仍是白")
        assertEquals(1f, light.green)
        assertEquals(1f, light.blue)
        assertEquals(0x0E / 255f, dark.red, "深色染色必须仍是 #0E0E10")
        assertEquals(0x0E / 255f, dark.green)
        assertEquals(0x10 / 255f, dark.blue)
    }

    /** 共享玻璃 token 没被本次改动挪动：`SkinGlass` 默认 26 %/34 %，material `surfaceGlass` 84 %。 */
    @Test
    fun sharedGlassTokensAreNotMoved() {
        val glass = SkinGlass()
        assertEquals(0.26f, glass.tintLight.alpha, alphaTolerance, "SkinGlass.tintLight 默认值被改了 —— 顶栏会跟着变")
        assertEquals(0.34f, glass.tintDark.alpha, alphaTolerance, "SkinGlass.tintDark 默认值被改了 —— 顶栏会跟着变")
        assertEquals(24f, glass.blurRadiusDp, "共享模糊半径被改了")
        assertEquals(0.84f, MaterialSkin().colors.surfaceGlass.alpha, alphaTolerance, "material surfaceGlass 被改了")
        assertEquals(
            0.78f,
            MaterialSkin(dark = true).colors.surfaceGlass.alpha,
            alphaTolerance,
            "material 深色 surfaceGlass 被改了",
        )
        assertEquals(
            0.34f,
            MaterialSkin(dark = true).glass.tintDark.alpha,
            alphaTolerance,
            "material 深色玻璃染色被改了",
        )
    }

    // === 源码护栏：三处同构 / 回退分支只用局部覆盖 =================================

    /**
     * 三形态同构：三个渲染点都必须出现同一组三行 —— `mergeDescendants` / `this.selected` /
     * `role = Role.Tab`。少一处就是本轮那个 bug 的形状（语义靠复制粘贴传播，就会漏第四个文件）。
     */
    @Test
    fun allThreeNavFormsCarryTheSameSemanticsBlock() {
        val files = listOf(
            "shell/NavRail.kt" to "rail",
            "shell/FloatingBottomBar.kt" to "浮动胶囊",
            "shell/BottomBar.kt" to "固定底栏",
        )
        val missing = mutableListOf<String>()
        for ((rel, name) in files) {
            val file = src(rel)
            assertTrue(file.isFile, "源文件不在预期位置：${file.path}")
            val source = file.readText()
            listOf("mergeDescendants = true", "this.selected =", "role = Role.Tab").forEach { needle ->
                if (!source.contains(needle)) missing += "$rel($name) 缺 `$needle`"
            }
        }
        assertEquals(emptyList(), missing, "三形态语义必须同构：$missing")
    }

    /**
     * ③ 的回退分支只能走局部覆盖：`FloatingBottomBar.kt` 里不许再把裸 `surfaceGlass` 当胶囊底色
     * 用（那样 84 % 的透明底会随 token 漂移，且顶栏也被迫跟着改）。
     */
    @Test
    fun noBlurBranchUsesTheLocalOverrideNotTheRawToken() {
        val source = src("shell/FloatingBottomBar.kt").readText()
        assertTrue(
            source.contains("noBlurPillContainer(skin.colors.surfaceGlass)"),
            "无模糊分支必须经 noBlurPillContainer() 局部提不透明度",
        )
        assertTrue(
            !source.contains("} else {\n        skin.colors.surfaceGlass"),
            "无模糊分支又回到了裸 surfaceGlass",
        )
        assertTrue(
            source.contains("blurPillContainer(dark)"),
            "模糊分支必须走 blurPillContainer(dark)（有模糊路径不许动）",
        )
    }

    /** ① 的源码护栏：固定底栏 item 的 semantics 必须在 `.clickable` **之前**（同 rail 的写法）。 */
    @Test
    fun fixedBarItemDeclaresSemanticsBeforeClickable() {
        val source = src("shell/BottomBar.kt").readText()
        val itemStart = source.indexOf("private fun RowScope.FixedBottomBarItem")
        assertTrue(itemStart > 0, "找不到 FixedBottomBarItem —— 源码护栏失去作用对象")
        val body = source.substring(itemStart)
        val semanticsAt = body.indexOf(".semantics(mergeDescendants = true)")
        val clickableAt = body.indexOf(".clickable(onClick = onClick)")
        assertTrue(semanticsAt > 0, "FixedBottomBarItem 没有 semantics 块")
        assertTrue(clickableAt > 0, "FixedBottomBarItem 的 clickable 不见了")
        assertTrue(semanticsAt < clickableAt, "semantics 必须在 clickable 之前，与 rail/胶囊同形")
        assertTrue(
            body.contains("this.selected = selected"),
            "selected 必须写在 item 自己的布尔参数上（不是写死 true）",
        )
    }

    /**
     * ④ 的护栏：未选中槽位不许画「等于父底色」的方块 —— 那是把容器背景写死了第二遍，
     * 换成 glass 底时未选中项会突然变成一块 surfaceVariant 的实心块。
     *
     * g-nav-selected：这条**绘制**已搬到共享渲染器 `NavSelection.kt`（三形态共用一份），所以
     * 断言对象跟着搬；不变量一条没改（未选中必须显式透明、不许 surfaceVariant），并且对 rail
     * 侧保留反向断言——它不许自己再画一遍。
     */
    @Test
    fun navRailUnselectedSlotPaintsNoFill() {
        val shared = src("shell/NavSelection.kt")
        assertTrue(shared.isFile, "共享选中态渲染器不在预期位置：${shared.path}")
        val sharedSource = shared.readText()
        assertTrue(
            sharedSource.contains("else Color.Transparent"),
            "未选中槽位必须显式无填充（否则和容器底色耦合）",
        )
        assertTrue(
            !sharedSource.contains("else skin.colors.surfaceVariant"),
            "未选中槽位又画回 surfaceVariant 了",
        )
        val railSource = src("shell/NavRail.kt").readText()
        assertTrue(
            !railSource.contains("else skin.colors.surfaceVariant"),
            "rail 自己又画了一遍 surfaceVariant 填充",
        )
    }

    /**
     * 语义节点不该在固定底栏里重复计数：合并后每个 tab 恰好一个节点带 label
     * （图标 contentDescription + 标签文字都并到同一个节点，不会出现 8 个「会话」）。
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun fixedBarMergesEachTabIntoASingleLabelledNode() = runComposeUiTest {
        setBar(style = BottomBarStyle.Fixed)
        labels.forEach { label ->
            val matches = onAllNodes(hasText(label)).fetchSemanticsNodes()
            assertEquals(1, matches.size, "「$label」在合并树里必须恰好 1 个节点（实际 ${matches.size}）")
            assertTrue(
                matches.first().config.contains(SemanticsProperties.Selected),
                "「$label」节点缺 selected",
            )
        }
    }

    /** 兜底：`onNode(...)` 找得到每一项，说明 `.clickable` 没被 semantics 挤掉可点语义。 */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun fixedBarEveryTabIsReachableByText() = runComposeUiTest {
        setBar(style = BottomBarStyle.Fixed)
        labels.forEach { label ->
            onNode(hasText(label)).assertExists()
        }
    }

}
