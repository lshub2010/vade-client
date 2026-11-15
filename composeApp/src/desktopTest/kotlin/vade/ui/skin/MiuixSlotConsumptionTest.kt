package vade.ui.skin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import vade.ui.chat.MarkdownText
import vade.ui.skin.material.MaterialSkin
import vade.ui.skin.miuix.MiuixSkin

/**
 * C-09 **槽位契约** 的回归测试 —— `MessageBubble.body` 与 `ToolCallCard.meta`。
 *
 * 这两个槽是冻结后被追加的（见 `Skin.kt` 的注释），语义只有一句：**页面给内容，皮肤给框**。
 * 皮肤可以换 padding、行高、圆角、颜色，但**不能丢槽** —— 一旦丢，同一份数据会在这一套皮肤
 * 上凭空消失，而在另一套上正常（`shots/g-visual-audit2/RESULT.md` · D3/D4 就是这么被发现的：
 * 同一会话切回 material 立刻恢复成 Markdown）。
 *
 * 这里刻意测「屏上」而不是「源码里有没有这个词」：
 *  - D3：含 `**粗体**` 与三反引号代码块的消息，miuix 下**不得出现**字面 `**` / 三个反引号；
 *  - D4：miuix 工具卡必须画出页面交进来的四行 meta（各自关键词）与 `ARGS` / `RESULT` 两个标签；
 *  - 对拍：同一份正文 / 同一份 meta，在 material 与 miuix 下屏上的文本集合必须一致 ——
 *    有人删掉 `body()` 或 `meta()` 的调用，这一条先红；
 *  - 反向：`body = null` / `meta = null` 时退回冻结行为，槽位是「覆盖」不是「替换」。
 */
@OptIn(ExperimentalTestApi::class)
class MiuixSlotConsumptionTest {

    // --- fixtures ---------------------------------------------------------------

    /**
     * A reply of the shape the audit screenshotted: one bold run and one fenced kotlin block.
     * Built by concatenation because a Kotlin raw string cannot contain a literal fence.
     */
    private val markdown =
        "原图已压缩，**原图未保留**。\n" +
            "\n" +
            "```kotlin\n" +
            "val task = tasks.findByName(\"assembleDebug\")\n" +
            "if (task != null) println(\"UP-TO-DATE\")\n" +
            "```\n"

    /** What `ChatThread.ToolRow` hands to the `meta` slot (`Models.toolMetaRows`). */
    private val metaRows = listOf(
        "执行耗时" to "1.24 s",
        "批次" to "b-7 · 同批 3 个",
        "错误码" to "E_TIMEOUT",
        "发起于" to "12:04:31",
    )

    private val call = ToolCallView(
        id = "t1",
        name = "shell.exec",
        args = "{\"cmd\": \"gradle :composeApp:desktopTest\"}",
        result = "exit 1 · 3 tests failed",
        risk = "high",
        isError = true,
    )

    private val message = MessageView(id = "m1", text = markdown)

