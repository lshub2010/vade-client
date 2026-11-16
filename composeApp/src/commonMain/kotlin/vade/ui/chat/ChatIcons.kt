package vade.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import vade.ui.skin.ICON_PRESS_SCALE
import vade.ui.skin.LocalReduceMotion
import vade.ui.skin.LocalSkin
import vade.ui.skin.ReduceMotion

/**
 * v2 §3 · 图标即按钮 — the page's glyph vocabulary, drawn with `Canvas` strokes.
 *
 * Why drawn and not font glyphs: `↑ ＋ ⋯ ✕` render differently per platform font, and the
 * visual language pins one stroke weight per screen ("禁止同一屏混用两套线宽"). All six
 * glyphs (发送 ↑ / 停止 ■ / 附件 ＋ / 更多 ⋯ / 返回 ‹ / 关闭 ✕) share a single stroke
 * width derived from the canvas size, so every icon on the page optically matches.
 *
 * `⋯` / `‹` are listed here for completeness (the shell top bar owns where they appear);
 * this page itself only composes ↑ ■ ＋ ✕ ⏷. **No text buttons appear on this page** —
 * every action is one of these glyphs inside [ChatIconButton].
 */
enum class ChatIcon { SendUp, Stop, Plus, Close, More, Back, Check, ChevronDown }

/** One stroke weight for every glyph: 2dp at the standard 20dp box, scaling with size. */
private fun DrawScope.strokeWidth(): Float = size.minDimension * 0.10f

private fun DrawScope.line(from: Offset, to: Offset, color: Color) {
    drawLine(color, from, to, strokeWidth = strokeWidth(), cap = StrokeCap.Round)
}

/** ↑ — send. Vertical shaft with a round-capped chevron head. */
fun DrawScope.paintSendUp(color: Color) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    line(Offset(cx, h * 0.84f), Offset(cx, h * 0.20f), color)
    line(Offset(cx, h * 0.20f), Offset(cx - w * 0.26f, h * 0.46f), color)
    line(Offset(cx, h * 0.20f), Offset(cx + w * 0.26f, h * 0.46f), color)
}

/** ■ — stop. Filled rounded square (§2: 方形图标), not a red ball. */
fun DrawScope.paintStop(color: Color) {
    val w = size.width
    drawRoundRect(
        color = color,
        topLeft = Offset(w * 0.20f, w * 0.20f),
        size = Size(w * 0.60f, w * 0.60f),
        cornerRadius = CornerRadius(w * 0.10f),
    )
}

/** ＋ — attach. */
fun DrawScope.paintPlus(color: Color) {
    val w = size.width
    val cx = w / 2f
    line(Offset(cx, w * 0.18f), Offset(cx, w * 0.82f), color)
    line(Offset(w * 0.18f, cx), Offset(w * 0.82f, cx), color)
}

/** ✕ — close / deny. */
fun DrawScope.paintClose(color: Color) {
    val w = size.width
    line(Offset(w * 0.20f, w * 0.20f), Offset(w * 0.80f, w * 0.80f), color)
    line(Offset(w * 0.80f, w * 0.20f), Offset(w * 0.20f, w * 0.80f), color)
}

/** ⋯ — more (held for shell parity; not composed on this page yet). */
fun DrawScope.paintMore(color: Color) {
    val w = size.width
    val cy = size.height / 2f
    val r = strokeWidth() / 2f
    listOf(0.24f, 0.5f, 0.76f).forEach { x ->
        drawCircle(color, radius = r, center = Offset(w * x, cy))
    }
}

/** ‹ — back (held for shell parity; not composed on this page yet). */
fun DrawScope.paintBack(color: Color) {
    val w = size.width
    val h = size.height
    line(Offset(w * 0.62f, h * 0.20f), Offset(w * 0.34f, h * 0.5f), color)
    line(Offset(w * 0.34f, h * 0.5f), Offset(w * 0.62f, h * 0.80f), color)
}

