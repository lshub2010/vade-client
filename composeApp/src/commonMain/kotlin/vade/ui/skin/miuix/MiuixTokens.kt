package vade.ui.skin.miuix

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.TextStyles
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.defaultTextStyles
import top.yukonga.miuix.kmp.theme.lightColorScheme
import vade.ui.skin.SkinColors
import vade.ui.skin.SkinMotion
import vade.ui.skin.SkinShapes
import vade.ui.skin.SkinTypography
import vade.ui.skin.VadeFonts

/**
 * U2 · `miuix` design tokens.
 *
 * Everything here is skin-private: pages read colours only through `Skin.colors` (C-09) and
 * never through a Miuix type. The values are chosen to be *less* Material rather than
 * "Material in another palette" (task book §四):
 *
 *  - shapes follow the v2 six-step ladder exactly (xs 12 / sm 16 / md 20 / lg 24 / xl 28 /
 *    sheet 32, see [shapes]);
 *  - the page background is a warm grey and surfaces are flat white — hierarchy comes from
 *    a 1 dp hairline, with only the two §8 shadow exceptions (static cards get `shadowSoft`,
 *    float layers get `shadowFloat`; see visual-language v2 §8). Miuix's own default `Card`
 *    has no surface at all (spike/miuix RESULT.md §五 R5), so every container here paints
 *    its own colour *and* its own stroke;
 *  - type follows `ui-visual-language.md` §四 (15 body / 13 secondary / 11 tiny).
 *
 * Dark mode is a second value set over the same semantic names, not a tinted light theme.
 */
internal object MiuixTokens {

    // --- light -----------------------------------------------------------------

