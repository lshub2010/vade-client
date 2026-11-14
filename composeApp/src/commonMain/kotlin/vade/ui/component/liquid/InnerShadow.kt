// Ported from KernelSU (https://github.com/tiann/KernelSU),
// manager/app/src/main/java/me/weishu/kernelsu/ui/component/liquid/InnerShadow.kt,
// Copyright (c) 2023-2026 weishu & contributors, Apache License 2.0.
// Original: Kyant0/AndroidLiquidGlass via the compose-miuix-ui example
// `IosLiquidGlassNavigationBar`. Same Apache License 2.0 terms; see README.md.
//
// w2-bottombar port note: KernelSU's version blurs a shadow crescent through a
// GraphicsLayer RenderEffect (BlurEffect). That path is platform-risky on wasm,
// so this port is a **pure Compose canvas implementation** (drawWithContent +
// Path, no RenderEffect, no GraphicsLayer, no blend-mode layers): the soft
// falloff of [InnerShadow.radius] is approximated by N nested "ring" difference
// paths with a linear alpha ramp instead of a true Gaussian blur.

package vade.ui.component.liquid

import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * A shadow cast *inward* from the shape's edge — the pressed-state cue of the
 * floating pill.
 *
 * @param radius Softness of the shadow's inner edge (dp). The KernelSU original
 *   blurs the crescent by this radius; here it drives the width of a stepped
 *   alpha ramp (see file header for why).
 * @param offset Where the "light" punches the shadow from, in dp. The default
 *   `(0, radius)` leaves the crescent along the *top* edge, matching KernelSU.
 * @param color Shadow colour.
 * @param alpha Overall strength; 0 disables the draw entirely.
 */
@Immutable
data class InnerShadow(
    val radius: Dp = 24.dp,
    val offset: DpOffset = DpOffset(0.dp, radius),
    val color: Color = Color.Black.copy(alpha = 0.15f),
    val alpha: Float = 1f,
) {
    companion object {
        val Default: InnerShadow = InnerShadow()
    }
}

/** Number of falloff rings approximating a Gaussian of [InnerShadow.radius]. */
private const val RING_STEPS = 12

fun Modifier.innerShadow(
    shape: Shape,
    shadow: () -> InnerShadow?,
): Modifier = this.drawWithContent {
    drawContent()
    val spec = shadow() ?: return@drawWithContent
    if (spec.alpha <= 0.01f) return@drawWithContent

    val radiusPx = spec.radius.toPx().coerceAtLeast(0f)
    val dx = spec.offset.x.toPx()
    val dy = spec.offset.y.toPx()
    if (radiusPx <= 0.5f && dx == 0f && dy == 0f) {
        // No softness and no offset: nothing visible to draw.
        return@drawWithContent
    }

    drawInnerShadow(
        shape = shape,
        radiusPx = radiusPx,
        offset = Offset(dx, dy),
        color = spec.color,
        alpha = spec.alpha.coerceIn(0f, 1f),
    )
}
/**
 * A soft halo drawn **only outside** the shape — the float cue for translucent
 * surfaces. `Modifier.shadow` paints an elevation silhouette *under* the shape,
 * which bleeds through a 40 % glass tint and turns the pill grey; the ring bands
 * here stop exactly at the shape edge. Same stepped-falloff technique as the
 * inner shadow, mirrored outward. (KernelSU uses `Modifier.dropShadow`, which
 * this Compose version does not ship.)
 */
fun Modifier.dropHalo(
    shape: Shape,
    radius: Dp,
    color: Color,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    alpha: Float = 1f,
): Modifier = this.drawWithContent {
    drawContent()
    val strength = alpha.coerceIn(0f, 1f)
    if (strength <= 0.01f || radius <= 0.dp) return@drawWithContent

    val radiusPx = radius.toPx()
    val dx = offset.x.toPx()
    val dy = offset.y.toPx()
    val outline = shape.createOutline(size, layoutDirection, this)

    val step = radiusPx / RING_STEPS
    for (k in RING_STEPS downTo 1) {
        val outer = offsetDilatedOutline(outline, dx, dy, step * k)
        val inner = offsetDilatedOutline(outline, dx, dy, step * (k - 1))
        val band = Path().apply { op(outer, inner, PathOperation.Difference) }
        // Closest band strongest: linear ramp up to the full strength at the edge.
        drawPath(band, color.copy(alpha = strength * k / RING_STEPS))
    }
}

