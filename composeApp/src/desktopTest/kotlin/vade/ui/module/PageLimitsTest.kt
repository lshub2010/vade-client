package vade.ui.module

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * §7.3 限额（A8）：深度 9 / 节点 257 / 单节点 children 33 / text 4097 → 拒渲；
 * 恰好 8 / 256 / 32 / 4096 → 正常解析（两侧都验）。超限不做截断后渲染——截断会把
 * 校验失败伪装成正常页面。
 */
class PageLimitsTest {

    private val json = Json

    private fun parse(tree: String): ParseResult =
        ModulePageParser.parse(json.parseToJsonElement(tree), setOf("p1"))

    /** depth 层嵌套 column（链式），叶子是 text：根 = 深度 1。 */
    private fun nestedColumns(depth: Int): String {
        if (depth == 1) return """{"type":"text","props":{"text":"leaf"}}"""
        return """{"type":"column","props":{"gap":"none"},"children":[${nestedColumns(depth - 1)}]}"""
    }

    /** 1 + k 个子 column（各带 m 个 text 叶）的树：总节点 = 1 + k*(1+m)，深度 3。 */
    private fun wideTree(k: Int, m: Int): String {
        val column = { """{"type":"column","props":{"gap":"none"},"children":[""" +
            (1..m).joinToString(",") { """{"type":"text","props":{"text":"t$it"}}""" } + """]}""" }
        return """{"type":"column","props":{"gap":"none"},"children":[""" +
            (1..k).joinToString(",") { column() } + """]}"""
    }

    // --- 深度 -------------------------------------------------------------------

    @Test
    fun depthEightIsAccepted() {
        val result = parse(nestedColumns(8))
        assertIs<ParseResult.Ok>(result, "恰好 8 层必须正常渲染")
    }

    @Test
    fun depthNineIsRejected() {
        val result = parse(nestedColumns(9))
        val rejected = assertIs<ParseResult.Rejected>(result, "第 9 层必须拒渲")
        assertTrue(rejected.reasons.any { it.contains("深度") }, "实际: ${rejected.reasons}")
    }

    // --- 节点数 -------------------------------------------------------------------

    @Test
    fun nodeCount256IsAccepted() {
        // 1 + 15*(1+16) = 256；深度 3、每容器 ≤32 children，全部满足。
        val result = parse(wideTree(k = 15, m = 16))
        val ok = assertIs<ParseResult.Ok>(result)
        assertTrue(ok.nodeCount == 256, "构造的树必须恰好 256 节点，实际 ${ok.nodeCount}")
    }

    @Test
    fun nodeCount257IsRejected() {
        // 1 + 16*(1+15) = 257；超出即拒渲。
        val result = parse(wideTree(k = 16, m = 15))
        val rejected = assertIs<ParseResult.Rejected>(result)
        assertTrue(rejected.reasons.any { it.contains("256") || it.contains("节点数") }, "实际: ${rejected.reasons}")
    }

    // --- 其他限额 -----------------------------------------------------------------

    @Test
    fun children33IsRejected() {
        val children = (1..33).joinToString(",") { """{"type":"text","props":{"text":"t$it"}}""" }
        val result = parse("""{"type":"column","props":{},"children":[$children]}""")
        val rejected = assertIs<ParseResult.Rejected>(result)
        assertTrue(rejected.reasons.any { it.contains("children") }, "实际: ${rejected.reasons}")
    }

    @Test
    fun text4096IsAcceptedAnd4097IsRejected() {
        val ok = parse("""{"type":"text","props":{"text":"${"x".repeat(4096)}"}}""")
        assertIs<ParseResult.Ok>(ok, "恰好 4096 字符要能渲染")

        val over = parse("""{"type":"text","props":{"text":"${"x".repeat(4097)}"}}""")
        val rejected = assertIs<ParseResult.Rejected>(over)
        assertTrue(rejected.reasons.any { it.contains("4096") }, "实际: ${rejected.reasons}")
    }

    @Test
    fun nonObjectRootIsRejected() {
        val result = parse("""[{"type":"text","props":{"text":"x"}}]""")
        assertIs<ParseResult.Rejected>(result, "根不是对象 → 拒渲")
    }
}
