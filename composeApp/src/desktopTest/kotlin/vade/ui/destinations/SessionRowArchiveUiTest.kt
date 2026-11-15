package vade.ui.destinations

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import vade.ui.data.SessionSummary
import vade.ui.data.TimeBucket
import vade.ui.data.sessionGroups
import vade.ui.meta.MetaColors
import vade.ui.meta.MetaGroup
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * g-archive-ui · 会话行上的「已归档」标记与「组名」。
 *
 * 上一棒把 `archived` / `groupId` 投影进了 [SessionSummary]，但列表行一个都不渲染 ——
 * 用户要的「会话要有分类」在界面上等于不存在。这里钉三层：
 *  1. **纯规则**（[sessionGroupTag]）：查不到组就退回裸 id、分组为空就不显示、颜色只认合法
 *     `#RRGGBB` 不编色；
 *  2. **真的渲出来**：归档行有一枚矢量图的「已归档」（`contentDescription`，不是字符/汉字），
 *     有组的行显示组名；
 *  3. **不多渲**：未归档且未分组的行**一条多余文字都没有**（整棵语义树的文本与基线逐字相等），
 *     归档标记只加图标不加文字。
 */
@OptIn(ExperimentalTestApi::class)
class SessionRowArchiveUiTest {

    // ------------------------------------------------------------ 1. 组名解析（纯函数）

    @Test
    fun noGroupMeansNoTagSoTheRowAddsNothing() {
        assertNull(sessionGroupTag(null, listOf(PROJECT)), "未分组不许显示任何东西")
        assertNull(sessionGroupTag("", listOf(PROJECT)), "空串不是分组")
        assertNull(sessionGroupTag("   ", listOf(PROJECT)), "空白串同样不是分组")
    }

    @Test
    fun knownGroupResolvesToItsNameAndColor() {
        val tag = assertNotNull(sessionGroupTag("g-1", listOf(PROJECT, INSPIRATION)), "有组必须有标签")
        assertEquals("项目", tag.label, "组名来自 MetaGroup.name，不是 id")
        assertEquals(MetaColors.parse("#3B82F6"), tag.color, "组色来自 MetaGroup.colorHex")
    }

    @Test
    fun unknownGroupFallsBackToTheRawIdInsteadOfPretendingUngrouped() {
        // 分组表没加载 / id 拼错：显示裸 id。UI 难看但诚实 —— 假装未分组会让用户以为
        // 自己拖丢了分类，而那正是「分类」这条需求最不该出错的地方。
        val tag = assertNotNull(sessionGroupTag("g-404", emptyList()))
        assertEquals("g-404", tag.label)
        assertNull(tag.color, "查不到组就没有组色，不许拿主色顶替")
    }

    @Test
    fun invalidColorIsRejectedRatherThanInvented() {
        // `color` 在契约里是被清除后存 null 的可选字段；畸形值不该被悄悄渲染成某个预设色。
        val broken = PROJECT.copy(colorHex = "#GGGGGG")
        val tag = assertNotNull(sessionGroupTag("g-1", listOf(broken)))
        assertEquals("项目", tag.label)
        assertNull(tag.color, "非法 #RRGGBB 必须落 null（调用方退化成 muted 点）")
        val cleared = PROJECT.copy(colorHex = null)
        assertNull(assertNotNull(sessionGroupTag("g-1", listOf(cleared))).color, "被清除的颜色 = 没有颜色")
    }

    @Test
    fun blankGroupNameFallsBackToTheId() {
        // 组名是服务端必填，但畸形数据不该让行上出现一个「只有一个色点、没有名字」的谜之分类。
        val nameless = PROJECT.copy(name = "   ")
        assertEquals("g-1", assertNotNull(sessionGroupTag("g-1", listOf(nameless))).label)
    }

    // ------------------------------------------------------------ 2. 归档标记

    @Test
    fun archivedRowRendersTheBadgeAndLiveRowDoesNot() {
        assertEquals(1, archivedBadgeCount(row(archived = true)), "归档行必须有一枚「已归档」标记")
        assertEquals(0, archivedBadgeCount(row(archived = false)), "未归档行不许有标记")
        assertEquals(
            "已归档",
            ARCHIVED_BADGE_LABEL,
            "标记的 contentDescription 是本棒与测试共用的唯一定义",
        )
    }

    @Test
    fun theBadgeIsAnIconNotText() {
        // 图标必须是 ImageVector（`VadeIcons.Archive`）—— 字符/汉字当图标是明令禁止的。
        // 语义树里证明这一点：标记只出现在 contentDescription 上，**不产生任何文本节点**。
        val plain = textsOf(row(archived = false))
        val archived = textsOf(row(archived = true))
        assertEquals(plain, archived, "归档标记不许给行里加一段文字（它是图标，不是「已归档」三个字）")
        assertEquals(1, archivedBadgeCount(row(archived = true)))
    }

    // ------------------------------------------------------------ 3. 分组色点渲染 / 不多渲（Q2-B 单行形态）

