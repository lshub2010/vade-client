package vade.ui.skin.miuix

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.defaultTextStyles
import top.yukonga.miuix.kmp.theme.lightColorScheme
import vade.ui.skin.SkinColors
import vade.ui.skin.SkinMotion
import vade.ui.skin.SkinShapes
import vade.ui.skin.SkinTypography

/**
 * U2 · `miuix` design tokens.
 *
 * Everything here is skin-private: pages read colours only through `Skin.colors` (C-09) and
 * never through a Miuix type. The values are chosen to be *less* Material rather than
 * "Material in another palette" (task book §四):
 *
 *  - shapes follow the spec ladder exactly (8 / 12 / 16 / 20 / 28, see [shapes]);
 *  - the page background is a warm grey and surfaces are flat white — hierarchy comes from
 *    a 1 dp hairline, never from elevation (Miuix's own default `Card` has no surface at
 *    all, see spike/miuix RESULT.md §五 R5, so every container here paints its own colour
 *    *and* its own stroke);
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
    )

    fun colors(dark: Boolean): SkinColors = if (dark) darkColors else lightColors

    // --- shapes ----------------------------------------------------------------

    /**
     * Radius ladder. `ui-visual-language.md` §三 fixes the scale at **8 / 12 / 16 / 20 / 28** and
     * ties each step to a component class; a skin may change colours but *not* the ladder
     * (§八 验收 2: "同屏 ≤3 种；无 <8dp").
     *
     * `SkinShapes` carries only four slots, so the four live steps are mapped to the roles the
     * spec names, and the 8 dp step is kept as [radiusXs] for inline chips / thumbnails:
     *
     *   small  = 12 → 次要按钮、列表项、缩略图   (spec `sm`)
     *   medium = 16 → 卡片、输入框、工具卡       (spec `md`)
     *   large  = 20 → 用户气泡、面板             (spec `lg`)
     *   pill   = 999 → 主按钮、chip、状态药丸
     *
     * The previous revision used 8/12/16 ("one notch below material"). That read well on a phone
     * but broke the semantic contract — a card at 12 dp is the spec's *list row* radius, so a
     * miuix card and a miuix list row were the same shape class. Corrected to the spec ladder.
     */
    val shapes = SkinShapes(
        small = 12.dp,
        medium = 16.dp,
        large = 20.dp,
        pill = 999.dp,
    )

    /** Spec `xs` — 小标签、代码块内联、缩略图. The floor: nothing in this skin goes below 8 dp. */
    val radiusXs = 8.dp

    /**
     * Spec `xl` — 底部 sheet / 命令面板 / 全屏弹层. `SkinShapes` stops at `large`, so the two
     * steps above it (xl = 28) are skin-private constants rather than a widening of the frozen
     * C-09 token class.
     */
    val sheetRadius = 28.dp

    // --- type (spec §四) --------------------------------------------------------

    /**
     * `ui-visual-language.md` §四 fixes the type scale: 正文 15/400, 次要 13/400, 极小 11/500,
     * 列表标题 15/600, 页面标题 20/600.
     *
     * The previous revision ran a notch smaller (16/13/10) as a "compact density" delta. That
     * is not a delta the spec permits — 13 sp body copy on a 480 dp phone reads as dense
     * *and* fails the 4.5:1 perceptual check at a glance because the strokes get thin. Corrected
     * to the spec scale; density is now expressed through row height and padding, not type size.
     */
    val typography = SkinTypography(
        title = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp),
        body = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
        caption = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
        mono = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace, lineHeight = 20.sp),
    )

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

    /**
     * Desktop density compensation (task book §T3): Miuix is laid out for phones, so at the
     * same dp the controls look undersized on a 1280×800 window. 1.18 was picked by
     * comparing the 480×900 and 1280×800 shots of the same page (see README §桌面密度).
     */
    const val DESKTOP_DENSITY_SCALE = 1.18f

    // --- Miuix theme bridge -----------------------------------------------------

    /**
     * Maps the semantic [SkinColors] onto Miuix's much wider `Colors` bag. Only the slots
     * the eleven components can actually reach are filled; the rest keep Miuix's defaults so
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
            textStyles = defaultTextStyles(),
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
