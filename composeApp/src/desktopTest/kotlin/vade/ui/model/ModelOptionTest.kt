package vade.ui.model

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import vade.ui.protocol.ModelEntry
import vade.ui.skin.SkinColors

/**
 * The data side of the model picker (RFC-0005 §3/§5): the 11-field `model/list` entry
 * mapping, the tri-state capability badges, and the row-visual decision. All pure —
 * no composition, no window, same discipline as ChatFlowTest.
 *
 * The exhaustive item (任务书 ①) is enforced by asserting ALL ELEVEN labeled detail rows
 * on one fully-declared fixture: if the mapping ever drops a wire field, its exact
 * expected value goes missing and the test goes red.
 */
class ModelOptionTest {

    /** Light-skin tokens, built straight from the visual-language palette (§1). */
    private val colors = SkinColors(
        primary = Color(0xFF5B5BD6),
        onPrimary = Color.White,
        surface = Color.White,
        surfaceVariant = Color(0xFFF2F2F4),
        onSurface = Color(0xFF1A1A1C),
        onSurfaceVariant = Color(0xFF6E6E76),
        outline = Color(0xFFD9D9DE),
        error = Color(0xFFB3261E),
        onError = Color.White,
        isDark = false,
    )

    private fun option(entry: ModelEntry) = ModelOption.of(entry)

    private val byLabel: Map<String, String> =
        option(ModelFixtures.claudeSonnet).detail.associate { it.label to it.value }

    // --- ① the 11 fields, exhaustively ----------------------------------------

    @Test
    fun elevenFieldsAllRenderExhaustively() {
        val detail = option(ModelFixtures.claudeSonnet).detail
        assertEquals(11, detail.size, "one row per wire field, no extras: ${detail.map { it.label }}")

        // Every field of the model/list entry with its expected rendering — 漏一个即红.
        assertEquals("claude-sonnet", byLabel["模型 ID"])
        assertEquals("anthropic", byLabel["Provider"])
        assertEquals("Claude Sonnet", byLabel["显示名"])
        assertEquals("text, image", byLabel["输入模态"])
        assertEquals("20.0 MB", byLabel["单图上限"])
        assertEquals("8.4M px", byLabel["单图像素"])
        assertEquals("4 张", byLabel["每轮图片"])
        assertEquals("200K tokens", byLabel["上下文"])
        assertEquals("支持", byLabel["工具调用"])
        assertEquals("10", byLabel["priority"])
        assertEquals("在线", byLabel["可用性"])
    }

    @Test
    fun nullFieldsSayDeclaredInsteadOfDisappearing() {
        // RFC-0005 §2: a chat module without capabilities.models → `<module>/*`, all null.
        val undeclared = option(ModelFixtures.undeclared)
        val detail = undeclared.detail.associate { it.label to it.value }
        assertEquals(11, detail.size)
        assertEquals("m6-echo/*", detail["模型 ID"])
        assertEquals("未声明", detail["Provider"])
        assertEquals("未声明", detail["显示名"])
        assertEquals("未声明", detail["输入模态"])
        assertEquals("未声明", detail["单图上限"])
        assertEquals("未声明", detail["单图像素"])
        assertEquals("未声明", detail["每轮图片"])
        assertEquals("未声明", detail["上下文"])
        assertEquals("未声明", detail["工具调用"])
        assertEquals("未声明", detail["priority"])
        assertEquals("在线", detail["可用性"], "available is runtime state, not a capability field — it stays declared")
        // Title falls back to the raw id; the meta line honestly says the provider is unknown.
        assertEquals("m6-echo/*", undeclared.title())
        assertEquals("provider 未声明", undeclared.metaLine())
        assertTrue(undeclared.imageLimitSummary == null)
    }

    // --- ② the badge tri-state --------------------------------------------------

    @Test
    fun visionBadgeHasAllThreeStatesFromData() {
        val supported = option(ModelFixtures.claudeSonnet)
        assertEquals(CapSupport.Supported, supported.vision)
        assertEquals("vision · 图片输入", supported.badges().first { it.kind == CapabilityKind.Vision }.text)

        val textOnly = option(ModelFixtures.localQwen)
        assertEquals(CapSupport.Unsupported, textOnly.vision)
        assertEquals("vision · 仅文本", textOnly.badges().first { it.kind == CapabilityKind.Vision }.text)

        val unknown = option(ModelFixtures.undeclared)
        assertEquals(CapSupport.Unknown, unknown.vision)
        assertEquals("vision · 未声明", unknown.badges().first { it.kind == CapabilityKind.Vision }.text)
    }

