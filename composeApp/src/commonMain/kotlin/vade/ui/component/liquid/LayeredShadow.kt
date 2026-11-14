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

    /** §8.2 `shadow0` — a static card in the page flow (tool card, attachment card). */
    val card: LayeredShadow = LayeredShadow(
        listOf(
            ShadowLayer(offsetY = 1.dp, blur = 2.dp, alpha = 0.05f),
            ShadowLayer(offsetY = 2.dp, blur = 4.dp, alpha = 0.03f),
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
 */
fun Modifier.layeredShadow(
    shadow: LayeredShadow,
    shape: Shape,
    color: Color = Color.Black,
    dark: Boolean = false,
): Modifier {
    val effective = if (dark) shadow.forDark() else shadow
    var chain: Modifier = this
    for (layer in effective.layers) {
        chain = chain.dropHalo(
            shape = shape,
            radius = layer.blur,
            color = color,
            offset = DpOffset(x = 0.dp, y = layer.offsetY),
            alpha = layer.alpha,
        )
    }
    return chain
}
