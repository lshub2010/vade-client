// Downward-only gradient shelf — the shared shadow primitive behind §8 float layers
// (docs/visual-language.md §8: 多层渐变、有方向、上方无影).
//
// Why a gradient band and not a blurred silhouette — g-shadow-real measured all of
// these at ZERO pixels on emulator-5554's hardware canvas (shots/g-shadow-real/RESULT.md):
//   * Compose `clipPath(Difference)` around the BlurMaskFilter draw  → 0 px
//   * Compose clip + outer saveLayer                                 → 0 px
//   * native `clipPath(hole, Region.Op.DIFFERENCE)`                  → 0 px
//   * native saveLayer + `PorterDuff.CLEAR` punch                    → 0 px
//   * Compose `BlendMode.Clear/DstOut` punch (worse)                 → whole layer erased
//   * 40-layer filled fallback (α ≈ 0.0004/layer)                    → 8-bit quantised to 0
// BlurMaskFilter itself DOES paint (same build, static cards show a 3/255 gradient),
// but every route that "clamps" the blur eats it. The shelf needs none of that: it is
// one vertical alpha gradient per band, drawn INSIDE a rounded path that lives strictly
// BELOW the element's bottom edge — a plain fill, no mask filter, no clip, no offscreen
// layer — so it lands pixels on any canvas Android can throw at it, and by construction:
//   * nothing is painted above the element (§8.3 上方不得有阴影 → D10's 10.7/255 band dies),
//   * nothing hugs the corners (Material's tessellated silhouette → D6's 1px step dies),
//   * the falloff is monotonic with sub-quantum per-pixel steps (无阶梯),
//   * the token's layers become stacked gradient bands (近层短促、远层绵长) — never one
//     flat slab (§8.5 禁止清单第一条).
//
// Quantisation is a first-class concern here (g-shadow-android 第 1.5 级): each band's
// alpha rounds to its own 8-bit quantum per pixel, so the *composite* peak lands on a
// coarse staircase — with the §8.2 float token's alpha ratios the achievable edge
// composites are {6, 9, 10}/255 and **8 is unreachable** by scale alone. 8 is exactly
// the cell both acceptance gates share (Android 峰值 ≤ 8/255; desktop
// `ComposerShadowShapeTest.below >= 8`), so [shelfBands] trims the darkest band one
// quantum at a time until the quantised composite fits the ≤3% budget — float lands on
// (3,3,2)/255 with all three bands alive.

package vade.ui.component.liquid

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Ink multiplier from the §8.2 token's nominal alpha to a band's α₀ (pre-trim). */
internal const val SHELF_INK_SCALE = 0.266f

/**
 * The composite-peak budget in 8-bit quanta: ≤3% (≤8/255) at the element's bottom edge.
 * 8.49 is the largest real whose screen value still reads 8 (the quantiser rounds).
 */
internal const val SHELF_PEAK_BUDGET_QUANTA = 8.49f

/** One gradient band: a rounded rect below the element, α₀ at its top fading to 0. */
internal data class ShelfBand(
    val heightPx: Float,
    val radiusPx: Float,
    val alpha0: Float,
)

/**
 * Composite ink at [depthPx] below the element's bottom edge (source-over stack) —
 * pure math, the guard tests' substrate.
 */
internal fun shelfInkAt(bands: List<ShelfBand>, depthPx: Float): Float {
    var keep = 1f
    for (band in bands) {
        val t = (depthPx / band.heightPx).coerceIn(0f, 1f)
        keep *= 1f - band.alpha0 * (1f - t)
    }
    return 1f - keep
}

/**
 * The **quantised** composite peak — what an 8-bit surface actually shows at the
 * element's bottom edge, one quantum per band (the gradient rounds per pixel).
 */
internal fun shelfQuantizedPeak(bands: List<ShelfBand>): Float {
    var keep = 1f
    for (band in bands) {
        keep *= 1f - (band.alpha0 * 255f).roundToInt().coerceIn(0, 255) / 255f
    }
    return 1f - keep
}

/** Deepest ink the shelf casts — right at the element's bottom edge. */
internal fun shelfPeakBelow(bands: List<ShelfBand>): Float = shelfQuantizedPeak(bands)

/**
 * Ink above the element's top edge. The shelf paints strictly below the element's
 * bottom edge, so this is 0 by construction; this function keeps the *contract* the
 * guard test asserts — if the engine ever grows an upward tail, it must be modelled
 * here and `DownwardShelfMathTest` will fire.
 */
internal fun shelfLeakAbove(bands: List<ShelfBand>): Float = 0f

/** Band count — the §8.1 「多层渐变叠加」 contract (never one slab). */
internal fun shelfBandCount(bands: List<ShelfBand>): Int = bands.size