    @Composable
    private fun MetaRows() {
        val skin = LocalSkin.current
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            metaRows.forEach { (label, value) ->
                BasicText(
                    text = "$label · $value",
                    style = skin.typography.caption.copy(color = skin.colors.onSurfaceVariant),
                )
            }
        }
    }

    // --- harness ----------------------------------------------------------------

    /** One bubble of [skin], rendered with the page's markdown renderer in the `body` slot. */
    @Composable
    private fun TheBubble(skin: Skin, role: BubbleRole, withBody: Boolean) {
        CompositionLocalProvider(LocalSkin provides skin) {
            Box(Modifier.width(520.dp)) {
                skin.MessageBubble(
                    message = message,
                    role = role,
                    maxWidth = 760.dp,
                    body = if (withBody) {
                        { MarkdownText(source = markdown) }
                    } else {
                        null
                    },
                )
            }
        }
    }

    /** One expanded tool card of [skin], with the page's four meta rows in the `meta` slot. */
    @Composable
    private fun TheToolCard(skin: Skin, withMeta: Boolean) {
        CompositionLocalProvider(LocalSkin provides skin) {
            Box(Modifier.width(520.dp)) {
                skin.ToolCallCard(
                    call = call,
                    state = ToolCardState.Expanded,
                    onToggle = {},
                    meta = if (withMeta) {
                        { MetaRows() }
                    } else {
                        null
                    },
                )
            }
        }
    }

    /** Every `Text` on screen, in semantics-tree order. */
    private fun ComposeUiTest.screenTexts(): List<String> {
        val out = mutableListOf<String>()

        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            node.children.forEach { child -> walk(child) }
        }

        walk(onRoot().fetchSemanticsNode())
        return out
    }

    private fun bubbleTexts(skin: Skin, role: BubbleRole, withBody: Boolean = true): List<String> {
        var texts: List<String> = emptyList()
        runComposeUiTest {
            setContent { TheBubble(skin, role, withBody) }
            waitForIdle()
            texts = screenTexts()
        }
        return texts
    }

    private fun toolCardTexts(skin: Skin, withMeta: Boolean = true): List<String> {
        var texts: List<String> = emptyList()
        runComposeUiTest {
            setContent { TheToolCard(skin, withMeta) }
            waitForIdle()
            texts = screenTexts()
        }
        return texts
    }

    private fun List<String>.joinForReport(): String = joinToString("\n") { "    · $it" }

    // --- D3 · MessageBubble.body ------------------------------------------------

    @Test
    fun miuixBubbleRendersMarkdownInsteadOfLiteralMarkers() {
        val texts = bubbleTexts(MiuixSkin(), BubbleRole.Assistant)

        val literalMarkers = texts.filter { "**" in it || "```" in it }
        assertTrue(
            literalMarkers.isEmpty(),
            "miuix 把 Markdown 标记原样画上了屏（D3：`body` 槽没被消费）。命中：\n" +
                literalMarkers.joinForReport() +
                "\n整屏文本：\n" + texts.joinForReport(),
        )
        assertTrue(
            texts.any { it == "原图已压缩，原图未保留。" },
            "粗体的**内容**也没上屏——不只是标记的问题。整屏文本：\n" + texts.joinForReport(),
        )
        assertTrue(
            texts.any { it == "kotlin" },
            "代码块的语言标签没上屏（fence 被当正文吞了）。整屏文本：\n" + texts.joinForReport(),
        )
        assertTrue(
            texts.any { "findByName" in it },
            "代码块正文没上屏。整屏文本：\n" + texts.joinForReport(),
        )
    }

    @Test
    fun miuixUserAndSystemBubblesAlsoRenderTheMarkdownBody() {
        // 三个角色各走一条分支；只要有一条忘了 `body()`，这里就红。
        listOf(BubbleRole.User, BubbleRole.System, BubbleRole.Assistant).forEach { role ->
            val texts = bubbleTexts(MiuixSkin(), role)
            assertTrue(
                texts.none { "**" in it || "```" in it },
                "$role 气泡把 Markdown 标记原样画上了屏：\n" + texts.joinForReport(),
            )
            assertTrue(
                texts.any { it == "原图已压缩，原图未保留。" },
                "$role 气泡没有渲染富文本正文：\n" + texts.joinForReport(),
            )
        }
    }

    @Test
    fun bothSkinsShowTheSameTextForTheSameMessage() {
        val miuix = bubbleTexts(MiuixSkin(), BubbleRole.Assistant)
        val material = bubbleTexts(MaterialSkin(), BubbleRole.Assistant)

        assertEquals(
            material.sorted(),
            miuix.sorted(),
            "同一份正文在两套皮肤下屏上文本不一致 —— 皮肤结构可以不同，数据不许不同。\n" +
                "material：\n" + material.joinForReport() +
                "\nmiuix：\n" + miuix.joinForReport(),
        )
    }

    @Test
    fun nullableBodyKeepsTheFrozenPlainTextRendering() {
        // 槽位的语义是「覆盖」，不是「替换」：页面不传 body 时冻结行为逐字保留。
        listOf<Skin>(MiuixSkin(), MaterialSkin()).forEach { skin ->
            val texts = bubbleTexts(skin, BubbleRole.Assistant, withBody = false)
            assertTrue(
                texts.any { it == markdown },
                "${skin.id}: body = null 时必须退回 `Text(message.text)`（冻结行为）。\n" +
                    texts.joinForReport(),
            )
        }
    }

    // --- D4 · ToolCallCard.meta + ARGS / RESULT ---------------------------------

    @Test
    fun miuixToolCardDrawsAllFourMetaRows() {
        val texts = toolCardTexts(MiuixSkin())

        metaRows.forEach { (label, value) ->
            assertTrue(
                texts.any { it.contains(label) && it.contains(value) },
                "miuix 工具卡丢了 meta 的「$label」行（D4：`meta` 形参没被消费）。\n" +
                    "整屏文本：\n" + texts.joinForReport(),
            )
        }
    }

    @Test
    fun miuixToolCardDrawsArgsAndResultLabels() {
        val texts = toolCardTexts(MiuixSkin())

        listOf("ARGS", "RESULT").forEach { label ->
            assertTrue(
                texts.any { it.trim() == label },
                "miuix 工具卡缺少 $label 内嵌块标签。\n整屏文本：\n" + texts.joinForReport(),
            )
        }
        assertTrue(
            texts.any { it == call.args },
            "ARGS 块的参数正文没上屏（只剩标签）。\n整屏文本：\n" + texts.joinForReport(),
        )
        assertTrue(
            texts.any { it == call.result },
            "RESULT 块的结果正文没上屏（只剩标签）。\n整屏文本：\n" + texts.joinForReport(),
        )
    }

    @Test
    fun bothSkinsShowTheSameMetaRows() {
        val miuix = toolCardTexts(MiuixSkin())
        val material = toolCardTexts(MaterialSkin())

        metaRows.forEach { (label, _) ->
            assertTrue(
                material.any { it.contains(label) },
                "material 侧回归：meta 的「$label」行不见了。\n" + material.joinForReport(),
            )
            assertTrue(
                miuix.any { it.contains(label) },
                "miuix 侧丢了「$label」行。\n" + miuix.joinForReport(),
            )
        }
        listOf("ARGS", "RESULT").forEach { label ->
            assertTrue(
                material.any { it.trim() == label },
                "material 侧回归：$label 标签不见了。\n" + material.joinForReport(),
            )
            assertTrue(
                miuix.any { it.trim() == label },
                "miuix 侧缺 $label 标签。\n" + miuix.joinForReport(),
            )
        }
    }

    @Test
    fun nullableMetaDrawsNothingOnEitherSkin() {
        listOf<Skin>(MiuixSkin(), MaterialSkin()).forEach { skin ->
            val texts = toolCardTexts(skin, withMeta = false)
            val leaked = texts.filter { row -> metaRows.any { (label, _) -> row.contains(label) } }
            assertTrue(
                leaked.isEmpty(),
                "${skin.id}: meta = null 时不得凭空画出行。命中：\n" + leaked.joinForReport(),
            )
        }
    }

    // --- the same contract, at the source level (cheap tripwire) ----------------

    @Test
    fun miuixPartsKeepTheSlotCalls() {
        // 屏上测试是权威；这一条只是把「形参还在、调用没了」这种改法也钉住，报错信息直接
        // 说清后果。删掉调用 → 这里红；只要调用还在、换了写法 → 屏上那几条仍然绿。
        val file = java.io.File("src/commonMain/kotlin/vade/ui/skin/miuix/MiuixParts.kt")
        assertTrue(file.isFile, "MiuixParts.kt 不在预期位置：${file.absolutePath}")
        val source = file.readText()

        assertTrue(
            "SlottedOrFallback(body)" in source,
            "MiuixMessageBubble 不再消费 body 槽（C-09）——找 `SlottedOrFallback(body)`；" +
                "D3 会以字面 `**` 和 fence 的形式回到屏上",
        )
        assertTrue(
            "meta()" in source,
            "MiuixToolCallCard 不再消费 meta 槽（C-09）——D4 的耗时/批次/错误码/发起时间会消失",
        )
    }
}
