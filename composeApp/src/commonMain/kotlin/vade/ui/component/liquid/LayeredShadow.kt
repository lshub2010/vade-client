// Layered, directional shadow — docs/visual-language.md §8.
//
// Why this exists: `Modifier.shadow(elevation, shape)` paints **one** symmetric
// silhouette. The user's requirement is explicit — "阴影这些都是有渐变的，不是后面糊
// 一层就完事，然后阴影有方向" — so a single translucent slab is banned. A real
// shadow is ≥2 stacked layers with different blur radii and different alphas, and
// it is **directional**: the light sits above and slightly in front, so every layer
// is offset *downwards* and never sideways.
//
// Technique: reuse the pure-canvas ring-band falloff from `dropHalo` (InnerShadow.kt)
// once per layer. No RenderEffect, no GraphicsLayer, no blend modes — so Android,
// desktop and wasm all draw the same thing, and nothing bleeds through a translucent
// glass tint the way `Modifier.shadow`'s elevation silhouette does.

package vade.ui.component.liquid

import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * One shadow layer: a ring falloff of [blur] radius, pushed [offsetY] down, at [alpha].
 *
 * §8.3 fixes the geometry: `offsetY : blur ≈ 1 : 2` and `offsetX` is always 0 (the
 * interface is lit from straight above; no oblique light).
 */
@Immutable
data class ShadowLayer(
    val offsetY: Dp,
    val blur: Dp,
    val alpha: Float,
)

/**
 * A complete shadow: an ordered list of [ShadowLayer]s, drawn innermost-first so the
 * tight dark layer sits under the wide faint one — the continuous falloff that reads
 * as a gradient instead of a slab.
 */
@Immutable
data class LayeredShadow(val layers: List<ShadowLayer>) {

    /**
     * Dark-theme variant (§8.2): alpha × 1.8, blur × 1.2. On a dark canvas a light-mode
     * shadow is invisible — it needs to be both deeper and wider to read at all.
     */
    fun forDark(): LayeredShadow = LayeredShadow(
        layers.map { it.copy(alpha = (it.alpha * 1.8f).coerceAtMost(1f), blur = it.blur * 1.2f) },
    )
}

/**
 * The five documented levels (§8.2). Call sites pick by *layer*, never by eyeballing an
 * elevation number: `shadow0` for a static card inside the page, `shadow2` for a floating
 * composer, `shadow3` for a sheet/drawer.
 */
object VadeShadows {

    /**
     * §8.2 `shadow0` — a static card in the page flow (tool card, attachment card, approval card).
     *
     * g-cards-audit retune. The documented pair (`0 1 2 .05` + `0 2 4 .03`) was copied from
     * CSS box-shadow, where "blur 2" already means σ≈1. Here σ = blur·0.5 and offsetY stays
     * tiny, so every layer's gaussian was *narrower than its own offset*: the whole shadow
     * collapsed into a 11–16 px rim that is 18/255 (7 %) darker than the canvas and steps
     * 3–4 levels per pixel — measured on the emulator at capabilities/profile/模块页面, it
     * reads as 「卡片有一圈暗边框」, exactly the user's 第二句「卡片的也不行」.
     *
     * Static cards are hairline-first (§8 prologue: 静态卡片靠 hairline 分隔) and 阴影是
     * 「允许极浅」— so shadow0 is now a *wide, very faint, downward-only* pair:
     * offsetY:blur = 1:2 still (§8.3), but σ (2dp / 5dp) is twice the offset, so the ink
     * spreads over ~30 px instead of 15 px while the peak drops to Φ(1)·ink ≈ 6/255
     * (2.4 %, inside the ≤8/255 budget) and the top edge gets Φ(-1)·ink ≈ 1.2/255
     * (no visible shadow above the card). Nothing here is a slab: the two layers are
     * different blurs at different alphas, offset straight down.
     */
    val card: LayeredShadow = LayeredShadow(
        listOf(
            ShadowLayer(offsetY = 2.dp, blur = 4.dp, alpha = 0.014f),
            ShadowLayer(offsetY = 5.dp, blur = 10.dp, alpha = 0.010f),
        ),
    )

