package vade.ui.module

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 只读绑定三根 + 缺失/越界行为（§5 / A7）：缺失或 null → 空值**不崩**（验收 ⑧）；
 * 越界根（如 session.*）→ 空值 + 一次性提示、不发任何 RPC；路径形式非法 → 解析期拒渲。
 */
class BindingResolveTest {

    private val json = Json

    private fun parse(tree: String): ParseResult =
        ModulePageParser.parse(json.parseToJsonElement(tree), setOf("p1"))

    private val emptyEnv = PageEnv(state = null, moduleOnline = true, moduleVersion = "1.0")

    // --- ⑧ 绑定值缺失不崩 --------------------------------------------------------

    @Test
    fun missingStateKeyYieldsEmptyTextWithoutCrash() {
        val result = parse("""{"type":"text","data_ref":"page.state.gone","props":{}}""")
        val ok = assertIs<ParseResult.Ok>(result)
        val env = emptyEnv.copy(state = json.parseToJsonElement("""{"other": 1}""").jsonObject)
        val report = PageResolveReport()
        val resolved = ModulePageResolver.resolve(ok.root, env, report)
        val text = assertIs<ResolvedNode.TextLeaf>(resolved)
        assertEquals("", text.text, "缺失 → 空值，不崩、不给假数据")
        assertTrue(report.notices.isEmpty(), "缺失是正常状态，无提示（§5.4）")
    }

    @Test
    fun nullStateValueYieldsEmpty() {
        val result = parse("""{"type":"text","data_ref":"page.state.x","props":{}}""")
        val ok = assertIs<ParseResult.Ok>(result)
        val env = emptyEnv.copy(state = json.parseToJsonElement("""{"x": null}""").jsonObject)
        val text = assertIs<ResolvedNode.TextLeaf>(ModulePageResolver.resolve(ok.root, env, PageResolveReport()))
        assertEquals("", text.text)
    }

    @Test
    fun stateAbsentEntirelyYieldsEmpty() {
        val result = parse("""{"type":"text","data_ref":"page.state.x","props":{}}""")
        val ok = assertIs<ParseResult.Ok>(result)
        val text = assertIs<ResolvedNode.TextLeaf>(ModulePageResolver.resolve(ok.root, emptyEnv, PageResolveReport()))
        assertEquals("", text.text)
    }

    // --- 越界根 -------------------------------------------------------------------

    @Test
    fun outOfRootPathYieldsEmptyPlusOneTimeNotice() {
        val report = PageResolveReport()
        val binding = requireNotNull(PageBinding.parse("session.messages"))
        assertNull(resolveBinding(binding, emptyEnv, item = null, report = report), "越界 → 空值")
        assertTrue(
            report.notices.any { it.contains("不可用") },
            "越界要有一次性提示，实际: ${report.notices}",
        )
        // 一次性：同样的越界再报一次也不重复入列。
        resolveBinding(binding, emptyEnv, item = null, report = report)
        assertEquals(1, report.notices.size)
    }

    @Test
    fun itemOutsideListTemplateYieldsNotice() {
        val result = parse("""{"type":"text","data_ref":"item.name","props":{}}""")
        val ok = assertIs<ParseResult.Ok>(result)
        val report = PageResolveReport()
        val text = assertIs<ResolvedNode.TextLeaf>(ModulePageResolver.resolve(ok.root, emptyEnv, report))
        assertEquals("", text.text)
        assertTrue(report.notices.any { it.contains("item.") }, "实际: ${report.notices}")
    }

    // --- 路径形式非法 = 结构性越界（解析期拒渲） --------------------------------------

    @Test
    fun fiveSegmentPathIsRejectedAtParse() {
        val result = parse("""{"type":"text","data_ref":"page.state.a.b.c","props":{}}""")
        val rejected = assertIs<ParseResult.Rejected>(result)
        assertTrue(rejected.reasons.any { it.contains("越界") || it.contains("非法") }, "实际: ${rejected.reasons}")
    }

