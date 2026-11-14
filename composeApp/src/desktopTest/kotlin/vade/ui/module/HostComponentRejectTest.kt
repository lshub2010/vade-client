package vade.ui.module

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * C-19 §4.5 白名单校验的穷举验收：4 个宿主组件（MessageBubble / InputBar /
 * ApprovalDialog / BottomBar）逐个出现 → **整页拒渲**（不是忽略），错误信息点名组件；
 * 未知组件名与未知节点类型同样 fail-closed（§7.2：不降级占位、不做部分渲染）。
 */
class HostComponentRejectTest {

    private val json = Json

    private fun parse(tree: String, declared: Set<String> = setOf("p1")): ParseResult =
        ModulePageParser.parse(json.parseToJsonElement(tree), declared)

    private fun componentTree(componentName: String): String =
        """{"type":"component","component":"$componentName","props":{}}"""

    private fun assertRejectedByHostComponent(name: String) {
        val result = parse(componentTree(name))
        val rejected = assertIs<ParseResult.Rejected>(result, "$name 必须整页拒渲")
        assertTrue(
            rejected.reasons.any { it.contains(name) && it.contains("宿主组件") },
            "错误定位必须点名宿主组件 $name，实际: ${rejected.reasons}",
        )
    }

    @Test
    fun messageBubbleIsRejected() = assertRejectedByHostComponent("MessageBubble")

    @Test
    fun inputBarIsRejected() = assertRejectedByHostComponent("InputBar")

    @Test
    fun approvalDialogIsRejected() = assertRejectedByHostComponent("ApprovalDialog")

    @Test
    fun bottomBarIsRejected() = assertRejectedByHostComponent("BottomBar")

    @Test
    fun unknownComponentIsRejected() {
        val result = parse(componentTree("Foo"))
        val rejected = assertIs<ParseResult.Rejected>(result, "白名单外组件 Foo 必须拒渲")
        assertTrue(
            rejected.reasons.any { it.contains("未知组件") && it.contains("Foo") },
            "实际: ${rejected.reasons}",
        )
    }

    @Test
    fun unknownNodeTypesAreRejected() {
        // §7.2 例举的四种未知 type；webview/iframe 也在内——模块页没有 WebView 这条
        // 红线在数据层同样 fail-closed。
        for (type in listOf("grid", "stack", "iframe", "webview")) {
            val result = parse("""{"type":"$type","props":{}}""")
            val rejected = assertIs<ParseResult.Rejected>(result, "未知 type \"$type\" 必须拒渲")
            assertTrue(
                rejected.reasons.any { it.contains("未知节点类型") },
                "实际: ${rejected.reasons}",
            )
        }
    }

    @Test
    fun rejectReasonsFeedTheErrorCardWithAtMostThreeLocations() {
        // §7.2：错误卡最多 3 条定位——这里构造一个多问题的树，确认 reasons 有内容可取。
        val result = parse(
            """{"type":"column","props":{"gap":"m"},"children":""" +
                """[{"type":"component","component":"MessageBubble","props":{}},""" +
                """{"type":"component","component":"InputBar","props":{}}]}""",
        )
        val rejected = assertIs<ParseResult.Rejected>(result)
        assertTrue(rejected.reasons.isNotEmpty(), "至少一条定位")
    }
}