/**
 * The shelf geometry for [layers] at [pxPerDp]. Per §8.2 layer k:
 * height = offsetY + 3σ (σ = blur·[HALO_BLUR_SIGMA_FACTOR] — the gaussian's visible
 * extent, kept so the shelf spans the same ground the blurred token would have) and
 * α₀ = alpha·[inkScale]. The band's corner follows the element's outline radius,
 * clamped to half the band height so Skia never has to rescale the radii (a rescale
 * would square the band's top corners off against the element's curl).
 *
 * Light theme: the *quantised* composite peak must fit [SHELF_PEAK_BUDGET_QUANTA]
 * (the ≤3% budget's largest 8-bit-representable value), so the darkest band is trimmed
 * one quantum at a time until it does — the peak comes off the top, the falloff keeps
 * its shape. Dark theme skips the trim: the budget there is on the *visible delta*
 * (ink × the #0E0E10 canvas), which the token's ×1.8 stack never approaches.
 */
internal fun shelfBands(
    layers: List<ShadowLayer>,
    outlineRadiusPx: Float,
    pxPerDp: Float,
    inkScale: Float = SHELF_INK_SCALE,
    dark: Boolean = false,
    trimToBudget: Boolean = true,
): List<ShelfBand> {
    val bands = layers.map { layer ->
        val sigma = layer.blur.value * HALO_BLUR_SIGMA_FACTOR
        val heightPx = ((layer.offsetY.value + 3f * sigma) * pxPerDp).coerceAtLeast(1f)
        ShelfBand(
            heightPx = heightPx,
            radiusPx = outlineRadiusPx.coerceAtLeast(0f).coerceAtMost(heightPx / 2f),
            alpha0 = layer.alpha * inkScale,
        )
    }
    if (!trimToBudget || dark || bands.isEmpty()) return bands

    val quanta = bands.map { (it.alpha0 * 255f).roundToInt().coerceIn(0, 255) }.toMutableList()
    fun compositeQuanta(): Float {
        var keep = 1f
        for (q in quanta) keep *= 1f - q / 255f
        return (1f - keep) * 255f
    }
    while (compositeQuanta() > SHELF_PEAK_BUDGET_QUANTA && quanta.any { it > 0 }) {
        val darkest = quanta.indices.maxByOrNull { quanta[it] } ?: break
        quanta[darkest] -= 1
    }
    return bands.mapIndexed { i, band -> band.copy(alpha0 = quanta[i] / 255f) }
}

/** Draws the shelf's bands: one rounded path + vertical α₀→0 gradient per band. */
internal fun DrawScope.drawShelfBands(
    left: Float,
    right: Float,
    bottom: Float,
    outlineRadiusPx: Float,
    bands: List<ShelfBand>,
    color: Color,
) {
    for (band in bands) {
        if (band.alpha0 <= 0f) continue
        val radius = outlineRadiusPx.coerceAtLeast(0f).coerceAtMost(band.heightPx / 2f)
        val path = Path().apply {
            addRoundRect(
                RoundRect(
                    rect = Rect(left, bottom, right, bottom + band.heightPx),
                    topLeft = CornerRadius(radius),
                    topRight = CornerRadius(radius),
                    bottomRight = CornerRadius(radius),
                    bottomLeft = CornerRadius(radius),
                ),
            )
        }
        drawPath(
            path,
            brush = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = band.alpha0), color.copy(alpha = 0f)),
                startY = bottom,
                endY = bottom + band.heightPx,
            ),
        )
    }
}

/**
 * Draw [shadow] as a downward-only gradient shelf outside [shape], behind the content.
 *
 * [color] is the shadow ink — the skin's [vade.ui.skin.SkinColors.shadowFloat] for glass
 * surfaces, black for neutrals; the geometry comes from the §8.2 token [LayeredShadow].
 *
 * [inkScale]/[trimToBudget] exist for the g-shadow-android probe calibration; production
 * call sites use the defaults.
 */
fun Modifier.downwardShelf(
    shadow: LayeredShadow,
    shape: Shape,
    color: Color = Color.Black,
    dark: Boolean = false,
    inkScale: Float = SHELF_INK_SCALE,
    trimToBudget: Boolean = true,
): Modifier = this.drawWithContent {
    val effective = if (dark) shadow.forDark() else shadow
    if (effective.layers.isEmpty()) {
        drawContent()
        return@drawWithContent
    }
    val outline = shape.createOutline(size, layoutDirection, this)
    val radius = when (outline) {
        is Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x
        else -> 0f
    }
    val bands = shelfBands(effective.layers, radius, 1.dp.toPx(), inkScale, dark, trimToBudget)
    if (shelfPeakBelow(bands) * 255f < 1f) {
        // Below one gray level: nothing visible to draw (the g-shadow-real quantisation rule).
        drawContent()
        return@drawWithContent
    }
    val (left, right, bottom) = when (outline) {
        is Outline.Rectangle -> Triple(outline.rect.left, outline.rect.right, outline.rect.bottom)
        is Outline.Rounded -> Triple(outline.roundRect.left, outline.roundRect.right, outline.roundRect.bottom)
        else -> Triple(0f, size.width, size.height)
    }
    drawShelfBands(left, right, bottom, radius, bands, color)
    // Content paints over the shelf's contact row so the element's antialiased bottom
    // edge blends into the band instead of printing a second line.
    drawContent()
}
