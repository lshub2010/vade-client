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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.math.pow

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

/** Layer count of the no-MaskFilter fallback (overlapping filled outline copies). */
internal const val HALO_FALLBACK_LAYERS = 40

/**
 * Blur ink multiplier. The gaussian's visible below-edge peak is
 * Φ(offset/σ)·ink (offset 5dp, σ 5dp → ≈0.84·ink), so the ink is raised by this
 * factor to keep the *measured* peak — not the nominal ink — inside the ≤3 %
 * (≤8/255) budget.
 */
internal const val HALO_BLUR_INK_FACTOR = 1.2f

/** σ of the halo gaussian as a fraction of [radius] (50 %→5 % falloff spans ≈1.6σ). */
internal const val HALO_BLUR_SIGMA_FACTOR = 0.5f

/** Rings of the press-state inner shadow (a stepped approximation, fill only). */
private const val INNER_SHADOW_RINGS = 12

/**
 * Per-layer alpha so that [layers] fully-overlapping copies accumulate to exactly
 * [totalStrength]: `1 − (1 − strength)^(1/layers)`. At the pill's 0.03 strength
 * over 40 layers this is ≈0.00075 — each layer's own antialiased edge steps
 * ≈0.19/255, far below the 2-gray-level staircase bound.
 */
internal fun haloLayerAlpha(totalStrength: Float, layers: Int): Float =
    1f - (1f - totalStrength.coerceIn(0f, 1f)).pow(1f / layers)

/**
 * Cumulative alpha at a point covered by [layersCovering] of [layers] overlapping
 * halo layers: `1 − (1 − haloLayerAlpha) ^ layersCovering`. Non-increasing as
 * coverage drops, peaks at exactly [totalStrength] when everything covers, and is
 * 0 when nothing does — the monotonic, budget-exact falloff the acceptance
 * criteria measure.
 */
internal fun haloCumulativeAlpha(totalStrength: Float, layersCovering: Int, layers: Int): Float =
    1f - (1f - haloLayerAlpha(totalStrength, layers)).pow(layersCovering.coerceAtLeast(0))

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
 * A soft halo drawn **outside** the shape — the float cue for translucent
 * surfaces. `Modifier.shadow` paints an elevation silhouette *under* the shape,
 * which bleeds through a 40 % glass tint and turns the pill grey. (KernelSU uses
 * `Modifier.dropShadow`, which this Compose version does not ship.)
 *
 * g-float: **real gaussian blur first** — one blurred draw of the offset outline
 * ([haloBlurSupported]); no rings anywhere, so the shadow simply fades out (the
 * user: 「阴影为什么有边框，不应该直接淡出了吗」). Platforms without a working
 * MaskFilter fall back to [HALO_FALLBACK_LAYERS] overlapping **filled** copies of
 * the offset-dilated outline, each at [haloLayerAlpha]: coverage accumulates into
 * a smooth monotonic ramp — ≈0.19 gray-level steps and 0.25dp per layer at the
 * pill's 10dp radius, far under the 2-level staircase bound.
 *
 * The ORIGINAL construction is documented so it never comes back: 12 disjoint
 * ring-difference bands (`outer − inner`) that cannot accumulate, with the alpha
 * ramp INVERTED — the outermost ring carried the full strength and stepped
 * ~25/255 against the canvas. The user first saw it as a hard-edged grey slab
 * (「一坨灰块」, corners too square to hug the pill — the dilated-corner clamp),
 * then as 「阴影有边框」. Drawn BEFORE the content: solid consumers cover the
 * blur's inner half entirely; translucent glass shows it as a soft depth cue,
 * and the offset's below-edge mass hugs the pill instead of detaching from it.
 */
