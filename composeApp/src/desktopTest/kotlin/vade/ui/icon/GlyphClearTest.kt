package vade.ui.icon

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import vade.ui.IconGuardTest
import vade.ui.destinations.Destination
import vade.ui.shell.BottomBarItem
import vade.ui.shell.FloatingBottomBarItem
import vade.ui.shell.NavRail
import vade.ui.shell.TopBarButton
import vade.ui.shell.bottomBarIcon
import vade.ui.shell.panelToggleIcon
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin

/**
 * g-glyph-clear：`tmp/g-icon-guard` 扫出的最后 4 处符号图标 + 10 处「汉字当图标」的回归面。
 *
 * 分两层，因为两者的证据强度不同：
 *  - **真渲染**（`runComposeUiTest`）：导航栏四个图标、选中/未选中两态、浮动底栏的兜底路径 ——
 *    断言的是**合成出来的节点树**，不是源码里写了什么；
 *  - **纯函数 / 源码扫描**：右面板开合的两态矢量映射（`hasRightPanel` 要 ≥1200 dp，默认测试窗口
 *    到不了）、`×` 关闭按钮所在私有 composable（外部调不到）、以及「这 4 个文件里再出现一个字形
 *    字面量就直接红」的最后一道。
 *
 * 汉字那一类（`话`/`活`/`能`/`我`）不是符号，守卫的词表看不见它们，所以断言方式也不同：
 *  - `Destinations` 的 `glyph` 字段**已删除** → 纯函数断言四个 `icon` 是四枚互不相同的矢量；
 *  - `NavRail` 渲出的是 `VadeIcon`（有 testTag 的 Image 节点）而不是 13 sp 文字。
 */
class GlyphClearTest {

    private fun src(rel: String) = File("src/commonMain/kotlin/vade/ui/$rel")

    /** 旧导航图标的那四个汉字（本棒删掉的 `Destination.glyph`）。 */
    private val navCjkGlyphs = listOf("话", "活", "能", "我")

    /** `ImageVector` 的绘制内容在 `root`（路径组）里；空矢量 = 什么也画不出来。 */
    private fun ImageVector.drawsSomething(): Boolean = root.iterator().hasNext()

