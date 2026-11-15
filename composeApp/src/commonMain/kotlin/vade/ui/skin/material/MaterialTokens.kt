package vade.ui.skin.material

import androidx.compose.ui.graphics.Color
import vade.ui.skin.SkinColors

/**
 * `material` design tokens — light AND dark (docs/visual-language.md §2).
 *
 * Same structure as `MiuixTokens`: dark is a second authored value set over the same
 * semantic names, **not** a tinted light theme. Derivation rules, all from the visual
 * language (the single source this file must track):
 *
 *  - §2 暗色表: graphite neutrals `#0E0E10` canvas / `#161618` surface / `#2A2A2E`
 *    hairline / `#EDEDF0` text / `#9A9AA3` muted / accent **lifted** to `#8B8BF0`;
 *  - single indigo accent (§1): the accent lightens in dark instead of changing hue —
 *    never Material's banned default purple or a tonal derivative (§七.5);
 *  - status colours keep their hue and lift for dark (same derivation MiuixTokens uses
 *    for `success(dark)`/`warn(dark)`: `#2F7D5A`→`#4FD1A0`, `#B26A00`→`#E8A33D`);
 *  - on-accent text flips to dark in dark mode: white on `#8B8BF0` is 2.9:1, the
 *    canvas black is 6.5:1 (§2 对比度要求 ≥4.5:1);
 *  - §5 glass: the real-blur tint is `#0E0E10`@34% (pinned on [SkinGlass.tintDark] by
 *    [MaterialSkin.glass]); the *degrade* base (no HazeState wired) uses the §2
 *    `surfaceRaised` tone `#1C1C1F`@78% — `#0E0E10`@34% on the `#0E0E10` canvas is
 *    invisible as a field, same reasoning as MiuixTokens.darkColors.surfaceGlass;
 *  - §8 dark shadows: alpha ×1.8 (30%→54%, 50%→90%) and blur ×1.2 (applied at the
 *    `Modifier.shadow` call sites in `MaterialSkin`, not here — blur is a radius, not a
 *    colour token).
 *
 * WCAG (computed, pinned by MaterialSkinDarkTest): dark text/canvas 16.5:1, muted 6.9:1,
 * accent-as-text 6.5:1, error-as-text 6.4:1 — all over the ≥4.5 floor.
 */
internal object MaterialTokens {

    // --- light -----------------------------------------------------------------

    /** Visual language §2 亮色表 — byte-stable: the light look is screenshot-baselined. */
    val lightColors = SkinColors(
        primary = Color(0xFF5B5BD6),
        onPrimary = Color(0xFFFFFFFF),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFF0F0F2), // surfaceSunken: code blocks, chips, inputs
        onSurface = Color(0xFF17171A),
        onSurfaceVariant = Color(0xFF6B6B73),
        outline = Color(0xFFE6E6E9), // hairline
        error = Color(0xFFD93A3A),
        onError = Color(0xFFFFFFFF),
        isDark = false,
        canvas = Color(0xFFF7F7F8),
        hairline = Color(0xFFE6E6E9),
        success = Color(0xFF2F7D5A),
        warning = Color(0xFFB26A00),
        // --- v2 §5/§8 glass + shadow tokens ---------------------------------------
        // surfaceGlass is a *thin* translucent base so the hairline + shadow carry the
        // float; the real backdrop blur is `skin/glass/GlassSurface` once shell/ wires the
        // root hazeSource (MaterialParts documents this at the composer).
        surfaceGlass = Color(0xFFFFFFFF).copy(alpha = 0.84f),
        // §8 row 1 static-card shadow rendered at 2dp elevation; row 2 float shadow at 16dp.
        shadowSoft = Color(0x4D000000), // black @30%  → ≈ 0 1 2 rgba(0,0,0,.06)
        shadowFloat = Color(0x80000000), // black @50% → ≈ 0 12 32 rgba(0,0,0,.18)
        glowAccent = Color(0xFF5B5BD6), // §8 row 3: the accent glow of a primary action
        textOnGlass = Color(0xFF17171A),
    )

    // --- dark ------------------------------------------------------------------

    /** Visual language §2 暗色表 + §5 tint + §8 ×1.8 shadows — see the KDoc above. */
    val darkColors = SkinColors(
        primary = Color(0xFF8B8BF0), // §2 accent（暗色下提亮，同一靛蓝色相）
        onPrimary = Color(0xFF0E0E10), // 白字在 #8B8BF0 上 2.9:1 不达标；深底字 6.5:1
        surface = Color(0xFF161618), // §2 卡片
        surfaceVariant = Color(0xFF0E0E10), // surfaceSunken：暗色下比 surface 更深（Miuix 同法）
        onSurface = Color(0xFFEDEDF0), // §2 主文本
        onSurfaceVariant = Color(0xFF9A9AA3), // §2 次要文本
        outline = Color(0xFF2A2A2E), // §2 发丝线
        error = Color(0xFFF16A6A), // §2 danger
        onError = Color(0xFF1A0503), // 白字在 #F16A6A 上 2.97:1 不达标；深底字 6.6:1
        isDark = true,
        canvas = Color(0xFF0E0E10), // §2 底色
        hairline = Color(0xFF2A2A2E),
        success = Color(0xFF4FD1A0), // #2F7D5A 提亮保色相（MiuixTokens.success(dark) 同一推导）
        warning = Color(0xFFE8A33D), // #B26A00 提亮保色相
        // --- v2 §5/§8 glass + shadow tokens, dark side -----------------------------
        // 退化底用 §2 surfaceRaised（#1C1C1F@78%）而不是 #0E0E10@34%：后者贴在
        // #0E0E10 画布上不可见，浮层会“消失”。#0E0E10@34% 是真模糊的 tint，钉在
        // MaterialSkin.glass.tintDark（GlassSurface 按 colors.isDark 取用）。
        surfaceGlass = Color(0xFF1C1C1F).copy(alpha = 0.78f),
        // §8: 暗色阴影 alpha ×1.8 —— 30%→54%、50%→90%（暗底上阴影要更深更散才看得见）。
        shadowSoft = Color(0x8A000000),
        shadowFloat = Color(0xE6000000),
        glowAccent = Color(0xFF8B8BF0),
        textOnGlass = Color(0xFFEDEDF0),
        // --- G5 · 附件预览浮层（2026-11-15）----------------------------------------
        // 预览卡底座在暗色下必须**不透明**：退化底 #1C1C1F@78% 让背后的正文（红字工具卡、
        // 审批卡）整行透过可读（审计 f25 实测）。改用 §2 卡片色 #161618 全实底 —— 底座本身
        // 隔绝背后内容，玻璃语义（发丝线 + 浮层阴影）不变。
        previewCardBase = Color(0xFF161618),
        // 遮罩 ≥48%（§9 亮色 24% 黑在 #0E0E10 画布上不可感知）。亮色不动（默认 24%）。
        previewScrim = Color.Black.copy(alpha = 0.56f),
    )

    fun colors(dark: Boolean): SkinColors = if (dark) darkColors else lightColors
}