    @Test
    fun groupedRowCarriesTheGroupDotAndUngroupedRowDoesNot() {
        // Q2-B（g-route-transition 口径变更）：单行条目 = 组色点 + 标题。组名不再是行上
        // 的一段文字，而是色点的读屏语义（`分组：<名>`）——「分类」必须仍可被读屏报出，
        // 测试也用同一定位。预览行已废除，任何行都不该再有第二行文字。
        val ungrouped = dotCount("分组：项目", row(groupId = null))
        val grouped = dotCount("分组：项目", row(groupId = "g-1"), metaGroups = listOf(PROJECT))
        assertEquals(0, ungrouped, "未分组的行不该有组色点")
        assertEquals(1, grouped, "有组的行必须有一枚带组名语义的色点")
    }

    @Test
    fun thePreviewLineIsGoneUnderQ2b() {
        val grouped = textsOf(row(groupId = "g-1"), metaGroups = listOf(PROJECT))
        val ungrouped = textsOf(row(groupId = null))
        assertTrue("预览一行" !in grouped, "Q2-B：预览行必须从条目上消失")
        assertTrue("项目" !in grouped, "组名不再以文字出现（移进了色点语义）")
        assertEquals(ungrouped, grouped, "分组只加一枚色点（语义），不多一行文字")
    }

    @Test
    fun unknownGroupRendersTheRawIdOnTheRow() {
        // 行上不再有组名文字；裸 id 走色点语义（诚实退化不变，只是换了通道）。
        assertEquals(0, dotCount("分组：项目", row(groupId = "g-404")))
        assertEquals(1, dotCount("分组：g-404", row(groupId = "g-404"), metaGroups = emptyList()))
    }

    @Test
    fun archivedAndGroupedRowShowsBothWithoutLosingTheTitle() {
        val summary = row(archived = true, groupId = "g-1")
        assertEquals(1, archivedBadgeCount(summary), "标记仍在")
        assertEquals(1, dotCount("分组：项目", summary, metaGroups = listOf(PROJECT)), "组色点仍在")
        assertTrue("会话标题" in textsOf(summary, metaGroups = listOf(PROJECT)), "标题一个字都不许被挤掉")
        assertTrue("预览一行" !in textsOf(summary), "Q2-B：预览行必须消失")
    }

    // ------------------------------------------------------------ harness

    private fun row(
        id: String = "s-1",
        title: String = "会话标题",
        preview: String = "预览一行",
        archived: Boolean = false,
        groupId: String? = null,
    ): SessionSummary = SessionSummary(
        id = id,
        title = title,
        preview = preview,
        bucket = TimeBucket.Today,
        updatedAt = 1_700_000_000_000L,
        updatedLabel = "刚刚",
        archived = archived,
        groupId = groupId,
    )

    /** 整棵语义树里的文本，按遍历顺序。用来做「只多了 X」这种精确比较。 */
    private fun textsOf(session: SessionSummary, metaGroups: List<MetaGroup> = emptyList()): List<String> {
        var texts: List<String> = emptyList()
        runComposeUiTest {
            setContent { SessionsScreenFor(session, metaGroups) }
            waitForIdle()
            texts = collectTexts()
        }
        return texts
    }

    /**
     * Q2-B：组名/裸 id 走**色点的读屏语义**（`分组：<名>`）。色点在行内是合并子节点，
     * 它的 contentDescription 会并入行节点的合并语义，所以这里数语义树上的匹配数即可。
     */
    private fun dotCount(
        description: String,
        session: SessionSummary,
        metaGroups: List<MetaGroup> = emptyList(),
    ): Int {
        var count = -1
        runComposeUiTest {
            setContent { SessionsScreenFor(session, metaGroups) }
            waitForIdle()
            count = onAllNodesWithContentDescription(description).fetchSemanticsNodes().size
        }
        return count
    }

    private fun archivedBadgeCount(session: SessionSummary): Int {
        var count = -1
        runComposeUiTest {
            setContent { SessionsScreenFor(session, emptyList()) }
            waitForIdle()
            count = onAllNodesWithContentDescription(ARCHIVED_BADGE_LABEL).fetchSemanticsNodes().size
        }
        return count
    }

    @androidx.compose.runtime.Composable
    private fun SessionsScreenFor(session: SessionSummary, metaGroups: List<MetaGroup>) {
        CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
            SessionsScreen(
                groups = sessionGroups(listOf(session)),
                selectedId = null,
                twoPane = false,
                onOpen = {},
                metaGroups = metaGroups,
            )
        }
    }

    private fun ComposeUiTest.collectTexts(): List<String> {
        val out = mutableListOf<String>()

        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            node.children.forEach { child -> walk(child) }
        }

        walk(onRoot().fetchSemanticsNode())
        return out
    }

    private companion object {
        val PROJECT = MetaGroup(id = "g-1", name = "项目", iconName = "briefcase", colorHex = "#3B82F6")
        val INSPIRATION = MetaGroup(id = "g-2", name = "灵感", iconName = "bulb", colorHex = "#8B5CF6")
    }
}