    val lightColors = SkinColors(
        // Miuix's own accent is a saturated blue; keeping that hue family is what makes the
        // skin read as Miuix instead of "Material blue". Darkened from #2E6BE6 → #2A62D4
        // because the original measured 4.27:1 against `surfaceVariant` (#F2F1EF), under the
        // spec's 4.5:1 floor for anything carrying information (§二 对比度要求). 4.90 / 5.53 now.
        primary = Color(0xFF2A62D4),
        onPrimary = Color(0xFFFFFFFF),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFF2F1EF),
        onSurface = Color(0xFF141416),
        // Was #6E6E73 → 4.49:1 on `surfaceVariant`, i.e. it failed the 4.5 floor by a hair and
        // passed only on `surface`. Timestamps and secondary copy sit on both, so the darker
        // value is the correct one: 5.05 / 5.70.
        onSurfaceVariant = Color(0xFF66666C),
        outline = Color(0xFFDCDAD5),
        // Same fix as primary: #C93B2E was 4.48:1 on `surfaceVariant`. Error text must clear the
        // floor wherever it lands, so #C03328 (4.97 / 5.61).
        error = Color(0xFFC03328),
        onError = Color(0xFFFFFFFF),
        isDark = false,
        // --- v2 tokens (visual-language v2 §2/§5/§8) ------------------------------
        // The page canvas is the warm grey already used for `surfaceVariant` — v2 renames the
        // *semantic*, the palette keeps Miuix's own value (颜色可不同，语义必须一致).
        canvas = Color(0xFFF2F1EF),
        hairline = Color(0xFFDCDAD5),
        // Glass base (§5). This is the *no-haze* base a glass layer reads as until the app
        // shell registers a HazeState — ChatThread/shell are outside this batch, so the
        // composer/bar/sheet degrade to tint + hairline + shadowFloat. 26% (the §5 blur-tint
        // value) is invisible as a *field* on the warm canvas; the degrade shape needs to
        // lift the layer off the page while staying translucent (0.78 = backdrop ghosts
        // through without staying readable — glass, not a second page). The §5 26/34 tints
        // stay encoded in `Skin.glass` (`SkinGlass`) for the real-blur wiring.
        surfaceGlass = Color.White.copy(alpha = 0.78f),
        // §8 row 1 — static-card shadow `0 1 2 rgba(0,0,0,.06)`.
        shadowSoft = Color.Black.copy(alpha = 0.06f),
        // §8 row 2 — float-layer shadow `0 12 32 rgba(0,0,0,.18)`.
        shadowFloat = Color.Black.copy(alpha = 0.18f),
        // §8 row 3 — accent glow for the send ball, NOT a shadow colour.
        glowAccent = Color(0xFF2A62D4),
        textOnGlass = Color(0xFF141416),
    )

    // --- dark ------------------------------------------------------------------

    val darkColors = SkinColors(
        primary = Color(0xFF5B8DEF),
        onPrimary = Color(0xFF0B1220),
        // Miuix dark is pure black with lifted containers — not Material's grey #121212.
        surface = Color(0xFF1C1C1E),
        surfaceVariant = Color(0xFF000000),
        onSurface = Color(0xFFF2F2F7),
        onSurfaceVariant = Color(0xFF9A9AA0),
        outline = Color(0xFF3A3A3C),
        error = Color(0xFFFF6B5E),
        onError = Color(0xFF1A0503),
        isDark = true,
        // --- v2 tokens, dark side ---------------------------------------------------
        // §8: dark shadows run deeper and longer — 6 % black is invisible on #000000.
        canvas = Color(0xFF000000),
        hairline = Color(0xFF3A3A3C),
        // Dark: the §5 34 % black tint is invisible on #000000 — lift with the surface tone.
        surfaceGlass = Color(0xFF1C1C1E).copy(alpha = 0.78f),
        shadowSoft = Color.Black.copy(alpha = 0.35f),
        shadowFloat = Color.Black.copy(alpha = 0.55f),
        glowAccent = Color(0xFF5B8DEF),
        textOnGlass = Color(0xFFF2F2F7),
    )

    fun colors(dark: Boolean): SkinColors = if (dark) darkColors else lightColors

    // --- shapes ----------------------------------------------------------------

    /**
     * Radius ladder. `ui-visual-language.md` **v2 §10** fixes the six-step scale at
     * **12 / 16 / 20 / 24 / 28 / 32** and ties each step to a component class; a skin may
     * change colours but *not* the ladder (§八 验收 2: "同屏 ≤3 种"). The v1 8/12/16/20/28
     * ladder was raised one notch when the user asked for "大圆角" — 12/16/20 stayed in the
     * aliases while the whole scale moved, which is precisely the drift §10 forbids.
     *
     * `SkinShapes` (v2) carries all six slots plus the two v1 alias names, so [shapes]
     * points the aliases at the v2 steps by *name* — `small`→sm 16, `medium`→md 20,
     * `large`→lg 24 — and the extra steps live in the skin-private constants below:
     *
     *   xs     = 12 → chip、徽标、小按钮、代码块内联片段     (v2 `xs`, was 8)
     *   sm     = 16 → 列表行、次要按钮、输入框内层           (v2 `sm`)
     *   md     = 20 → 工具卡、附件卡、搜索结果行             (v2 `md`)
     *   lg     = 24 → 用户消息气泡、图片缩略、模块卡片       (v2 `lg`)
     *   xl     = 28 → 悬浮 composer、主卡片                  (v2 `xl`)
     *   sheet  = 32 → 全局弹窗卡片（四角同圆）               (v2 `sheet`)
     *   pill   = 999 → 主按钮、chip、状态药丸
     */
    val shapes = SkinShapes(
        // v1 aliases: point them at the v2 steps by name (§10 raised every step).
        small = 16.dp,
        medium = 20.dp,
        large = 24.dp,
        pill = 999.dp,
        // v2 six-step scale.
        xs = 12.dp,
        sm = 16.dp,
        md = 20.dp,
        lg = 24.dp,
        xl = 28.dp,
        sheet = 32.dp,
    )

    /** v2 `xs` — chip、徽标、代码块内联. The floor: nothing in this skin goes below 12 dp. */
    val radiusXs = 12.dp

    /**
     * v2 `xl` — 悬浮 composer / 主卡片. Kept as a skin-private name because the
     * `ApprovalDialog` sheet historically read it; v2 sheets use [sheetRadius] (32).
     */
    val xlRadius = 28.dp

    /**
     * v2 `sheet` — 全局弹窗卡片，四角同圆 (§9/§10). The v1 sheet read `xl` (28) with flush
     * top corners; v2 floats the card with 12 dp margins and all four corners at 32.
     */
    val sheetRadius = 32.dp

    // --- type (spec §四) --------------------------------------------------------

    /**
     * `ui-visual-language.md` §四 fixes the type scale: 正文 15/400, 次要 13/400, 极小 11/500,
     * 列表标题 15/600, 页面标题 20/600.
     *
     * The previous revision ran a notch smaller (16/13/10) as a "compact density" delta. That
     * is not a delta the spec permits — 13 sp body copy on a 480 dp phone reads as dense
     * *and* fails the 4.5:1 perceptual check at a glance because the strokes get thin. Corrected
     * to the spec scale; density is now expressed through row height and padding, not type size.
     *
     * **The family comes from [VadeFonts.sans], and it is a getter on purpose.** These are
     * explicit `TextStyle`s (they carry sizes/weights/line-heights of their own), so they do
     * not inherit `SkinTypography`'s defaults — that was the last hole in the font seam
     * (`shots/g-font-bundle2/RESULT.md`): under this skin Web drew tofu and fell back to
     * `fonts.gstatic.com`. Reading the seam per access (instead of capturing
     * `FontFamily.Default` at object-init time) means an install that lands *after* the
     * tokens were first touched still wins. On desktop/Android `VadeFonts.sans` **is**
     * `FontFamily.Default`, i.e. exactly what a null family resolved to before, so nothing
     * re-flows there.
     */
    val typography: SkinTypography
        get() = SkinTypography(
            title = TextStyle(
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 26.sp,
                fontFamily = VadeFonts.sans,
            ),
            body = TextStyle(fontSize = 15.sp, lineHeight = 23.sp, fontFamily = VadeFonts.sans),
            caption = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, fontFamily = VadeFonts.sans),
            // mono keeps Monospace: replacing it would break the probe's column alignment
            // (same rule as `SkinTypography.mono`).
            mono = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace, lineHeight = 20.sp),
        )

    /**
     * Miuix's own `TextStyles` bag, routed through the same font seam as [typography].
     *
     * `MiuixTheme`'s text styles are the **second** family source in this skin, and the one
     * that actually paints most of the pixels: Miuix's `Text` merges with `textStyles.main`
     * when the caller passes no style (`Text("仅本次允许", fontSize = 14.sp)` all over
     * `MiuixParts.kt`), and `Button` with `textStyles.button`. Left alone they hand Skiko
     * `FontFamily.Default` — tofu on Web. So every slot is re-pointed at [family].
     *
     * When [family] is `FontFamily.Default` (desktop, Android) the **untouched**
     * `defaultTextStyles()` is returned as the same object the skin used before this seam
     * existed — not a copy with `fontFamily = Default` written into it. That is what makes
     * "桌面/Android 观感零变化" true by construction rather than by inspection.
     */
    fun miuixTextStyles(family: FontFamily = VadeFonts.sans): TextStyles {
        val base = defaultTextStyles()
        if (family == FontFamily.Default) return base
        fun TextStyle.seam() = copy(fontFamily = family)
        return base.copy(
            main = base.main.seam(),
            paragraph = base.paragraph.seam(),
            body1 = base.body1.seam(),
            body2 = base.body2.seam(),
            button = base.button.seam(),
            footnote1 = base.footnote1.seam(),
            footnote2 = base.footnote2.seam(),
            headline1 = base.headline1.seam(),
            headline2 = base.headline2.seam(),
            subtitle = base.subtitle.seam(),
            title1 = base.title1.seam(),
            title2 = base.title2.seam(),
            title3 = base.title3.seam(),
            title4 = base.title4.seam(),
        )
    }

    /** Spec 极小标签 — 11 sp / 500. Too small for [SkinTypography], which stops at `caption`. */
    val tinySize = 11.sp
    /** Spec 列表标题 — 15 sp / 600. */
    val listTitleSize = 15.sp

    /** 120/160/200 ms — Miuix transitions are shorter and more direct than M3's. */
    val motion = SkinMotion(fastMs = 120, normalMs = 160, emphasizedMs = 200)

    // --- status colours ---------------------------------------------------------
    //
    // `SkinColors` carries only `primary` / `error`, but spec §五 工具卡 needs a *three-way*
    // status bar: 成功 success / 失败 danger / 审批 warn. Without these the skin fell back to
    // `outline` for "success", which is the near-invisible grey hairline colour — that is the
    // flat grey bar the first screenshot showed. Skin-private, like the rest of the palette.

    /** Spec §二 `success` #2F7D5A (4.9:1 on white), lifted for dark. */
    fun success(isDark: Boolean) = if (isDark) Color(0xFF4FD1A0) else Color(0xFF2F7D5A)

    /** Spec §二 `warn` #B26A00 (5.0:1 on white), lifted for dark. */
    fun warn(isDark: Boolean) = if (isDark) Color(0xFFE8A33D) else Color(0xFFB26A00)

    // --- component metrics ------------------------------------------------------

    /** Compact list row (material's is 56 dp). Task book §四: 48 dp. */
    val rowHeight = 48.dp
    val buttonHeight = 32.dp
    /** Spec §五 底栏: 高 64 + 安全区. */
    val barHeight = 64.dp
    val dividerThickness = 1.dp
    val cardInsideMargin = PaddingValues(0.dp)
    val listInsideMargin = PaddingValues(horizontal = 12.dp, vertical = 5.dp)

    // --- floating composer (v2 §1/§2) --------------------------------------------

    /** §1: the composer floats with 16 dp clear on every side — never flush to the edge. */
    val composerMargin = 16.dp
    /** §2: the send ball is a 48 dp circle *outside* the field, 12 dp from it. */
    val sendBallSize = 48.dp
    val sendBallGap = 12.dp
    /** §五 消息（用户）: 右下角 6dp 气泡尾 — fixed value, not on the radius ladder. */
    val bubbleTail = 6.dp

    /**
     * Desktop density compensation (task book §T3): Miuix is laid out for phones, so at the
     * same dp the controls look undersized on a 1280×800 window. 1.18 was picked by
     * comparing the 480×900 and 1280×800 shots of the same page (see README §桌面密度).
     */
    const val DESKTOP_DENSITY_SCALE = 1.18f

    // --- Miuix theme bridge -----------------------------------------------------

    /**
     * Maps the semantic [SkinColors] onto Miuix's much wider `Colors` bag. Only the slots
     * the twelve components can actually reach are filled; the rest keep Miuix's defaults so
     * an upstream default change cannot silently repaint the skin.
     */
    fun miuixColors(c: SkinColors): Colors {
        val base = if (c.isDark) darkColorScheme() else lightColorScheme()
        return base.copy(
            primary = c.primary,
            onPrimary = c.onPrimary,
            primaryContainer = if (c.isDark) Color(0xFF26262A) else Color(0xFFE8EEFB),
            onPrimaryContainer = c.primary,
            secondaryContainer = if (c.isDark) Color(0xFF2C2C2E) else Color(0xFFF2F1EF),
            onSecondaryContainer = c.onSurfaceVariant,
            background = c.surfaceVariant,
            onBackground = c.onSurface,
            onBackgroundVariant = c.onSurfaceVariant,
            surface = c.surface,
            onSurface = c.onSurface,
            onSurfaceSecondary = c.onSurfaceVariant,
            onSurfaceVariantSummary = c.onSurfaceVariant,
            onSurfaceVariantActions = c.onSurfaceVariant,
            surfaceContainer = c.surface,
            onSurfaceContainer = c.onSurface,
            surfaceContainerHigh = if (c.isDark) Color(0xFF26262A) else Color(0xFFF7F6F4),
            surfaceContainerHighest = if (c.isDark) Color(0xFF2C2C2E) else Color(0xFFF2F1EF),
            outline = c.outline,
            dividerLine = c.outline,
            error = c.error,
            onError = c.onError,
            errorContainer = if (c.isDark) Color(0xFF3A1714) else Color(0xFFFBEAE8),
            onErrorContainer = c.error,
            disabledPrimary = c.onSurfaceVariant.copy(alpha = 0.35f),
            disabledOnPrimary = c.surface,
            disabledPrimaryButton = if (c.isDark) Color(0xFF2C2C2E) else Color(0xFFEDEBE8),
            disabledOnPrimaryButton = c.onSurfaceVariant.copy(alpha = 0.55f),
        )
    }

    /**
     * Card colour note (spike/miuix RESULT.md §五 R5): Miuix's `CardDefaults` paints
     * `surface` == `background`, i.e. an *invisible* card. That is why `MiuixSkin.Card`
     * does not use `Card` at all — it uses Miuix `Surface` with an explicit colour **and**
     * stroke. Kept here as documentation so the choice is not "rediscovered" later.
     */
}

