package vade.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 深色皮肤下助手回复必须是 onSurface，而不是 BasicText 的 LocalContentColor（黑）。
 *
 * 回归点：`MarkdownText(color=...)` 曾经只把颜色用在代码块上，正文 AnnotatedString
 * 不带基色；助手消息没有气泡 Surface，于是整段回落到黑色。这个用例直接检查
 * `annotated()` 产出的 SpanStyle 基色，避免再靠截图肉眼发现。
 */
class MarkdownTextColorTest {

    private val onSurface = Color(0xFFE6E1E5)
    private val link = Color(0xFF8AB4F8)

    @Test
    fun everyRunCarriesTheBaseColor() {
        val text = annotated(
            runs = listOf(
                MdRun("普通"),
                MdRun("加粗", bold = true),
                MdRun("代码", code = true),
            ),
            color = onSurface,
            size = 15,
            mono = FontFamily.Monospace,
            codeBackground = Color(0xFF2A2A2E),
            linkColor = link,
        )

        // 基色先铺满全文，后面的 SpanStyle 只叠加字重/字体，不覆盖颜色。
        val base = text.spanStyles.filter { it.start == 0 && it.end == text.length }
        assertTrue(base.isNotEmpty(), "missing full-range base style")
        assertTrue(
            base.any { it.item.color == onSurface },
            "base color must be onSurface, got ${base.map { it.item.color }}",
        )
    }

    @Test
    fun linksKeepTheirOwnColorOverTheBase() {
        val text = annotated(
            runs = listOf(MdRun("看这里", link = "https://example.com")),
            color = onSurface,
            size = 15,
            mono = FontFamily.Monospace,
            codeBackground = Color(0xFF2A2A2E),
            linkColor = link,
        )

        val linkSpan = text.spanStyles.last { it.start == 0 && it.end == text.length }
        assertEquals(link, linkSpan.item.color)
    }
}
