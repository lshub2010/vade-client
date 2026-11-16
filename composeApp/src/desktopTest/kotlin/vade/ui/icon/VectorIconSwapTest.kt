package vade.ui.icon

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.graphics.vector.ImageVector
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import vade.ui.component.liquid.rememberGlassBackdrop
import vade.ui.demo.MotionProbe
import vade.ui.demo.ProbeContent
import vade.ui.destinations.Destination
import vade.ui.destinations.ProfileScreen
import vade.ui.search.SearchHost
import vade.ui.shell.BottomBarItem
import vade.ui.shell.GlassNavBar
import vade.ui.shell.UiState
import vade.ui.shell.VadeBottomBar
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.InputMode
import vade.ui.skin.LocalSkin
import vade.ui.skin.NavItem
import vade.ui.skin.material.MaterialSkin
import vade.ui.skin.miuix.MiuixShowcasePage
import vade.ui.skin.miuix.MiuixSkin

/**
 * w-g-icons 反回归：字符当图标的存量清空后，换过的每个位置渲出的必须是 `ImageVector`
 * （经 [VadeIcons] 语义导出），节点树里不得再出现该字符字串。渲染层用 `runComposeUiTest`
 * 断言节点树；纯函数映射直接断言返回值。这是 [vade.ui.IconGuardTest]（父棒守卫，本棒
 * 不许动）之外的、针对本棒改过的文件的补充面。
 */
class VectorIconSwapTest {

    /** 本棒清掉的字符图标字母表（节点树扫描用；`×`/`↓` 作为运算符/键位名不算图标，另案处理）。 */
    private val bannedGlyphs = "●◉◍◆•←✕↻▾▸■↑⋯＋★⌕"

    /** w-g-icons 改过的文件（相对 `vade/ui/`），源码级扫描防回归。 */
    private val fixedFiles = listOf(
        "demo/MotionProbe.kt",
        "destinations/ProfileScreen.kt",
        "search/SearchOverlay.kt",
        "shell/BottomBar.kt",
        "shell/GlassNavBar.kt",
        "skin/material/MaterialParts.kt",
        "skin/material/MaterialSkin.kt",
        "skin/miuix/MiuixParts.kt",
        "skin/miuix/MiuixShowcase.kt",
        "skin/miuix/MiuixSkin.kt",
        "icon/VadeIcons.kt",
    )

    // --- 纯函数映射 ----------------------------------------------------------------

    @Test
    fun navIconOrNullMapsTheFourDestinationIds() {
        assertEquals(VadeIcons.Chat, VadeIcons.navIconOrNull("sessions"))
        assertEquals(VadeIcons.Activity, VadeIcons.navIconOrNull("activity"))
        assertEquals(VadeIcons.Grid, VadeIcons.navIconOrNull("capabilities"))
        assertEquals(VadeIcons.Person, VadeIcons.navIconOrNull("me"))
        // 四个映射互不相同——同一个矢量被用在两个目的地说明映射写错了。
        listOf(VadeIcons.Chat, VadeIcons.Activity, VadeIcons.Grid, VadeIcons.Person)
            .let { icons -> assertEquals(icons.size, icons.toSet().size) }
    }

    @Test
    fun navIconOrNullReturnsNullForUnknownIds() {
        assertNull(VadeIcons.navIconOrNull("settings"))
        assertNull(VadeIcons.navIconOrNull(""))
        assertNull(VadeIcons.navIconOrNull("Sessions")) // 大小写敏感，逐字匹配 Destination.id
        assertNull(VadeIcons.navIconOrNull("sessions "))
    }

    @Test
    fun appendedVectorsAreNonNullAndDistinct() {
        // w-g-icons 追加的三个语义矢量：都存在、都是 ImageVector、Circle 与导航图标不撞。
        assertNotNull(VadeIcons.Star)
        assertNotNull(VadeIcons.Mic)
        val circle: ImageVector = VadeIcons.Circle
        assertNotNull(circle)
        assertNotEquals(circle, VadeIcons.Chat)
        assertNotEquals(circle, VadeIcons.Grid)
        assertNotEquals(circle, VadeIcons.Person)
    }

    // --- 导航渲染器（NavItem 冻结无 icon 槽 → id 映射） ----------------------------