/**
 * Wraps a component subtree in the Miuix theme plus the desktop density compensation.
 *
 * Every `MiuixSkin` component funnels through here so that (a) Miuix internals always see
 * our palette, (b) the 1.18× desktop scale is applied exactly once even when components
 * nest (a `Card` containing a `Button` must not scale twice).
 */
@Composable
internal fun MiuixScope(colors: SkinColors, content: @Composable () -> Unit) {
    val baseDensity = LocalDensity.current
    val density = if (MiuixPlatform.isDesktop && !MiuixDensityApplied.current) {
        Density(density = baseDensity.density * MiuixTokens.DESKTOP_DENSITY_SCALE, fontScale = baseDensity.fontScale)
    } else {
        baseDensity
    }
    CompositionLocalProvider(
        LocalDensity provides density,
        MiuixDensityApplied provides true,
    ) {
        MiuixTheme(
            colors = MiuixTokens.miuixColors(colors),
            // Keyed on the seam generation: an install that lands *after* this subtree was
            // first composed rebuilds the Miuix text styles too (CMP does not repaint when
            // glyph bytes merely arrive — see `VadeFonts.generation`).
            textStyles = remember(VadeFonts.generation) { MiuixTokens.miuixTextStyles() },
            content = content,
        )
    }
}

/** Guards [MiuixScope]'s density compensation against double-scaling in nested components. */
internal val MiuixDensityApplied = androidx.compose.runtime.staticCompositionLocalOf { false }

internal expect val MiuixPlatform_isDesktop: Boolean

internal object MiuixPlatform {
    val isDesktop: Boolean get() = MiuixPlatform_isDesktop
}