    @Test
    fun uppercaseSegmentPathIsRejectedAtParse() {
        val result = parse("""{"type":"text","data_ref":"page.state.QueueLen","props":{}}""")
        assertIs<ParseResult.Rejected>(result)
    }

    // --- 三根取值与格式 -------------------------------------------------------------

    @Test
    fun moduleStatusOnlineResolvesAsBoolean() {
        val binding = requireNotNull(PageBinding.parse("module.status.online"))
        val value = resolveBinding(binding, emptyEnv.copy(moduleOnline = false), null, null)
        assertEquals(false, (value as JsonPrimitive).boolean)
    }

    @Test
    fun intAndPercentFormatsFormatNumbers() {
        assertEquals("42", PageFormatter.format(JsonPrimitive(42), PageTextFormat.Int))
        assertEquals("40%", PageFormatter.format(JsonPrimitive(0.4), PageTextFormat.Percent))
        // bytes 按 1024 进位（二进制约定）：734003200 B = 700 MiB。
        assertEquals("700 MB", PageFormatter.format(JsonPrimitive(734_003_200), PageTextFormat.Bytes))
    }

    @Test
    fun listResolvesItemsIntoRowsWithItemScope() {
        val result = parse(
            """{"type":"list","data_ref":"page.state.items","props":{},"children":[{"type":"component","component":"ListItem","props":{"title":{"bind":"item.name"},"subtitle":{"bind":"item.progress","format":"percent"}}}]}""",
        )
        val ok = assertIs<ParseResult.Ok>(result)
        val env = emptyEnv.copy(
            state = json.parseToJsonElement(
                """{"items":[{"name":"A","progress":0.4},{"name":"B","progress":0.75}]}""",
            ).jsonObject,
        )
        val resolved = assertIs<ResolvedNode.ListBlock>(ModulePageResolver.resolve(ok.root, env, PageResolveReport()))
        assertEquals(2, resolved.rows.size)
        val row = assertIs<ResolvedNode.Component>(resolved.rows[0])
        val item = assertIs<ResolvedComponent.ListItem>(row.payload)
        assertEquals("A", item.title)
        assertEquals("40%", item.subtitle)
    }

    @Test
    fun listOutsideItemFieldsWhitelistIsTreatedAsMissing() {
        val result = parse(
            """{"type":"list","data_ref":"page.state.items","props":{"item_fields":["name"]},"children":[{"type":"component","component":"ListItem","props":{"title":{"bind":"item.name"},"subtitle":{"bind":"item.progress"}}}]}""",
        )
        val ok = assertIs<ParseResult.Ok>(result)
        val env = emptyEnv.copy(
            state = json.parseToJsonElement("""{"items":[{"name":"A","progress":0.4}]}""").jsonObject,
        )
        val report = PageResolveReport()
        val resolved = assertIs<ResolvedNode.ListBlock>(ModulePageResolver.resolve(ok.root, env, report))
        val item = assertIs<ResolvedComponent.ListItem>(assertIs<ResolvedNode.Component>(resolved.rows[0]).payload)
        assertEquals("A", item.title)
        assertNull(item.subtitle, "白名单外字段按缺失处理")
        assertTrue(report.notices.any { it.contains("item_fields") }, "实际: ${report.notices}")
    }

    @Test
    fun visibleBoundFalsyDropsSubtree() {
        val result = parse(
            """{"type":"column","props":{},"children":[""" +
                """{"type":"text","props":{"text":"在线"},"visible":{"bind":"module.status.online"}},""" +
                """{"type":"text","props":{"text":"总是"}}]}""",
        )
        val ok = assertIs<ParseResult.Ok>(result)
        val env = emptyEnv.copy(moduleOnline = false)
        val resolved = assertIs<ResolvedNode.Column>(ModulePageResolver.resolve(ok.root, env, PageResolveReport()))
        assertEquals(1, resolved.children.size, "visible=false 的子树整体消失（只做真值判定）")
    }
}
