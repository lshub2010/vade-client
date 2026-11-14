package vade.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import vade.ui.skin.LocalSkin

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

/**
 * A pure-icon button (v2 §3): glyph canvas inside a **≥44dp touch target** (the visual box
 * and the hit box are the same 44dp circle), with a mandatory [contentDescription].
 *
 * `indication = null` — the press feedback on v2 controls is scale/colour, not a ripple.
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
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(boxSize)
            .semantics { this.contentDescription = contentDescription }
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(iconSize)) { paint(icon, tint) }
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
 * press feedback. Used for the v1.1 actions (编辑 / 重新生成 / 回形针 / 确认 / 取消).
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
    Box(
        modifier
            .size(boxSize)
            .semantics { this.contentDescription = contentDescription }
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        vade.ui.icon.VadeIcon(
            vector = vector,
            contentDescription = null, // the box carries the label — keep a single a11y node
            size = iconSize,
            tint = tint,
        )
    }
}
