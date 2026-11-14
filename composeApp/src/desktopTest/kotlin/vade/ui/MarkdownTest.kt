package vade.ui

import vade.ui.chat.Markdown
import vade.ui.chat.MdBlock
import vade.ui.chat.MdRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * U1-b · the markdown subset, asserted on the **parser** (no composition, no pixels).
 *
 * The half of the contract that matters for a streaming client is the last three tests: a reply
 * spends most of its life mid-`**` and mid-fence, and none of those states may throw, drop text
 * or swallow the message.
 */
class MarkdownTest {

    private fun runs(text: String): List<MdRun> = (Markdown.parse(text).single() as MdBlock.Paragraph).runs

    private fun texts(text: String): List<String> = runs(text).map { it.text }

    @Test
    fun boldItalicAndInlineCodeBecomeRuns() {
        val parsed = runs("**粗**与*斜*还有 `code`")
        assertEquals(listOf("粗", "与", "斜", "还有 ", "code"), parsed.map { it.text })
        assertTrue(parsed[0].bold, "**x** must be bold")
        assertTrue(parsed[2].italic, "*x* must be italic")
        assertTrue(parsed[4].code, "`x` must be inline code")
        // The style markers themselves never reach the text.
        assertFalse(parsed[0].text.contains("*"))
    }

    @Test
    fun nestedBoldItalicKeepsBothFlags() {
        val parsed = runs("**粗体里有 *斜* 一字**")
        val inner = parsed.first { it.text == "斜" }
        assertTrue(inner.bold && inner.italic, "nested emphasis keeps both flags: $inner")
    }

    @Test
    fun underscoreInsideAnIdentifierIsNotItalic() {
        // `args_preview` / `file_id` are RFC field names that appear in replies verbatim; a
        // naive `_…_` rule turns them italic and eats the underscore.
        val parsed = runs("字段 args_preview 与 file_id 都不能变斜体")
        assertEquals("字段 args_preview 与 file_id 都不能变斜体", parsed.joinToString("") { it.text })
        assertTrue(parsed.none { it.italic }, "no run may be italic: $parsed")
    }

    @Test
    fun linkCarriesItsUrlAndKeepsTheLabel() {
        val parsed = runs("见 [RFC-0004 §6.1](https://example.invalid/rfc#61) 的 parts 形状")
        val link = parsed.single { it.link != null }
        assertEquals("RFC-0004 §6.1", link.text)
        assertEquals("https://example.invalid/rfc#61", link.link)
    }

    @Test
    fun fencedCodeKeepsLanguageAndVerbatimBody() {
        val blocks = Markdown.parse(
            """
            before

            ```kotlin
            val x = "**not bold**"
            run(x)
            ```
            """.trimIndent(),
        )
        assertEquals(2, blocks.size, "paragraph + fenced block: $blocks")
        val code = assertIs<MdBlock.Code>(blocks[1])
        assertEquals("kotlin", code.lang)
        assertEquals("val x = \"**not bold**\"\nrun(x)", code.code)
        // Markers inside a fence are *not* parsed.
        assertTrue(code.code.contains("**not bold**"))
    }

    @Test
    fun bulletAndOrderedListsStaySeparate() {
        val blocks = Markdown.parse("- 一\n- 二\n\n1. 甲\n2) 乙")
        assertEquals(2, blocks.size, "an unordered block then an ordered one: $blocks")
        val first = assertIs<MdBlock.Bullets>(blocks[0])
        assertFalse(first.ordered)
        assertEquals(listOf("一", "二"), first.items.map { it.joinToString("") { r -> r.text } })

        val second = assertIs<MdBlock.Bullets>(blocks[1])
        assertTrue(second.ordered, "`1)` is an ordered marker too")
        assertEquals(listOf("甲", "乙"), second.items.map { it.joinToString("") { r -> r.text } })
    }

    @Test
    fun headingKeepsItsLevel() {
        val heading = assertIs<MdBlock.Heading>(Markdown.parse("### 结论").single())
        assertEquals(3, heading.level)
        assertEquals("结论", heading.runs.joinToString("") { it.text })
    }

    @Test
    fun unfinishedBoldStaysLiteral() {
        // The normal state mid-stream: `**` opened, nothing closed yet.
        val parsed = runs("结论是 **这张图里")
        assertEquals("结论是 **这张图里", parsed.joinToString("") { it.text })
        assertTrue(parsed.none { it.bold })
    }

    @Test
    fun unfinishedFenceDoesNotSwallowOrThrow() {
        val blocks = Markdown.parse("正文\n\n```kotlin\nval x = 1\n")
        assertEquals(2, blocks.size, "the unterminated fence is still a code block: $blocks")
        val code = assertIs<MdBlock.Code>(blocks[1])
        assertEquals("val x = 1", code.code)
    }

    @Test
    fun pathologicalInputNeverThrows() {
        val inputs = listOf(
            "*", "**", "***", "`", "```", "[", "[]", "[a](", "](x)", "\\", "**a*", "_a", "___",
            "- ", "1. ", "#", "#######", ">", "\n\n\n", "a\r\nb", "**`x`**", "[`a`](b)",
        )
        inputs.forEach { input ->
            Markdown.parse(input) // must not throw
            Markdown.isPlain(input)
        }
        assertTrue(Markdown.parse("").isEmpty(), "empty input has no blocks")
        assertTrue(Markdown.parse("   \n  ").isEmpty(), "blank input has no blocks")
    }

    @Test
    fun plainTextIsRecognisedSoTheFrozenBubbleCanRenderIt() {
        // `ChatThread` sends plain bodies through `Skin.MessageBubble` and only takes the rich
        // path when this returns false — the split has to be exact.
        assertTrue(Markdown.isPlain("就是一句普通的话，没有任何标记。"))
        assertTrue(Markdown.isPlain("两行\n普通文本"))
        assertTrue(Markdown.isPlain(""), "an empty streaming body is still plain")
        assertFalse(Markdown.isPlain("带 **粗体** 的句子"))
        assertFalse(Markdown.isPlain("带 `代码` 的句子"))
        assertFalse(Markdown.isPlain("带 [链接](https://x) 的句子"))
        assertFalse(Markdown.isPlain("```\ncode\n```"), "a fence is never plain")
        assertFalse(Markdown.isPlain("- 列表"), "a list is never plain")
    }

    @Test
    fun escapedMarkerIsLiteral() {
        val parsed = runs("这是 \\*不是斜体\\*")
        assertEquals("这是 *不是斜体*", parsed.joinToString("") { it.text })
        assertTrue(parsed.none { it.italic })
    }

    @Test
    fun theFixtureReplyParsesIntoTheExpectedShape() {
        // The thread's own reply is the best regression fixture: it exercises every branch.
        val blocks = Markdown.parse(vade.ui.chat.ChatFixtures.markdownReply)
        assertTrue(blocks.size >= 5, "paragraphs + list + fence, got ${blocks.size}")
        assertTrue(blocks.any { it is MdBlock.Code }, "the ```bash block must survive")
        assertTrue(blocks.any { it is MdBlock.Bullets }, "the numbered list must survive")
        val hasLink = blocks.any { block ->
            when (block) {
                is MdBlock.Paragraph -> block.runs.any { it.link != null }
                is MdBlock.Heading -> block.runs.any { it.link != null }
                is MdBlock.Bullets -> block.items.any { runs -> runs.any { it.link != null } }
                is MdBlock.Code -> false
            }
        }
        assertTrue(hasLink, "the RFC link must survive")
    }
}
