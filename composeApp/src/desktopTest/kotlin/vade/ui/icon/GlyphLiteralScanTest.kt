package vade.ui.icon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 匹配器自测：钉的是 [GlyphLiteralScan] 本身，不是「今天源码恰好干净」。
 *
 * 这条守卫两次失效（先排除 `icon|shell|skin` 三个目录，再把调用形态写死在正则里）都伴随
 * 同一个现象：它一直是绿的。所以下面每一条都必须能在「匹配器被改弱」时变红——
 * 变异自验见 shots/g-icon-guard/RESULT.md。
 */
class GlyphLiteralScanTest {

    // --- 0. pattern 由词表在运行期拼出，可被复算 ---------------------------------

    @Test
    fun patternIsBuiltFromVocabularyAtRuntime() {
        assertEquals(
            "^[" + GlyphLiteralScan.VOCAB + "]{1,4}$",
            GlyphLiteralScan.ICON_LITERAL.pattern,
            "判类 pattern 必须由 VOCAB 运行期拼出；不等说明 pattern 又退回手写字面量了",
        )
        for (ch in listOf('↑', '↓', '←', '→', '▲', '▼', '■', '□', '●', '○', '◆', '＋', '…', '✕', '✓', '▾', '▸', '‹', '⌕', '✎', '•', '×', '◂', '⧗')) {
            assertTrue(GlyphLiteralScan.VOCAB.contains(ch), "词表漏了 $ch")
        }
    }

    // --- 1. 单行命中 -----------------------------------------------------------

    @Test
    fun singleLineLiteralIsAHit() {
        val hits = GlyphLiteralScan.hits("""val a = Text("←")""")
        assertEquals(listOf("←"), hits.map { it.text.trim() })
    }

    // --- 2. 跨行命中 -----------------------------------------------------------

    @Test
    fun multiLineCallIsAHit() {
        val source = """
            Text(
                "←",
                size = 12,
            )
        """.trimIndent()
        val hits = GlyphLiteralScan.hits(source)
        assertEquals(listOf("←"), hits.map { it.text.trim() }, "跨行 Text( 里的字形必须命中")
        assertEquals(listOf(2), hits.map { it.line }, "行号必须是字面量所在行，不是调用起始行")
    }

    // --- 3. 注释不命中 ---------------------------------------------------------

    @Test
    fun commentedOutLiteralIsNotAHit() {
        val source = """
            // Text("←")
            /* Text("✕") */
            /* 外层 /* 内层 Text("▸") */ 仍是注释 */
            val keep = "ok"
            Text("✓")
        """.trimIndent()
        val hits = GlyphLiteralScan.hits(source)
        assertEquals(listOf("✓"), hits.map { it.text.trim() }, "注释里的字形不算 offender；注释之后的真字面量仍要算")
        assertEquals(listOf(5), hits.map { it.line })
    }

    // --- 4. 长正文不命中 -------------------------------------------------------

    @Test
    fun proseWithGlyphIsNotAHit() {
        // 这些都是真实存在的正文/提示串，字形只是句子的一部分。
        // 守卫一旦放宽成「含图标字符即违规」，它们就会变成误报。
        val source = """
            Text("正在生成 · 点 ■ 停止")
            UiText("↑ ↓ 选择 · Enter 进入 · Esc 关闭")
            Text("校验中…")
            Text("1.7 MB · 2048×1536")
        """.trimIndent()
        assertEquals(emptyList(), GlyphLiteralScan.hits(source), "长正文 / 键位提示 / 尺寸文案不得被判成字符图标")
        assertTrue(GlyphLiteralScan.PROSE.containsMatchIn("这是一段很长的正文提示"), "≥6 汉字必须被散文闸门认出来")
        assertFalse(GlyphLiteralScan.PROSE.containsMatchIn("校验中…"), "短提示不该被散文闸门吞掉（它靠词表判类排除）")
    }

    // --- 5. 旧正则漏掉的调用形态（守卫真正失效的地方）---------------------------

    @Test
    fun namedArgumentExpressionAndCustomCallShapesAreHits() {
        val source = """
            TopBarButton(glyph = if (state.panelOpen) "▸" else "◂", onClick = {})
            UiText(item.glyph ?: "•")
            skin.Button("×", onClick = onClose, style = BtnStyle.Text)
            Text(
                text = if (expanded) "▾" else "▸",
            )
            listOf("↑" to BtnStyle.Primary, "＋" to BtnStyle.Secondary)
        """.trimIndent()
        val hits = GlyphLiteralScan.hits(source)
        assertEquals(
            listOf("▸", "◂", "•", "×", "▾", "▸", "↑", "＋"),
            hits.map { it.text.trim() },
            "自定义按钮 / 命名字参 / if 表达式 / elvis 兜底 / listOf 裸字面量都必须在扫描范围内",
        )
        assertEquals(listOf(1, 1, 2, 3, 5, 5, 7, 7), hits.map { it.line })
    }

    // --- 6. 转义写法也要命中 ---------------------------------------------------

    @Test
    fun escapedUnicodeLiteralIsAHit() {
        val source = "val a = Text(\"\\u2190\")"
        assertEquals(listOf("←"), GlyphLiteralScan.hits(source).map { it.text.trim() })
    }

    // --- 7. raw string 里的字形也要命中 ----------------------------------------

    @Test
    fun rawStringLiteralIsAHit() {
        val source = "val a = Text(" + "\"\"\"" + "←" + "\"\"\"" + ")"
        assertEquals(listOf("←"), GlyphLiteralScan.hits(source).map { it.text.trim() })
    }

    // --- 8. 白名单：只放行它指的那一处，失效必须自曝 ----------------------------

    @Test
    fun allowEntrySilencesOnlyItsOwnOffender() {
        val offenders = listOf(
            GlyphLiteralScan.Offender("shell/A.kt", 10, "•", "text = glyph ?: \"•\""),
            GlyphLiteralScan.Offender("shell/B.kt", 10, "•", "text = glyph ?: \"•\""),
        )
        val allow = listOf(
            GlyphLiteralScan.Allow("shell/A.kt", 10, "•", "g-float 正在改该文件，合入后删除"),
        )
        val result = GlyphLiteralScan.applyAllow(offenders, allow)
        assertEquals(listOf("shell/B.kt"), result.offenders.map { it.path }, "白名单不得连带放过同字形的别的文件")
        assertEquals(emptyList(), result.staleAllows)
    }

    @Test
    fun staleAllowEntryIsReported() {
        // 行号漂了 / 字面量已经改掉：白名单必须自己报失效，否则它会永久静默。
        val allow = listOf(
            GlyphLiteralScan.Allow("shell/A.kt", 9, "•", "行号已漂移"),
            GlyphLiteralScan.Allow("shell/A.kt", 10, "×", "字形已改掉"),
        )
        val offenders = listOf(GlyphLiteralScan.Offender("shell/A.kt", 10, "•", "text = glyph ?: \"•\""))
        val result = GlyphLiteralScan.applyAllow(offenders, allow)
        assertEquals(1, result.offenders.size, "白名单对不上时不能吞掉 offender")
        assertEquals(allow, result.staleAllows, "两条白名单都已失效，必须全部报出来")
    }

    @Test
    fun allowEntryRequiresAReason() {
        assertFailsWith<IllegalArgumentException> {
            GlyphLiteralScan.Allow("shell/A.kt", 10, "•", "   ")
        }
    }
}
