package vade.ui.skin

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import vade.ui.skin.material.MaterialSkin
import vade.ui.skin.material.MaterialSkinEntry

/**
 * `material` dark anti-regression (docs/visual-language.md §2 暗色表 / §5 / §8).
 *
 * The original defect this file pins shut: `MaterialSkin` hardcoded `isDark = false`, so
 * `--dark` produced a byte-identical light render. Every assertion here fails again if
 * someone collapses the two palettes back into one:
 *
 *  1. light/dark canvas tokens differ;
 *  2. the dark canvas is *truly* dark (not a near-white swap);
 *  3. dark text/background contrast ≥ 4.5:1 (WCAG relative luminance, hand-rolled — no
 *     new dependencies);
 *  4. the dark accent is brighter than the light one;
 *  5. the dark hairline is *lighter* than the dark canvas (a light line on dark ground,
 *     not white-on-white and not the light theme's darker-than-canvas line);
 *  6. the token key sets match — every colour token is authored per theme, none silently
 *     falls back to the light value or a `SkinColors` default.
 */
class MaterialSkinDarkTest {

    private val light = MaterialSkin(dark = false).colors
    private val dark = MaterialSkin(dark = true).colors

    // --- WCAG 2.x relative luminance / contrast, pure Kotlin --------------------

    private fun channel(c: Double): Double =
        if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun relLum(color: Color): Double =
        0.2126 * channel(color.red.toDouble()) +
            0.7152 * channel(color.green.toDouble()) +
            0.0722 * channel(color.blue.toDouble())