/** ⏷ — expand approval detail (§4 卡尾). */
fun DrawScope.paintChevronDown(color: Color) {
    val w = size.width
    val h = size.height
    line(Offset(w * 0.24f, h * 0.38f), Offset(w * 0.5f, h * 0.66f), color)
    line(Offset(w * 0.5f, h * 0.66f), Offset(w * 0.76f, h * 0.38f), color)
}

/** ✓ — approve (§4 卡尾), drawn as a check so the deny ✕ never reads ambiguously. */
fun DrawScope.paintCheck(color: Color) {
    val w = size.width
    val h = size.height
    line(Offset(w * 0.20f, h * 0.52f), Offset(w * 0.42f, h * 0.74f), color)
    line(Offset(w * 0.42f, h * 0.74f), Offset(w * 0.80f, h * 0.28f), color)
}

fun DrawScope.paint(icon: ChatIcon, color: Color) {
    when (icon) {
        ChatIcon.SendUp -> paintSendUp(color)
        ChatIcon.Stop -> paintStop(color)
        ChatIcon.Plus -> paintPlus(color)
        ChatIcon.Close -> paintClose(color)
        ChatIcon.More -> paintMore(color)
        ChatIcon.Back -> paintBack(color)
        ChatIcon.Check -> paintCheck(color)
        ChatIcon.ChevronDown -> paintChevronDown(color)
    }
}

// --- K2 · press feedback ------------------------------------------------------

/** 按下时图标色的不透明度（不动色相：亮/暗两套皮肤都读得出「按下去了」）。 */
internal const val ICON_PRESSED_TINT_ALPHA = 0.72f

/** 按下时垫在图标下面的圆形 tint 底的不透明度 —— 触摸屏没有 hover，纯色变太轻。 */
internal const val ICON_PRESSED_DISC_ALPHA = 0.10f

/**
 * K2 · 一枚图标按钮的按压数值。
 *
 * 三个数一起定义，两个按钮（[ChatIconButton] / [ChatVectorIconButton]）和所有调用点共用，
 * 因此批准 ✓ / 拒绝 ✕ / 重试 ↻ / 停止 ■（发送球，[vade.ui.skin.SkinOrb.pressScale]）/ 编辑 ✎
 * 的按压比是同一个 0.92 —— 一屏不许出现两种按压比（§2/§7）。
 */
internal data class IconPressSpec(
    /** 整枚按钮的缩放（1f = 静止）。 */
    val scale: Float,
    /** 图标 tint 的不透明度倍数（1f = 原色）。 */
    val tintAlpha: Float,
    /** 圆形底的不透明度（0f = 不画）。 */
    val discAlpha: Float,
)

/** 纯函数：按下且有启用 → 三个数一起变；否则完全静止。`ChatMotionTest` 直接钉死。 */
internal fun iconPressSpec(
    pressed: Boolean,
    enabled: Boolean,
    pressScale: Float = ICON_PRESS_SCALE,
): IconPressSpec =
    if (pressed && enabled) {
        IconPressSpec(scale = pressScale, tintAlpha = ICON_PRESSED_TINT_ALPHA, discAlpha = ICON_PRESSED_DISC_ALPHA)
    } else {
        IconPressSpec(scale = 1f, tintAlpha = 1f, discAlpha = 0f)
    }

/**
 * K2 的渲染侧：一枚 ≥44dp 圆形触摸区 + 按压缩放/染色 + [contentDescription]。
 *
 * 为什么不是 ripple：v2 的按压反馈是 scale/colour（`indication = null`，§3），而 K2 的缺陷
 * 是**连 scale 都没有** —— 点下去零反馈。这里把 [IconPressSpec] 接上
 * [androidx.compose.foundation.interaction.InteractionSource]，时长读
 * [vade.ui.skin.SkinMotion]（`quickMs`），并按 K1 在 reduced-motion 下封顶 100ms。
 *
 * [glyph] 由调用方决定画什么（Canvas 描边 / `VadeIcon` 矢量），颜色是已经算好的 tint。
 */