    private fun navFixtures(): List<NavItem> = listOf(
        NavItem("sessions", "会话"),
        NavItem("activity", "活动", badge = "2"),
        NavItem("capabilities", "能力"),
        NavItem("me", "我的"),
    )

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun glassNavBarRendersVectorsNeverGlyphText() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                GlassNavBar(items = navFixtures(), selected = 0, onSelect = {}, style = BottomBarStyle.Fixed)
            }
        }
        onNodeWithText("会话").assertExists()
        onNodeWithText("我的").assertExists()
        bannedGlyphs.forEach { glyph ->
            assertEquals(0, onAllNodesWithText(glyph.toString()).fetchSemanticsNodes().size, "字符图标 '$glyph' 不应再渲出")
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun materialSkinBottomBarRendersVectorsNeverGlyphText() = runComposeUiTest {
        val skin = MaterialSkin()
        setContent {
            CompositionLocalProvider(LocalSkin provides skin) {
                skin.BottomBar(items = navFixtures(), selected = 1, onSelect = {}, style = BottomBarStyle.Fixed)
            }
        }
        onNodeWithText("能力").assertExists()
        bannedGlyphs.forEach { glyph ->
            assertEquals(0, onAllNodesWithText(glyph.toString()).fetchSemanticsNodes().size, "字符图标 '$glyph' 不应再渲出")
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun miuixSkinBottomBarRendersVectorsNeverGlyphText() = runComposeUiTest {
        val skin = MiuixSkin()
        setContent {
            CompositionLocalProvider(LocalSkin provides skin) {
                skin.BottomBar(items = navFixtures(), selected = 2, onSelect = {}, style = BottomBarStyle.Fixed)
            }
        }
        onNodeWithText("我的").assertExists()
        bannedGlyphs.forEach { glyph ->
            assertEquals(0, onAllNodesWithText(glyph.toString()).fetchSemanticsNodes().size, "字符图标 '$glyph' 不应再渲出")
        }
    }

    // --- shell 固定底栏（BottomBarItem.icon 缺省 → Circle 矢量兜底，不回退 "•"） ----

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun fixedBottomBarFallsBackToCircleVectorNotGlyph() = runComposeUiTest {
        // icon = null 的条目走 `item.icon ?: VadeIcons.Circle` 兜底路径——旧代码在这里
        // 渲 "•" 字符，现在必须是一枚矢量。
        val items = listOf(
            BottomBarItem(id = "sessions", label = "会话"), // icon 缺省 → Circle
            BottomBarItem(id = "activity", label = "活动", icon = VadeIcons.Activity),
            BottomBarItem(id = "capabilities", label = "能力", icon = VadeIcons.Grid, badge = "3"),
            BottomBarItem(id = "me", label = "我的", icon = VadeIcons.Person),
        )
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                val backdrop = rememberGlassBackdrop()
                VadeBottomBar(items = items, selected = 0, onSelect = {}, style = BottomBarStyle.Fixed, backdrop = backdrop)
            }
        }
        onNodeWithText("会话").assertExists()
        bannedGlyphs.forEach { glyph ->
            assertEquals(0, onAllNodesWithText(glyph.toString()).fetchSemanticsNodes().size, "字符图标 '$glyph' 不应再渲出")
        }
    }

    // --- 搜索覆盖层（← / ✕ 顶栏按钮 → Back / Close 矢量） --------------------------

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun searchOverlayRendersVectorButtonsNeverGlyphText() = runComposeUiTest {
        val state = UiState(BottomBarStyle.Fixed, "material", Destination.Sessions)
        state.openSearch()
        // 顶栏键位提示（"↑ ↓ 选择 · Enter …"）是键帽名不是图标，不在禁列（guard 也不扫）。
        val paletteBanned = bannedGlyphs.replace("↑", "")
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                SearchHost(state)
            }
        }
        val back = onAllNodesWithContentDescription("关闭搜索").fetchSemanticsNodes()
        val close = onAllNodesWithContentDescription("关闭面板").fetchSemanticsNodes()
        assertTrue(back.isNotEmpty() || close.isNotEmpty(), "覆盖层顶栏的图标按钮没渲染出来")
        paletteBanned.forEach { glyph ->
            assertEquals(0, onAllNodesWithText(glyph.toString()).fetchSemanticsNodes().size, "字符图标 '$glyph' 不应再渲出")
        }
    }

    // --- 输入条语音按钮（◍ / ◉ → Mic 矢量，双皮肤） --------------------------------

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun inputBarMicRendersVectorNotGlyphOnBothSkins() = runComposeUiTest {
        val material = MaterialSkin()
        val miuix = MiuixSkin()
        setContent {
            CompositionLocalProvider(LocalSkin provides material) {
                material.InputBar(value = "", onValueChange = {}, mode = InputMode.Idle, voiceAvailable = true)
            }
            CompositionLocalProvider(LocalSkin provides miuix) {
                miuix.InputBar(value = "", onValueChange = {}, mode = InputMode.Idle, voiceAvailable = true)
            }
        }
        // 两个皮肤各一枚语音按钮（D97：voiceAvailable 时必画）。
        assertEquals(2, onAllNodesWithContentDescription("语音输入").fetchSemanticsNodes().size)
        bannedGlyphs.forEach { glyph ->
            assertEquals(0, onAllNodesWithText(glyph.toString()).fetchSemanticsNodes().size, "字符图标 '$glyph' 不应再渲出")
        }
    }

    // --- miuix showcase（Button 换真文案 + SendOrb 矢量 + 底栏矢量） ----------------

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun miuixShowcaseRendersNoCharacterIcons() = runComposeUiTest {
        setContent {
            MiuixShowcasePage(skin = MiuixSkin(), bar = BottomBarStyle.Fixed)
        }
        // 按钮矩阵现在是真文案（"发送"/"添加"/"删除"/"更多"，各出现 ≥1 次）。
        assertTrue(onAllNodesWithText("发送").fetchSemanticsNodes().isNotEmpty(), "按钮矩阵的真文案没了")
        assertTrue(onAllNodesWithText("添加").fetchSemanticsNodes().isNotEmpty(), "禁用按钮的真文案没了")
        bannedGlyphs.forEach { glyph ->
            assertEquals(0, onAllNodesWithText(glyph.toString()).fetchSemanticsNodes().size, "字符图标 '$glyph' 不应再渲出")
        }
    }

    // --- 我的页选择 chip（"● $id" → 纯文字 + 样式选中态） ---------------------------

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun profileChipsShowSelectionByStyleNotGlyph() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                ProfileScreen(
                    skinId = "material",
                    barStyle = BottomBarStyle.Fixed,
                    containerLabel = "test",
                    onSkinChange = {},
                    onBarChange = {},
                )
            }
        }
        // g-miuix-slots · D9：chip 上现在是**用户可读名**（经典 / Miuix / 玻璃），内部 id 退到
        // 下一行 11sp 的「内部 id」次级文案里 —— 两样都得在，且都不许带 ● 前缀。
        onNodeWithText("经典").assertExists()
        onNodeWithText("Miuix").assertExists()
        onNodeWithText("玻璃").assertExists()
        onNodeWithText("停靠").assertExists()
        bannedGlyphs.forEach { glyph ->
            assertEquals(0, onAllNodesWithText(glyph.toString()).fetchSemanticsNodes().size, "字符图标 '$glyph' 不应再渲出")
        }
    }

    // --- demo 行（● / ◆ / ★ → Running / Archive / Star 矢量） ----------------------

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun motionProbeRendersVectorIconsNotGlyphs() = runComposeUiTest {
        setContent {
            MotionProbe(autoScroll = false, measureFps = false, content = ProbeContent.Real)
        }
        // 夹具前几行在首屏内；扫描覆盖所有已合成行。
        assertTrue(onAllNodesWithText("收藏").fetchSemanticsNodes().isNotEmpty(), "demo 夹具行没渲染")
        bannedGlyphs.forEach { glyph ->
            assertEquals(0, onAllNodesWithText(glyph.toString()).fetchSemanticsNodes().size, "字符图标 '$glyph' 不应再渲出")
        }
        // 不做 swipe+waitForIdle：探针常驻帧循环永不静止，会把测试体卡满 1m 超时；
        // 懒列表深处的行由 fixedFilesCarryNoGlyphTextLiterals 的源码级扫描兜底。
    }

    // --- 源码级扫描（渲染测试盖不到未合成行；这是最后一道） -------------------------

    @Test
    fun fixedFilesCarryNoGlyphTextLiterals() {
        // 注意：正则用运行期拼接，不用 `"""..."""` 源码 literal——raw string 以 `""""`
        // 结尾时末尾引号会被并进 pattern（多出一个字面 `"`），只匹配单行 `Text("X")`，
        // 跨行字形全部漏扫（现 IconGuardTest 的正则即有此行为）。
        // ↑↓（键帽名）、×（乘号）、…（省略号）是合法文本字符，不是图标——不扫。
        val vocab = "←→▲▼■□●○◆＋✕✓▾▸‹⌕✎•◂⧗"
        val bareText = Regex("\\bText\\(\\s*\"([" + vocab + "])")
        val uiText = Regex("UiText\\(\\s*\"([" + vocab + "])")
        val offenders = mutableListOf<String>()
        for (rel in fixedFiles) {
            val f = File("src/commonMain/kotlin/vade/ui/$rel")
            assertTrue(f.isFile, "源文件不在预期位置: ${f.path}")
            val text = f.readText()
            bareText.findAll(text).forEach { m -> offenders += "$rel: Text(\"${m.groupValues[1]}\")" }
            uiText.findAll(text).forEach { m -> offenders += "$rel: UiText(\"${m.groupValues[1]}\")" }
        }
        assertTrue(
            offenders.isEmpty(),
            "字符当图标回归（必须用 vade.ui.icon.VadeIcon + VadeIcons）：$offenders",
        )
    }
}