    private fun contrast(a: Color, b: Color): Double {
        val la = relLum(a)
        val lb = relLum(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** Composite a translucent colour over an opaque backdrop (per channel). */
    private fun blend(fg: Color, bg: Color): Color = Color(
        red = fg.red * fg.alpha + bg.red * (1f - fg.alpha),
        green = fg.green * fg.alpha + bg.green * (1f - fg.alpha),
        blue = fg.blue * fg.alpha + bg.blue * (1f - fg.alpha),
        alpha = 1f,
    )

    // --- 1 · light and dark must differ ------------------------------------------

    @Test
    fun lightAndDarkPalettesDiffer() {
        assertFalse(light.isDark)
        assertTrue(dark.isDark)
        // The headline defect: dark must not render as light. Canvas is the app backdrop —
        // if these are equal the two themes are byte-identical again.
        assertNotEquals(light.canvas, dark.canvas, "light/dark canvas 必须不同（§2 两张表）")
        assertNotEquals(light.surface, dark.surface, "light/dark surface 必须不同")
        assertNotEquals(light.hairline, dark.hairline, "light/dark 发丝线必须不同")
        assertNotEquals(light.primary, dark.primary, "light/dark 强调色必须不同")
    }

    // --- 2 · dark canvas is truly dark --------------------------------------------

    @Test
    fun darkCanvasIsTrulyDarkNotANearWhiteSwap() {
        val darkLum = relLum(dark.canvas)
        val lightLum = relLum(light.canvas)
        assertTrue(darkLum < lightLum, "暗色 canvas 亮度必须低于亮色：dark=$darkLum light=$lightLum")
        assertTrue(darkLum < 0.10, "暗色 canvas 是真暗色（相对亮度 <0.10），不是换个近白色：$darkLum")
        assertTrue(lightLum > 0.70, "亮色 canvas 保持亮（§2 #F7F7F8）：$lightLum")
    }

    // --- 3 · WCAG AA contrast in dark ----------------------------------------------

    @Test
    fun darkTextContrastMeetsWcagAA() {
        // 正文 ≥ 4.5:1（§2 对比度要求）—— 主文本、次要文本各自对 canvas 与 surface。
        assertTrue(contrast(dark.onSurface, dark.canvas) >= 4.5, "主文本/canvas")
        assertTrue(contrast(dark.onSurface, dark.surface) >= 4.5, "主文本/surface")
        assertTrue(contrast(dark.onSurfaceVariant, dark.canvas) >= 4.5, "次要文本/canvas")
        assertTrue(contrast(dark.onSurfaceVariant, dark.surface) >= 4.5, "次要文本/surface")
        // error 文字（TextField 错误行）与压在玻璃上的文字也算文字。
        assertTrue(contrast(dark.error, dark.canvas) >= 4.5, "error 文字/canvas")
        val glassBase = blend(dark.surfaceGlass, dark.canvas)
        assertTrue(contrast(dark.textOnGlass, glassBase) >= 4.5, "textOnGlass/玻璃退化底")
        // 反白/反黑字压在 accent 与 danger 实色上（主按钮、危险按钮）。
        assertTrue(contrast(dark.onPrimary, dark.primary) >= 4.5, "onPrimary/accent")
        assertTrue(contrast(dark.onError, dark.error) >= 4.5, "onError/danger")
    }

    @Test
    fun lightTextContrastStillMeetsWcagAA() {
        // §八.1 的要求是“亮暗两套”——别让暗色达标时把亮色弄丢。
        assertTrue(contrast(light.onSurface, light.canvas) >= 4.5, "亮色主文本/canvas")
        assertTrue(contrast(light.onSurfaceVariant, light.canvas) >= 4.5, "亮色次要文本/canvas")
        assertTrue(contrast(light.onSurfaceVariant, light.surface) >= 4.5, "亮色次要文本/surface")
        assertTrue(contrast(light.onPrimary, light.primary) >= 4.5, "亮色 onPrimary/accent")
    }

    // --- 4 · dark accent is brighter ------------------------------------------------

    @Test
    fun darkAccentIsBrighterThanLightAccent() {
        val darkLum = relLum(dark.primary)
        val lightLum = relLum(light.primary)
        assertTrue(darkLum > lightLum, "暗色下 accent 提亮（§2 暗色表 #8B8BF0 > #5B5BD6）")
        assertEquals(Color(0xFF8B8BF0), dark.primary, "暗色 accent = §2 暗色表值")
        assertEquals(Color(0xFF5B5BD6), light.primary, "亮色 accent = §2 亮色表值")
        // 辉光跟 accent 走（§8 row 3），暗色也必须一起提亮。
        assertEquals(dark.primary, dark.glowAccent)
    }

    // --- 5 · dark hairline is a light line on dark ground ----------------------------

    @Test
    fun darkHairlineIsLighterThanCanvasNotWhiteOnWhite() {
        val darkLum = relLum(dark.hairline)
        val canvasLum = relLum(dark.canvas)
        assertTrue(
            darkLum > canvasLum,
            "暗色发丝线必须比暗色画布亮（#2A2A2E > #0E0E10），否则线看不见",
        )
        assertTrue(darkLum < 0.5, "发丝线不是白色叠白（#FFFFFF 在暗底上是纯白刺线）")
        assertTrue(relLum(light.hairline) < relLum(light.canvas), "亮色发丝线比画布暗（既有行为不变）")
        assertNotEquals(light.hairline, dark.hairline)
        assertEquals(dark.outline, dark.hairline, "outline/hairline 同源（亮色也是同源）")
    }

    // --- 6 · token key sets match (nothing silently missing in dark) -----------------

    @Test
    fun tokenKeySetsMatchEveryColorAuthoredPerTheme() {
        // SkinColors 的每个 token 槽位都在这张表里；给 SkinColors 加字段时必须同步加进来。
        // 暗色漏写某个槽位时它会回落到 light 值或 data class 默认值——下面的
        // “dark != light”逐槽断言会把这两种静默回落全部打红。
        val tokens: List<Pair<String, (SkinColors) -> Any>> = listOf(
            "primary" to { it.primary },
            "onPrimary" to { it.onPrimary },
            "surface" to { it.surface },
            "surfaceVariant" to { it.surfaceVariant },
            "onSurface" to { it.onSurface },
            "onSurfaceVariant" to { it.onSurfaceVariant },
            "outline" to { it.outline },
            "error" to { it.error },
            "onError" to { it.onError },
            "canvas" to { it.canvas },
            "hairline" to { it.hairline },
            "success" to { it.success },
            "warning" to { it.warning },
            "surfaceGlass" to { it.surfaceGlass },
            "shadowSoft" to { it.shadowSoft },
            "shadowFloat" to { it.shadowFloat },
            "glowAccent" to { it.glowAccent },
            "textOnGlass" to { it.textOnGlass },
            "isDark" to { it.isDark },
        )
        assertEquals(19, tokens.size, "SkinColors 槽位变了就同步这张表")
        tokens.forEach { (name, get) ->
            val l = get(light)
            val d = get(dark)
            assertNotEquals(l, d, "token `$name` 的暗色值不能照抄亮色（漏写 = 静默回落）")
        }
    }

    // --- doc-table pinning + §5/§8 derivation ----------------------------------------

    @Test
    fun darkTokensMatchVisualLanguageTable() {
        // §2 暗色表原文值，逐个钉住（改表必须同时改这里，反之亦然）。
        assertEquals(Color(0xFF0E0E10), dark.canvas)
        assertEquals(Color(0xFF161618), dark.surface)
        assertEquals(Color(0xFF2A2A2E), dark.hairline)
        assertEquals(Color(0xFFEDEDF0), dark.onSurface)
        assertEquals(Color(0xFF9A9AA3), dark.onSurfaceVariant)
        assertEquals(Color(0xFFF16A6A), dark.error)
        // §2 没写的两枚状态色按既有规则推导（亮值保色相提亮，MiuixTokens 同一推导）。
        assertEquals(Color(0xFF4FD1A0), dark.success)
        assertEquals(Color(0xFFE8A33D), dark.warning)
    }

    @Test
    fun darkGlassAndShadowFollowTheDocDerivation() {
        // §5：暗色玻璃染色 #0E0E10@34%（GlassSurface 按 isDark 取 glass.tintDark）。
        assertEquals(Color(0xFF0E0E10).copy(alpha = 0.34f), MaterialSkin(dark = true).glass.tintDark)
        assertEquals(Color(0xFFFFFFFF).copy(alpha = 0.26f), MaterialSkin(dark = false).glass.tintLight)
        // §8：暗色阴影 alpha ×1.8（30%→54%），blur ×1.2 钉在 skin 内部的 blur 代理上。
        assertEquals(0.302f, light.shadowSoft.alpha, 0.01f)
        assertEquals(0.541f, dark.shadowSoft.alpha, 0.01f)
        assertEquals(light.shadowSoft.alpha * 1.8f, dark.shadowSoft.alpha, 0.01f)
        assertEquals(light.shadowFloat.alpha * 1.8f, dark.shadowFloat.alpha, 0.01f)
        val darkSkin = MaterialSkin(dark = true)
        val lightSkin = MaterialSkin(dark = false)
        assertEquals(2.4.dp, darkSkin.shadowBlurSoft, "§8 blur ×1.2：2dp→2.4dp")
        assertEquals(19.2.dp, darkSkin.shadowBlurFloat, "§8 blur ×1.2：16dp→19.2dp")
        assertEquals(2.dp, lightSkin.shadowBlurSoft, "亮色 blur 不动")
        assertEquals(16.dp, lightSkin.shadowBlurFloat, "亮色 blur 不动")
        // 退化玻璃底用 §2 surfaceRaised（#1C1C1F@78%），保持半透明。
        assertEquals(Color(0xFF1C1C1F).copy(alpha = 0.78f), dark.surfaceGlass)
        assertTrue(dark.surfaceGlass.alpha < 1f)
        // §2 accentSoft：亮 8% / 暗 14%（ListItem 选中底的 accent alpha）。
        assertEquals(0.08f, lightSkin.accentSoftAlpha)
        assertEquals(0.14f, darkSkin.accentSoftAlpha)
    }

    // --- the --dark plumbing itself ---------------------------------------------------

    @Test
    fun entryPlumbsDarkThroughRegistryLikeMiuixSkinEntry() {
        // Main.kt 的 --dark 就是写这个 var；工厂闭包在读它。两态都要能从注册表拿出来。
        MaterialSkinEntry.dark = false
        MaterialSkinEntry.register()
        assertFalse(SkinRegistry.get("material").colors.isDark, "默认注册 = 亮色")
        MaterialSkinEntry.dark = true
        MaterialSkinEntry.register()
        assertTrue(SkinRegistry.get("material").colors.isDark, "--dark 后注册的工厂必须产暗色")
        // 还原默认，别把亮色基线留给别的测试。
        MaterialSkinEntry.dark = false
        MaterialSkinEntry.register()
        assertFalse(SkinRegistry.get("material").colors.isDark)
    }
}