    /** §8.2 `shadow1` — hovered list row, chip. */
    val raised: LayeredShadow = LayeredShadow(
        listOf(
            ShadowLayer(offsetY = 2.dp, blur = 4.dp, alpha = 0.06f),
            ShadowLayer(offsetY = 4.dp, blur = 8.dp, alpha = 0.04f),
        ),
    )

    /** §8.2 `shadow2` — the floating composer, the search overlay, the bottom pill. */
    val float: LayeredShadow = LayeredShadow(
        listOf(
            ShadowLayer(offsetY = 4.dp, blur = 8.dp, alpha = 0.07f),
            ShadowLayer(offsetY = 8.dp, blur = 16.dp, alpha = 0.05f),
            ShadowLayer(offsetY = 12.dp, blur = 24.dp, alpha = 0.03f),
        ),
    )

    /** §8.2 `shadow3` — a global sheet, a drawer. */
    val sheet: LayeredShadow = LayeredShadow(
        listOf(
            ShadowLayer(offsetY = 8.dp, blur = 16.dp, alpha = 0.09f),
            ShadowLayer(offsetY = 16.dp, blur = 32.dp, alpha = 0.06f),
            ShadowLayer(offsetY = 24.dp, blur = 48.dp, alpha = 0.04f),
        ),
    )

    /** §8.2 `shadow4` — image-preview control bar, transient toast. */
    val overlay: LayeredShadow = LayeredShadow(
        listOf(
            ShadowLayer(offsetY = 12.dp, blur = 24.dp, alpha = 0.11f),
            ShadowLayer(offsetY = 24.dp, blur = 48.dp, alpha = 0.07f),
            ShadowLayer(offsetY = 32.dp, blur = 64.dp, alpha = 0.05f),
        ),
    )
}

/**
 * Draw [shadow] outside [shape], one ring falloff per layer.
 *
 * [color] is the shadow ink (black for neutrals, an accent for glow — but glow is
 * **symmetric and offset-free**, so it does not go through this modifier; §8.4).
 *
 * The layer order matters: `dropHalo` draws each falloff *over* what is already there,
 * so iterating the list in order puts the wide faint layer last, which is what produces
 * the smooth ramp rather than a hard edge.
 *
 * @param outsideOnly keep every layer strictly outside [shape] (see [dropHalo]): a
 *   translucent consumer then keeps its own tint instead of compositing 5–7/255 of
 *   shadow ink into its fill. The composer sets this — its `surfaceGlass` is 84 %
 *   opaque, and the blended-in ink used to read as 「玻璃底发灰」.
 *
 *   **g-shadow-android:** "strictly outside" is now drawn as the downward-only gradient
 *   shelf ([downwardShelfRaw]) — one vertical alpha gradient per §8.2 layer in a rounded
 *   path that lives strictly *below* the element's bottom edge, quantised-peak-trimmed
 *   into the ≤3 % budget. It used to clip each gaussian out of the shape with
 *   `clipPath(clipOp = Difference)`, which measured **0 pixels** on Android's hardware
 *   canvas — every clip/blur/mask route there paints nothing (g-shadow-real: Compose
 *   clipPath, clip+saveLayer, native clipPath DIFFERENCE, native saveLayer+CLEAR all
 *   0 px; the 40-layer fallback quantises to 0). The shelf needs no clip, no blur, no
 *   offscreen layer, and it keeps the contract this flag exists for: the glass fill is
 *   untouched (nothing is painted inside or above the shape — measured 0.00/255 above
 *   and 0 inside on the probe), so a translucent panel keeps exactly the tint its token
 *   says. It also lands on the §8.3-correct side: the old gaussian's upper tail leaked
 *   ink above the composer, the shelf paints nothing there.
 */
fun Modifier.layeredShadow(
    shadow: LayeredShadow,
    shape: Shape,
    color: Color = Color.Black,
    dark: Boolean = false,
    outsideOnly: Boolean = false,
): Modifier {
    val effective = if (dark) shadow.forDark() else shadow
    if (outsideOnly) {
        return downwardShelfRaw(
            shadow = effective,
            shape = shape,
            color = color,
        )
    }
    var chain: Modifier = this
    for (layer in effective.layers) {
        chain = chain.dropHalo(
            shape = shape,
            radius = layer.blur,
            color = color,
            offset = DpOffset(x = 0.dp, y = layer.offsetY),
            alpha = layer.alpha,
            outsideOnly = false,
        )
    }
    return chain
}