fun Modifier.dropHalo(
    shape: Shape,
    radius: Dp,
    color: Color,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    alpha: Float = 1f,
): Modifier = this.drawWithContent {
    val strength = alpha.coerceIn(0f, 1f)
    if (strength <= 0.01f || radius <= 0.dp) {
        drawContent()
        return@drawWithContent
    }

    val radiusPx = radius.toPx()
    val dx = offset.x.toPx()
    val dy = offset.y.toPx()
    val outline = shape.createOutline(size, layoutDirection, this)

    if (outline is Outline.Rounded) {
        // One gaussian-blurred draw. σ = radius·[HALO_BLUR_SIGMA_FACTOR]; the ink
        // carries [HALO_BLUR_INK_FACTOR] because the visible below-edge peak is
        // Φ(offset/σ)·ink < ink — the worst case still lands inside the ≤3 %
        // (≤8/255) budget. offsetX stays 0 (§8.3: light from straight above).
        val rr = outline.roundRect
        val drew = drawBlurredHalo(
            left = rr.left,
            top = rr.top,
            right = rr.right,
            bottom = rr.bottom,
            cornerRadius = dilatedCornerRadius(
                originalRadius = rr.topLeftCornerRadius.x,
                width = rr.width,
                height = rr.height,
                dilation = 0f,
            ),
            dx = dx,
            dy = dy,
            colorArgb = color.copy(alpha = (strength * HALO_BLUR_INK_FACTOR).coerceAtMost(1f)).toArgb(),
            blurSigmaPx = radiusPx * HALO_BLUR_SIGMA_FACTOR,
        )
        if (drew) {
            drawContent()
            return@drawWithContent
        }
    }

    val layerAlpha = haloLayerAlpha(strength, HALO_FALLBACK_LAYERS)
    val step = radiusPx / HALO_FALLBACK_LAYERS
    for (k in HALO_FALLBACK_LAYERS downTo 1) {
        drawPath(offsetDilatedOutline(outline, dx, dy, step * k), color.copy(alpha = layerAlpha))
    }
    drawContent()
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
        val step = if (radiusPx > 0f) radiusPx / INNER_SHADOW_RINGS else 0f
        for (k in INNER_SHADOW_RINGS downTo 1) {
            val dilate = step * k
            val punch = offsetDilatedOutline(
                outline = outline,
                dx = offset.x,
                dy = offset.y,
                dilation = dilate,
            )
            val band = Path().apply { op(clip, punch, PathOperation.Difference) }
            val ringAlpha = alpha / INNER_SHADOW_RINGS
            // g-float: fill only. The old extra `Stroke(width = dilate)` pass was
            // painting a crisp inner border around each band — read by the user as
            // 「阴影有边框」. The remaining uniform-alpha rings are a documented
            // approximation of the press-state cue (see the KDoc), not a fade.
            drawPath(band, color.copy(alpha = ringAlpha))
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
 * Corner radius of a rounded rect inflated by [dilation]: the radius grows with the
 * dilation (a pill — radius == height/2 — inflates into a perfect capsule again:
 * radius == dilatedHeight / 2) and is capped by the dilated rect's half-extent.
 */
internal fun dilatedCornerRadius(
    originalRadius: Float,
    width: Float,
    height: Float,
    dilation: Float,
): Float = (originalRadius + dilation).coerceIn(
    0f,
    minOf(width + 2 * dilation, height + 2 * dilation) / 2f,
)

/**
 * The punch-out path: [outline] translated by (dx, dy) and inflated by
 * [dilation]. Exact for rectangles and corner-based shapes (rounded rect /
 * circle); a generic outline is translated only and the dilation is dropped
 * (documented limitation — the pill only ever uses rounded shapes).
 */
internal fun offsetDilatedOutline(
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
        // g-float: the corner cap must be sized from the DILATED rect and must grow
        // with the dilation. The old code capped the radius at the ORIGINAL
        // half-extent (`minOf(rr.width, rr.height) / 2f`), so inflating a pill
        // (radius == height/2) kept the old radius while the edges grew — the halo
        // came out as a non-concentric, too-square slab (the user's
        // 「圆角太小了根本不贴合」). See [dilatedCornerRadius].
        val radius = dilatedCornerRadius(
            originalRadius = rr.topLeftCornerRadius.x,
            width = rr.width,
            height = rr.height,
            dilation = dilation,
        )
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