    // --- 1 · 导航栏：四个图标是矢量，不是一个字 ----------------------------------

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun navRailDrawsAVectorForEachDestination() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                NavRail(width = 80.dp, selected = Destination.Sessions, badgeOf = { null }, onSelect = {})
            }
        }
        Destination.entries.forEach { destination ->
            // 图标槽：存在一个带 testTag 的 Image 节点（VadeIcon），而不是 13sp 文字。
            onNodeWithTag("vade.nav.rail.icon.${destination.id}", useUnmergedTree = true).assertExists()
            // 文字标签还在（可读性没被换图标换掉）。
            onNodeWithText(destination.label).assertExists()
        }
        navCjkGlyphs.forEach { glyph ->
            assertEquals(
                0,
                onAllNodesWithText(glyph, useUnmergedTree = true).fetchSemanticsNodes().size,
                "汉字当图标回归：'$glyph' 仍被渲成文字（NavigationRail 的图标槽必须是 VadeIcon）",
            )
        }
    }

    // --- 2 · 选中 / 未选中两态仍然可分 ------------------------------------------

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun navRailKeepsTheSelectedStateAfterTheVectorSwap() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                NavRail(width = 80.dp, selected = Destination.Activity, badgeOf = { null }, onSelect = {})
            }
        }
        // 恰好一项被标成 selected，且是「活动」——换图标前这条语义只存在于字重和 16% 底色里。
        val selected = SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)
        assertEquals(1, onAllNodes(selected).fetchSemanticsNodes().size, "选中项必须恰好一个")
        onNode(selected and hasText("活动")).assertExists()
        onNode(selected and hasText("会话")).assertDoesNotExist()
        // 未选中项明确是 false（不是「没设置」）。
        val unselected = SemanticsMatcher.expectValue(SemanticsProperties.Selected, false)
        assertEquals(3, onAllNodes(unselected).fetchSemanticsNodes().size, "其余三项必须显式为未选中")
    }

    // --- 3 · 四个导航图标：四枚互不相同的矢量 -----------------------------------

    @Test
    fun destinationIconsAreFourDistinctVectors() {
        assertEquals(VadeIcons.Chat, Destination.Sessions.icon)
        assertEquals(VadeIcons.Activity, Destination.Activity.icon)
        assertEquals(VadeIcons.Grid, Destination.Capabilities.icon)
        assertEquals(VadeIcons.Person, Destination.Me.icon)
        val icons = Destination.entries.map { it.icon }
        assertEquals(4, icons.toSet().size, "四个导航图标必须互不相同，否则两个目的地会看起来一样")
        icons.forEach { icon ->
            assertTrue(icon.drawsSomething(), "空矢量画不出图标：$icon")
            assertNotEquals(VadeIcons.Circle, icon, "导航图标不得退化成中性兜底圆")
        }
        // id 映射与枚举一致（底栏按 id 找图标的那条路）。
        Destination.entries.forEach { assertEquals(it.icon, VadeIcons.navIconOrNull(it.id)) }
    }

    // --- 4 · 右面板开合：两态 = 两枚不同矢量 ------------------------------------

    @Test
    fun panelToggleIsOneVectorPerState() {
        val closed = panelToggleIcon(open = false)
        val open = panelToggleIcon(open = true)
        assertEquals(ShellGlyphs.PanelExpand, closed, "面板关着时按钮必须表达「展开」")
        assertEquals(ShellGlyphs.PanelCollapse, open, "面板开着时按钮必须表达「收起」")
        assertNotEquals(closed, open, "两态必须是不同的图标（原来靠 ◂ / ▸ 两个字符区分）")
        assertTrue(closed.drawsSomething() && open.drawsSomething())
    }

    // --- 5 · 开合按钮渲出来的是图标，屏上没有 ◂ / ▸ --------------------------------

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun panelToggleButtonRendersVectorsNotChevronCharacters() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                Column {
                    TopBarButton(panelToggleIcon(open = false), onClick = {}, contentDescription = "展开右面板")
                    TopBarButton(panelToggleIcon(open = true), onClick = {}, contentDescription = "收起右面板")
                }
            }
        }
        onNodeWithContentDescription("展开右面板").assertExists()
        onNodeWithContentDescription("收起右面板").assertExists()
        listOf("◂", "▸").forEach { glyph ->
            assertEquals(
                0,
                onAllNodesWithText(glyph, useUnmergedTree = true).fetchSemanticsNodes().size,
                "开合按钮仍在渲字符 '$glyph'",
            )
        }
    }

    // --- 6 · 浮动底栏的兜底链：icon → navIconOrNull → Circle，绝无字符 -----------

    @Test
    fun bottomBarIconResolutionNeverReturnsACharacter() {
        // ① 显式 icon 优先。
        assertEquals(
            VadeIcons.Star,
            bottomBarIcon(BottomBarItem(id = "sessions", label = "会话", icon = VadeIcons.Star)),
        )
        // ② 没给 icon 时按 id 找回真正的导航矢量（旧代码在这里落进 `glyph ?: "•"`）。
        assertEquals(VadeIcons.Chat, bottomBarIcon(BottomBarItem(id = "sessions", label = "会话")))
        assertEquals(VadeIcons.Person, bottomBarIcon(BottomBarItem(id = "me", label = "我的")))
        // ③ 未知 id → 中性兜底圆，不是 "•"。
        assertEquals(VadeIcons.Circle, bottomBarIcon(BottomBarItem(id = "settings", label = "设置")))
        assertEquals(VadeIcons.Circle, bottomBarIcon(BottomBarItem(id = "", label = "")))
    }

    // --- 7 · 浮动底栏条目真渲染：兜底路径上是一枚矢量 ---------------------------

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun floatingBarItemRendersTheFallbackAsAVector() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                Row {
                    // icon 缺省 → 走 navIconOrNull("sessions") 这一档（曾经是 "•"）。
                    FloatingBottomBarItem(
                        selected = false,
                        onClick = {},
                        item = BottomBarItem(id = "sessions", label = "会话"),
                    )
                }
            }
        }
        // Image(contentDescription = label) 存在 ⇒ 图标槽画的是矢量；文字兜底不会有这个节点。
        onNodeWithContentDescription("会话").assertExists()
        assertEquals(0, onAllNodesWithText("•", useUnmergedTree = true).fetchSemanticsNodes().size, "圆点兜底回来了")
    }

    // --- 8 · 未知 id 的兜底路径同样不落字符 -------------------------------------

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun floatingBarItemUnknownIdDrawsTheCircleVector() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                Row {
                    FloatingBottomBarItem(
                        selected = true,
                        onClick = {},
                        item = BottomBarItem(id = "no-such-destination", label = "未知"),
                    )
                }
            }
        }
        onNodeWithContentDescription("未知").assertExists()
        assertEquals(0, onAllNodesWithText("•", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    // --- 9 · 本棒改过的四个文件里再出现字形字面量就红 ---------------------------

    @Test
    fun clearedShellFilesCarryNoCharacterOrCjkIconLiterals() {
        val files = listOf(
            "shell/AppShell.kt",
            "shell/FloatingBottomBar.kt",
            "shell/NavRail.kt",
            "destinations/Destinations.kt",
        )
        val offenders = mutableListOf<String>()
        for (rel in files) {
            val file = src(rel)
            assertTrue(file.isFile, "源文件不在预期位置：${file.path}")
            val source = file.readText()
            (GlyphLiteralScan.hits(source) + GlyphLiteralScan.cjkHits(source)).forEach { hit ->
                offenders += "$rel:${hit.line} \"${hit.text.trim()}\""
            }
        }
        assertTrue(offenders.isEmpty(), "本棒清过的文件又出现字形/单汉字字面量：$offenders")
    }

    // --- 10 · 全仓扫描：只剩 6 条白名单文案 -------------------------------------

    @Test
    fun wholeTreeScanFindsOnlyTheAllowlistedDemoLabels() {
        val root = File("src/commonMain/kotlin")
        assertTrue(root.isDirectory, "commonMain 不在预期位置：${root.absolutePath}")
        val result = GlyphLiteralScan.scan(root) // 不过白名单：先看原始 offender 集合
        val actual = result.offenders.map { "${it.path}:${it.line}:${it.glyph}" }.sorted()
        val expected = listOf(
            "vade/ui/demo/MotionProbe.kt:312:好",
            "vade/ui/skin/miuix/MiuixShowcase.kt:208:主",
            "vade/ui/skin/miuix/MiuixShowcase.kt:209:次",
            "vade/ui/skin/miuix/MiuixShowcase.kt:210:危",
            "vade/ui/skin/miuix/MiuixShowcase.kt:211:文",
            "vade/ui/skin/miuix/MiuixShowcase.kt:220:禁",
        ).sorted()
        assertEquals(
            expected,
            actual,
            "全仓 offender 集合变了：新增的是真图标 → 修源码；确属文案 → 在 IconGuardTest.allowed 写理由放行",
        )
        // 白名单把这 6 条全部吃掉之后必须一条不剩、且没有 stale。
        val allowed = GlyphLiteralScan.scan(root, IconGuardTest().allowList())
        assertTrue(allowed.offenders.isEmpty(), "白名单之后的残余：${allowed.offenders.map { it.render() }}")
        assertTrue(allowed.staleAllows.isEmpty(), "白名单有失效条目：${allowed.staleAllows}")
    }

    // --- 11 · 单汉字规则只认一个字，两字标签不受影响 ---------------------------

    @Test
    fun singleCjkRuleOnlyFiresOnOneCharacterLiterals() {
        assertEquals(listOf("话"), GlyphLiteralScan.cjkHits("val g = \"话\"").map { it.text.trim() })
        assertEquals(
            listOf("好"),
            GlyphLiteralScan.cjkHits("RealRow(\"好\", meta = \"2 分钟前\")").map { it.text.trim() },
        )
        // 两字以上的标签（占 commonMain 的 100+ 处）绝不能进这条规则。
        assertEquals(
            emptyList(),
            GlyphLiteralScan.cjkHits("UiText(\"会话\") /* \"我\" */ ").map { it.text.trim() },
            "两字标签与注释里的单汉字都不算 offender",
        )
        // 符号那一类判类没被汉字规则污染。
        assertTrue(GlyphLiteralScan.hits("Text(\"↑\")").isNotEmpty())
        assertEquals(emptyList(), GlyphLiteralScan.hits("Text(\"会话\")"))
    }
}