/**
 * Stepped-falloff inner shadow. Ring `k` is the band between the shape edge and
 * the shape translated by [offset] and inflated by `radius * k / steps`; each
 * ring contributes `alpha / steps`, so the innermost band accumulates the full
 * strength while the outer bands fade — a linear ramp that reads as blur at the
 * 4–8 dp radii the pill uses.
 */
private fun ContentDrawScope.drawInnerShadow(
    shape: Shape,
    radiusPx: Float,
    offset: Offset,
    color: Color,
    alpha: Float,
) {
    val outline = shape.createOutline(size, layoutDirection, this)
    val clip = outlineAsPath(outline)

    drawIntoShadowCanvas(clip) {
        val step = if (radiusPx > 0f) radiusPx / RING_STEPS else 0f
        for (k in RING_STEPS downTo 1) {
            val dilate = step * k
            val punch = offsetDilatedOutline(
                outline = outline,
                dx = offset.x,
                dy = offset.y,
                dilation = dilate,
            )
            val band = Path().apply { op(clip, punch, PathOperation.Difference) }
            val ringAlpha = alpha / RING_STEPS
            if (dilate > 0f) {
                // Stroke *and* fill: the stroke covers the band's edge cleanly at
                // small radii where 12 rings would otherwise show steps.
                drawPath(band, color.copy(alpha = ringAlpha))
                drawPath(
                    band,
                    color.copy(alpha = ringAlpha),
                    style = Stroke(width = dilate.coerceAtLeast(1f)),
                )
            } else {
                drawPath(band, color.copy(alpha = ringAlpha))
            }
        }
    }
}

/** Runs [block] with the canvas clipped to [clip] — the shadow never escapes the shape. */
private inline fun ContentDrawScope.drawIntoShadowCanvas(
    clip: Path,
    block: ContentDrawScope.() -> Unit,
) {
    drawContext.canvas.save()
    drawContext.canvas.clipPath(clip)
    try {
        block()
    } finally {
        drawContext.canvas.restore()
    }
}

private fun outlineAsPath(outline: Outline): Path =
    when (outline) {
        is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
        is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
        is Outline.Generic -> outline.path
    }

/**
 * The punch-out path: [outline] translated by (dx, dy) and inflated by
 * [dilation]. Exact for rectangles and corner-based shapes (rounded rect /
 * circle); a generic outline is translated only and the dilation is dropped
 * (documented limitation — the pill only ever uses rounded shapes).
 */
private fun offsetDilatedOutline(
    outline: Outline,
    dx: Float,
    dy: Float,
    dilation: Float,
): Path = when (outline) {
    is Outline.Rectangle -> {
        val r = Rect(
            outline.rect.left + dx - dilation,
            outline.rect.top + dy - dilation,
            outline.rect.right + dx + dilation,
            outline.rect.bottom + dy + dilation,
        )
        Path().apply { addRect(r) }
    }

    is Outline.Rounded -> {
        val rr = outline.roundRect
        val maxRadius = minOf(rr.width, rr.height) / 2f
        val radius = (rr.topLeftCornerRadius.x + dilation).coerceIn(0f, maxRadius)
        Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    rect = Rect(
                        rr.left + dx - dilation,
                        rr.top + dy - dilation,
                        rr.right + dx + dilation,
                        rr.bottom + dy + dilation,
                    ),
                    topLeft = androidx.compose.ui.geometry.CornerRadius(radius),
                    topRight = androidx.compose.ui.geometry.CornerRadius(radius),
                    bottomRight = androidx.compose.ui.geometry.CornerRadius(radius),
                    bottomLeft = androidx.compose.ui.geometry.CornerRadius(radius),
                ),
            )
        }
    }

    is Outline.Generic -> {
        // Dilation is not well-defined for arbitrary paths; translate only.
        Path().apply {
            addPath(outline.path)
            if (dx != 0f || dy != 0f) translate(Offset(dx, dy))
        }
    }
}
