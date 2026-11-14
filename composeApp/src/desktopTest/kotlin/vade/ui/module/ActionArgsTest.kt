package vade.ui.module

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 动作（§6 / §6.4）：kind 只有三类、未知 kind → 整页拒渲；navigate 到未声明页 → 拒渲；
 * 参数来源只有字面量 / bind / input 三种（其他来源键解析期拒）；bind 解析不到 → 空串；
 * $input 严格整数，失败 → 动作不发出；AttachmentChip 的 click 只能 navigate（Q4）。
 */
class ActionArgsTest {

    private val json = Json

    private fun parse(tree: String): ParseResult =
        ModulePageParser.parse(json.parseToJsonElement(tree), setOf("p1"))

    private val emptyEnv = PageEnv(state = null, moduleOnline = true, moduleVersion = "1.0")

    @Test
    fun unknownActionKindIsRejected() {
        val result = parse(
            """{"type":"component","component":"Button","props":{"text":"go"},""" +
                """"on":{"click":{"kind":"http.request","url":"https://example.com"}}}""",
        )
        val rejected = assertIs<ParseResult.Rejected>(result)
        assertTrue(rejected.reasons.any { it.contains("未知动作") }, "实际: ${rejected.reasons}")
    }

    @Test
    fun navigateToUndeclaredPageIsRejected() {
        val result = parse(
            """{"type":"component","component":"Button","props":{"text":"go"},""" +
                """"on":{"click":{"kind":"navigate","to":"module:other"}}}""",
        )
        val rejected = assertIs<ParseResult.Rejected>(result)
        assertTrue(rejected.reasons.any { it.contains("未声明") }, "实际: ${rejected.reasons}")
    }

    @Test
    fun navigateToDeclaredPageParses() {
        val result = parse(
            """{"type":"component","component":"Button","props":{"text":"go"},""" +
                """"on":{"click":{"kind":"navigate","to":"module:p1"}}}""",
        )
        val ok = assertIs<ParseResult.Ok>(result)
        val root = assertIs<PageNode.ComponentNode>(ok.root)
        val action = requireNotNull(root.events["click"])
        val navigate = assertIs<PageAction.Navigate>(action)
        assertEquals("p1", (navigate.target as NavigateTarget.ModulePage).pageId)
    }

    @Test
    fun navigateSettingsTargetOutsideFixedEnumIsRejected() {
        val result = parse(
            """{"type":"component","component":"Button","props":{"text":"go"},""" +
                """"on":{"click":{"kind":"navigate","to":"settings:network"}}}""",
        )
        assertIs<ParseResult.Rejected>(result, "settings 目标枚举固定：appearance/models/devices/about")
    }

    @Test
    fun literalArgsPassThrough() {
        val args = json.parseToJsonElement("""{"all": true, "count": 3, "note": "x"}""").jsonObject
        val report = PageResolveReport()
        val resolved = requireNotNull(ModulePageResolver.resolveArgs(args, emptyEnv, report))
        assertEquals(true, resolved["all"]!!.jsonPrimitive.boolean)
        assertEquals(3L, resolved["count"]!!.jsonPrimitive.long)
        assertEquals("x", resolved["note"]!!.jsonPrimitive.content)
    }

    @Test
    fun boundArgMissingResolvesToEmptyString() {
        val args = json.parseToJsonElement("""{"q": {"bind": "page.state.gone"}}""").jsonObject
        val resolved = requireNotNull(ModulePageResolver.resolveArgs(args, emptyEnv, PageResolveReport()))
        assertEquals("", resolved["q"]!!.jsonPrimitive.content, "解析不到 → 空串（core 按 input_schema 复校）")
    }

    @Test
    fun inputAsIntStrictParse() {
        val args = json.parseToJsonElement("""{"n": {"input": "n", "as": "int"}}""").jsonObject
        val okEnv = emptyEnv.copy(inputs = mapOf("n" to "12"))
        val resolved = requireNotNull(ModulePageResolver.resolveArgs(args, okEnv, PageResolveReport()))
        assertEquals(12L, resolved["n"]!!.jsonPrimitive.long)

        val badEnv = emptyEnv.copy(inputs = mapOf("n" to "1x"))
        val report = PageResolveReport()
        assertNull(ModulePageResolver.resolveArgs(args, badEnv, report), "严格解析失败 → 动作不发出")
        assertTrue(report.notices.any { it.contains("整数") }, "就地提示，实际: ${report.notices}")
    }

    @Test
    fun argWithNestedObjectSourceIsRejectedAtParse() {
        // {"bind": "$env"} 之类的来源键 → 拒渲（§6.4）。
        val result = parse(
            """{"type":"component","component":"Button","props":{"text":"go"},""" +
                """"on":{"click":{"kind":"tools/call","tool":"t","args":{"q":{"bind":"${'$'}env"}}}}}""",
        )
        assertIs<ParseResult.Rejected>(result, "\"${'$'}env\" 不是合法绑定路径 → 来源键非法")
    }

    @Test
    fun attachmentChipClickMustBeNavigateOnly() {
        val rejected = parse(
            """{"type":"component","component":"AttachmentChip","props":{"file_id":"f","mime":"image/png","name":"n","state":"uploaded"},"on":{"click":{"kind":"tools/call","tool":"t","args":{}}}}""",
        )
        assertIs<ParseResult.Rejected>(rejected, "Q4：AttachmentChip 只读，click 只能 navigate")
    }

    @Test
    fun submitPartsWithNonTextTypeAreRejected() {
        val result = parse(
            """{"type":"component","component":"Button","props":{"text":"go"},""" +
                """"on":{"click":{"kind":"ui/submit","content":[{"type":"file","file_id":"f"}]}}}""",
        )
        val rejected = assertIs<ParseResult.Rejected>(result)
        assertTrue(rejected.reasons.any { it.contains("text") }, "part 只允许 type:text，实际: ${rejected.reasons}")
    }

    @Test
    fun submitTextsResolveLiteralBindAndInput() {
        val action = PageAction.UiSubmit(
            parts = listOf(
                SubmitPart(SubmitText.Literal("（来自模块页面）")),
                SubmitPart(SubmitText.Bound(requireNotNull(PageBinding.parse("page.state.note")))),
                SubmitPart(SubmitText.InputRef("q")),
            ),
        )
        val env = emptyEnv.copy(
            state = json.parseToJsonElement("""{"note": "备注"}""").jsonObject,
            inputs = mapOf("q" to "搜索词"),
        )
        val texts = ModulePageResolver.resolveSubmitTexts(action.parts, env, PageResolveReport())
        assertEquals(listOf("（来自模块页面）", "备注", "搜索词"), texts)
    }
}