    @Test
    fun toolsBadgeHasAllThreeStatesFromData() {
        val supported = option(ModelFixtures.claudeSonnet)
        assertEquals(CapSupport.Supported, supported.tools)
        assertEquals("tools · 工具调用", supported.badges().first { it.kind == CapabilityKind.Tools }.text)

        val noTools = option(
            ModelEntry(
                id = "no-tools",
                provider = "test",
                displayName = "No Tools",
                inputModalities = listOf("text"),
                maxImageBytes = null,
                maxImagePx = null,
                maxImagesPerTurn = null,
                contextWindow = 8_000L,
                supportsTools = false,
                priority = 30,
                available = true,
            ),
        )
        assertEquals(CapSupport.Unsupported, noTools.tools)
        assertEquals("tools · 无工具", noTools.badges().first { it.kind == CapabilityKind.Tools }.text)

        val unknown = option(ModelFixtures.localQwen) // supports_tools = null
        assertEquals(CapSupport.Unknown, unknown.tools)
        assertEquals("tools · 未声明", unknown.badges().first { it.kind == CapabilityKind.Tools }.text)
    }

    @Test
    fun contextBadgeLightsUpOnlyOnADeclaredWindow() {
        val badges = option(ModelFixtures.claudeSonnet).badges()
        assertEquals(
            listOf(CapabilityKind.Vision, CapabilityKind.Tools, CapabilityKind.Context),
            badges.map { it.kind },
            "RFC-0005 order: 视觉 → 工具 → 长上下文",
        )
        assertEquals(CapSupport.Supported, badges[2].support)
        assertEquals("ctx · 200K", badges[2].text, "chip copy stays compact; the detail row carries 'tokens'")

        assertEquals("ctx · 32K", option(ModelFixtures.localQwen).badges()[2].text)
        assertEquals(
            CapSupport.Unknown,
            option(ModelFixtures.undeclared).badges()[2].support,
            "no context_window ⇒ 未声明, never a made-up number",
        )
    }

    @Test
    fun imageLimitsFeedOneDataDrivenSummaryLine() {
        assertEquals(
            "≤4 张/轮 · ≤20.0 MB · ≤8.4M px",
            option(ModelFixtures.claudeSonnet).imageLimitSummary,
        )
        assertNull(option(ModelFixtures.localQwen).imageLimitSummary)
    }

    // --- formatters ---------------------------------------------------------------

    @Test
    fun unitFormattersStayStableAcrossTargets() {
        assertEquals("512 B", formatModelBytes(512))
        assertEquals("1.0 KB", formatModelBytes(1024))
        assertEquals("1.7 MB", formatModelBytes(1_834_211))
        assertEquals("20.0 MB", formatModelBytes(20_971_520))
        assertEquals("3.0 GB", formatModelBytes(3_221_225_472))
        assertEquals("500000 px", formatModelPixels(500_000))
        assertEquals("8.4M px", formatModelPixels(8_388_608))
        assertEquals("999", formatContextWindow(999))
        assertEquals("32K", formatContextWindow(32_768))
        assertEquals("200K", formatContextWindow(200_000))
        assertEquals("1.0M", formatContextWindow(1_048_576))
    }

    // --- ③ the row visual decision -------------------------------------------------

    @Test
    fun selectedRowTakesTheAccentWashAndTheCheck() {
        val selected = modelRowStyle(colors, selected = true, selectable = true)
        assertEquals(colors.primary.copy(alpha = 0.08f), selected.background)
        assertEquals(colors.primary, selected.border)
        assertTrue(selected.showCheck, "the check glyph marks the selected row")

        val plain = modelRowStyle(colors, selected = false, selectable = true)
        assertNull(plain.background)
        assertEquals(colors.hairline, plain.border)
        assertFalse(plain.showCheck)
    }

    @Test
    fun darkSkinDeepensTheAccentWashPerVisualLanguage() {
        val dark = colors.copy(isDark = true)
        assertEquals(
            dark.primary.copy(alpha = 0.14f),
            modelRowStyle(dark, selected = true, selectable = true).background,
        )
    }
}
