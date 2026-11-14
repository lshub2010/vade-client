package vade.ui.module

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 4 个布局原语 + 文本叶各自能解析成类型化模型（渲染器对 ResolvedNode 是 1:1 机械映射，
 * 解析成功 + 模型字段正确 = 会按该原语渲出）；组件树结构规则（Card 独占 children）同验。
 */
class PrimitiveParseTest {

    private val json = Json

    private fun parse(tree: String): ParseResult =
        ModulePageParser.parse(json.parseToJsonElement(tree), setOf("p1"))

    // --- ① column --------------------------------------------------------------

    @Test
    fun columnParsesWithGapAndChildren() {
        val result = parse(
            """{"type":"column","props":{"gap":"m"},"children":[{"type":"text","props":{"text":"hi"}}]}""",
        )
        val ok = assertIs<ParseResult.Ok>(result)
        val root = assertIs<PageNode.Column>(ok.root)
        assertEquals(PageGap.M, root.gap)
        assertEquals(1, root.children.size)
    }

    // --- ② row -----------------------------------------------------------------

    @Test
    fun rowParsesWithAlignAndWrap() {
        val result = parse(
            """{"type":"row","props":{"gap":"s","align":"center","wrap":true},"children":[{"type":"text","props":{"text":"a"}},{"type":"text","props":{"text":"b"}}]}""",
        )
        val ok = assertIs<ParseResult.Ok>(result)
        val root = assertIs<PageNode.Row>(ok.root)
        assertEquals(PageGap.S, root.gap)
        assertEquals(RowAlign.Center, root.align)
        assertEquals(true, root.wrap)
        assertEquals(2, root.children.size)
    }

    @Test
    fun rowWithBadAlignIsRejected() {
        val result = parse("""{"type":"row","props":{"align":"space-between"},"children":[]}""")
        assertIs<ParseResult.Rejected>(result)
    }

    // --- ③ tabs ----------------------------------------------------------------

    @Test
    fun tabsParseWithTabTitlesAndSelected() {
        val result = parse(
            """{"type":"tabs","props":{"selected":1},"children":[""" +
                """{"type":"column","props":{"gap":"none","tab_title":"趋势"},"children":[]},""" +
                """{"type":"column","props":{"gap":"none","tab_title":"明细"},"children":[]}]}""",
        )
        val ok = assertIs<ParseResult.Ok>(result)
        val root = assertIs<PageNode.Tabs>(ok.root)
        assertEquals(listOf("趋势", "明细"), root.tabs.map { it.title })
    }

    @Test
    fun tabsChildWithoutTabTitleIsRejected() {
        val result = parse(
            """{"type":"tabs","children":[{"type":"column","props":{},"children":[]}]}""",
        )
        val rejected = assertIs<ParseResult.Rejected>(result)
        assertTrue(rejected.reasons.any { it.contains("tab_title") }, "实际: ${rejected.reasons}")
    }

    // --- ④ list ----------------------------------------------------------------

    @Test
    fun listParsesWithSingleTemplateChild() {
        val result = parse(
            """{"type":"list","data_ref":"page.state.items","props":{"empty_text":"队列为空"},"children":[{"type":"component","component":"ListItem","props":{"title":{"bind":"item.name"}}}]}""",
        )
        val ok = assertIs<ParseResult.Ok>(result)
        val root = assertIs<PageNode.ListBlock>(ok.root)
        assertEquals("page.state.items", root.dataRef.raw)
        assertEquals("队列为空", root.emptyText)
        assertIs<PageNode.ComponentNode>(root.template)
    }

    @Test
    fun listWithZeroTemplateChildrenIsRejected() {
        val result = parse("""{"type":"list","data_ref":"page.state.items","children":[]}""")
        val rejected = assertIs<ParseResult.Rejected>(result)
        assertTrue(rejected.reasons.any { it.contains("恰好 1 个") }, "实际: ${rejected.reasons}")
    }

    // --- ⑤ text 叶 -------------------------------------------------------------

    @Test
    fun textLeafParsesWithStyleMaxLinesAndFormat() {
        val result = parse(
            """{"type":"text","data_ref":"page.state.queue_len","props":{"style":"title","max_lines":2,"format":"int"}}""",
        )
        val ok = assertIs<ParseResult.Ok>(result)
        val root = assertIs<PageNode.TextLeaf>(ok.root)
        assertEquals(PageTextStyle.Title, root.style)
        assertEquals(2, root.maxLines)
        assertEquals(PageTextFormat.Int, root.format)
    }

    @Test
    fun textLeafWithLiteralTextParses() {
        val result = parse("""{"type":"text","props":{"text":"在线"}}""")
        val ok = assertIs<ParseResult.Ok>(result)
        val root = assertIs<PageNode.TextLeaf>(ok.root)
        assertEquals("在线", (assertIs<PropValue.Literal>(requireNotNull(root.content)).value))
    }

    @Test
    fun textLeafWithNeitherDataRefNorLiteralIsRejected() {
        val result = parse("""{"type":"text","props":{"style":"body"}}""")
        assertIs<ParseResult.Rejected>(result)
    }

    // --- 组件结构规则 ---------------------------------------------------------------

    @Test
    fun cardMayCarryChildren() {
        val result = parse(
            """{"type":"component","component":"Card","props":{"title":"并发"},"children":[{"type":"text","props":{"text":"hi"}}]}""",
        )
        val ok = assertIs<ParseResult.Ok>(result)
        val root = assertIs<PageNode.ComponentNode>(ok.root)
        assertEquals(ComponentKind.Card, root.kind)
        assertEquals(1, root.children.size)
    }

    @Test
    fun buttonMayNotCarryChildren() {
        val result = parse(
            """{"type":"component","component":"Button","props":{"text":"go"},"children":[{"type":"text","props":{"text":"hi"}}]}""",
        )
        val rejected = assertIs<ParseResult.Rejected>(result)
        assertTrue(rejected.reasons.any { it.contains("children") }, "实际: ${rejected.reasons}")
    }

    @Test
    fun textLeafMayNotCarryChildren() {
        val result = parse(
            """{"type":"text","props":{"text":"hi"},"children":[]}""",
        )
        assertIs<ParseResult.Rejected>(result)
    }

    // --- 白名单 7 组件逐个可解析（含 props 枚举） --------------------------------------

    @Test
    fun allSevenWhitelistComponentsParse() {
        val cases = mapOf(
            "Button" to """"text":"保存","style":"primary"""",
            "TextField" to """"label":"上限","value":{"input":"limit"}""",
            "Card" to """"title":"并发","subtitle":"说明"""",
            "ListItem" to """"title":"任务","leading_icon":"list"""",
            "Switch" to """"label":"自动开始"""",
            "ToolCallCard" to """"tool":"charts.query","state":"ok","args_preview":"range=7d"""",
            "AttachmentChip" to """"file_id":"f1","mime":"image/png","name":"图.png","state":"uploaded"""",
        )
        for ((name, props) in cases) {
            val result = parse(
                """{"type":"component","component":"$name","props":{$props}}""",
            )
            val ok = assertIs<ParseResult.Ok>(result, "$name 必须可解析")
            val root = assertIs<PageNode.ComponentNode>(ok.root)
            assertEquals(name, root.kind.wireName)
        }
    }
}
