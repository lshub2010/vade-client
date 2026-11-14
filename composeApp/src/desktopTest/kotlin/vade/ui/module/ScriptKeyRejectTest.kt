package vade.ui.module

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * §7.2 第一条拒渲规则：**任何脚本键（大小写不敏感、任意层级）→ 整页拒渲 + 无任何代码执行**。
 * 脚本键词表：script / on_load / onclick / eval / js / html / innerHTML / expression /
 * lambda / template 含 ${}。这是「没有求值器就没有 eval」的可执行部分。
 */
class ScriptKeyRejectTest {

    private val json = Json

    private fun parse(tree: String): ParseResult =
        ModulePageParser.parse(json.parseToJsonElement(tree), setOf("p1"))

    private fun assertScriptRejected(tree: String, label: String) {
        val result = parse(tree)
        val rejected = assertIs<ParseResult.Rejected>(result, "$label 必须整页拒渲")
        assertTrue(
            rejected.reasons.any { it.contains("脚本键") || it.contains("模板插值") },
            "错误定位必须指出脚本键，实际: ${rejected.reasons}",
        )
    }

    @Test
    fun scriptKeyOnNodeIsRejected() = assertScriptRejected(
        """{"type":"column","props":{},"script":"alert(1)","children":[]}""",
        "节点级 script",
    )

    @Test
    fun onclickInPropsIsRejected() = assertScriptRejected(
        """{"type":"component","component":"Button","props":{"text":"x","onclick":"alert(1)"}}""",
        "props.onclick",
    )

    @Test
    fun onLoadDeepInTreeIsRejected() {
        // on_load 埋在最内层 Card 的 props 里（任意层级命中即拒渲）。
        val tree = """{"type":"column","props":{},"children":[""" +
            """{"type":"component","component":"Card","props":{"on_load":"alert(1)"},"children":[""" +
            """{"type":"text","props":{"text":"hi"}}]}]}"""
        assertScriptRejected(tree, "深层 on_load")
    }

    @Test
    fun caseInsensitiveScriptKeyIsRejected() = assertScriptRejected(
        """{"type":"text","props":{"text":"hi","SCRIPT":"alert(1)"}}""",
        "大写 SCRIPT（大小写不敏感）",
    )

    @Test
    fun templateInterpolationIsRejected() = assertScriptRejected(
        // Kotlin 三引号里 ${'$'} 表示字面量 "${"，避免被当成 Kotlin 模板。
        """{"type":"text","props":{"text":"hi","template":"a${'$'}{secret}b"}}""",
        "template 含 \${} 插值",
    )

    @Test
    fun evalInActionArgsIsRejected() = assertScriptRejected(
        """{"type":"component","component":"Button","props":{"text":"go"},""" +
            """"on":{"click":{"kind":"tools/call","tool":"t","args":{"payload":"x","eval":"1+1"}}}}""",
        "动作 args 里的 eval",
    )
}