@Composable
private fun PressableIconButton(
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    boxSize: Dp,
    tint: Color,
    glyph: @Composable (Color) -> Unit,
) {
    val skin = LocalSkin.current
    val reduceMotion = LocalReduceMotion.current || ReduceMotion.enabled
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val spec = iconPressSpec(pressed = pressed, enabled = enabled, pressScale = skin.orb.pressScale)
    val pressMs = skin.motion.durationMs(skin.motion.quickMs, reduceMotion)
    val scale by animateFloatAsState(
        targetValue = spec.scale,
        animationSpec = tween(durationMillis = pressMs, easing = skin.motion.easing),
        label = "chatIconPressScale",
    )
    val tintAlpha by animateFloatAsState(
        targetValue = spec.tintAlpha,
        animationSpec = tween(durationMillis = pressMs, easing = skin.motion.easing),
        label = "chatIconPressTint",
    )
    Box(
        modifier
            .size(boxSize)
            // 缩放挂在 graphicsLayer 上：只动绘制，不动布局（44dp 触摸区永远不会缩水）。
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .semantics { this.contentDescription = contentDescription }
            .clip(CircleShape)
            .background(tint.copy(alpha = spec.discAlpha))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        glyph(tint.copy(alpha = tint.alpha * tintAlpha))
    }
}

/**
 * A pure-icon button (v2 §3): glyph canvas inside a **≥44dp touch target** (the visual box
 * and the hit box are the same 44dp circle), with a mandatory [contentDescription].
 *
 * `indication = null` — the press feedback on v2 controls is scale/colour, not a ripple:
 * 0.92 scale + a 10% tint disc + a 72% tint over `motion.quickMs` (K2; reduced-motion caps
 * that at 100ms and the scale is the only movement left).
 */
@Composable
fun ChatIconButton(
    icon: ChatIcon,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = LocalSkin.current.colors.onSurfaceVariant,
    boxSize: Dp = 44.dp,
    iconSize: Dp = 20.dp,
) {
    PressableIconButton(
        contentDescription = contentDescription,
        enabled = enabled,
        onClick = onClick,
        modifier = modifier,
        boxSize = boxSize,
        tint = tint,
    ) { color ->
        Canvas(Modifier.size(iconSize)) { paint(icon, color) }
    }
}

/** Convenience for a small tinted glyph that is *not* itself a button (badge rows, tails). */
@Composable
fun ChatIconGlyph(
    icon: ChatIcon,
    tint: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = 16.dp,
) {
    Box(modifier.size(iconSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(iconSize)) { paint(icon, tint) }
    }
}

/**
 * Fills [box] with the glyph, for call sites that already own the Box sizing.
 */
@Composable
fun ChatIconCanvas(icon: ChatIcon, tint: Color, box: Modifier = Modifier) {
    Canvas(box) { paint(icon, tint) }
}

/**
 * Vector-icon button (visual-language §3.1: for new call sites the ONLY allowed icon form
 * is an `ImageVector` from `vade.ui.icon`) — same contract as [ChatIconButton]: a ≥44dp
 * circular touch target, a mandatory [contentDescription] on the box, `indication = null`
 * and the same K2 press feedback (0.92 scale + tint, over `motion.quickMs`, capped by
 * reduced-motion). Used for the v1.1 actions (编辑 / 重新生成 / 回形针 / 确认 / 取消).
 */
@Composable
fun ChatVectorIconButton(
    vector: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = LocalSkin.current.colors.onSurfaceVariant,
    boxSize: Dp = 44.dp,
    iconSize: Dp = 20.dp,
) {
    PressableIconButton(
        contentDescription = contentDescription,
        enabled = enabled,
        onClick = onClick,
        modifier = modifier,
        boxSize = boxSize,
        tint = tint,
    ) { color ->
        vade.ui.icon.VadeIcon(
            vector = vector,
            contentDescription = null, // the box carries the label — keep a single a11y node
            size = iconSize,
            tint = color,
        )
    }
}
