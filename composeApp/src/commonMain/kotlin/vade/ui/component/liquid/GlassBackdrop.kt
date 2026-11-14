// w2-bottombar · liquid-glass facade over miuix-blur.
//
// The shell (`vade.ui.shell`) is scanned by MaterialImportGuardTest and must stay
// free of toolkit imports, so every miuix-blur type lives behind this facade:
// the shell only ever names GlassBackdrop / GlassSurfaceBackdrop /
// LiquidRefraction / LiquidHighlight. Backdrop capture follows KernelSU's
// pattern (`rememberBlurBackdrop` + `Modifier.layerBackdrop`), Copyright (c)
// 2023-2026 weishu & contributors, Apache License 2.0 — see README.md.

package vade.ui.component.liquid

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.BloomStroke
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.highlight.LightPosition
import top.yukonga.miuix.kmp.blur.highlight.LightSource
import top.yukonga.miuix.kmp.blur.highlight.rememberTiltLight
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

/**
 * Capture handle: the pixel source a bar samples. Attach it to the app-content
 * container with [captureGlassBackdrop] once; every glass surface below it can
 * then blur it.
 */
class GlassBackdrop internal constructor(internal val layer: LayerBackdrop) {
    /** The draw-side view of this capture alone. */
    fun asSurfaceBackdrop(): GlassSurfaceBackdrop = GlassSurfaceBackdrop(layer)
}

/**
 * Draw handle: the backdrop pixels a glass surface samples. Either a plain
 * capture ([GlassBackdrop.asSurfaceBackdrop]) or a composition of two
 * ([rememberCombinedSurfaceBackdrop]) — the pill's indicator needs content +
 * accent-tinted tabs so the refraction smears both.
 */
class GlassSurfaceBackdrop internal constructor(internal val backdrop: Backdrop)

/**
 * Remembers the app-content capture source. Attach its result with
 * [captureGlassBackdrop] on the container whose pixels should blur *behind*
 * bars and pills.
 *
 * @param surfaceColour optional colour painted *under* the recorded content
 *   (KernelSU's `rememberBlurBackdrop` paints the surface colour so the blur
 *   never samples transparency at the capture bounds). `null` records the
 *   content alone — the tint then comes from the surface pass instead.
 */
@Composable
fun rememberGlassBackdrop(surfaceColour: Color? = null): GlassBackdrop =
    if (surfaceColour == null) {
        GlassBackdrop(rememberLayerBackdrop())
    } else {
        val colour = surfaceColour
        GlassBackdrop(rememberLayerBackdrop { drawRect(colour) })
    }

/** Marks [Modifier]'s node as the pixel source of [backdrop]. */
fun Modifier.captureGlassBackdrop(backdrop: GlassBackdrop): Modifier =
    this.layerBackdrop(backdrop.layer)

/** Content backdrop + a second capture, drawn into the same surface. */
@Composable
fun rememberCombinedSurfaceBackdrop(
    content: GlassSurfaceBackdrop,
    extra: GlassSurfaceBackdrop,
): GlassSurfaceBackdrop = GlassSurfaceBackdrop(
    rememberCombinedBackdrop(content.backdrop, extra.backdrop),
)

/** Shell-visible refraction spec (mapped to [lens] inside the facade). */
@Immutable
data class LiquidRefraction(
    val refractionHeight: Dp,
    val refractionAmount: Dp,
    val depthEffect: Boolean = false,
    val chromaticAberration: Float = 0f,
)

/** Shell-opaque highlight handle (a miuix [Highlight] behind a facade type). */
class LiquidHighlight internal constructor(internal val highlight: Highlight) {
    /** Rebuilds the highlight at [alpha] — used per-frame for the press progress. */
    fun withAlpha(alpha: Float): LiquidHighlight = LiquidHighlight(highlight.copy(alpha = alpha))
}

/**
 * The pill's edge-bloom specular, mirroring KernelSU's `iosIndicatorSpecular`
 * (dual-peak BloomStroke), with one deliberate substitution: KernelSU rotates
 * the primary light by the *quantized gravity vector* (a device sensor stream).
 * Here the primary light goes through miuix-blur's cross-platform
 * `rememberTiltLight`, which shifts with device tilt where sensors exist
 * (Android) and degrades to a static light position on desktop/wasm —
 * documented degradation (see shots/w2-bottombar/RESULT.md).
 *
 * @param dark selects the slightly brighter stroke for dark surfaces.
 */
@Composable
fun rememberPillHighlight(dark: Boolean): LiquidHighlight {
    // Primary light anchored at the light reference (0.5, 0.7) minus 90°:
    // x = 0.5 + cos(-90°) = 0.5, y = 0.7 + sin(-90°) = -0.3 (the top edge) —
    // the same anchor KernelSU's quantized gravity falls back to at rest.
    val primary = rememberTiltLight(
        basePosition = LightPosition(0.5f, -0.3f, -0.05f),
        color = Color.White,
        intensity = 1f,
        sensitivity = 0.1f,
    )
    val strokeColour = if (dark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.12f)
    val highlight = Highlight(
        width = 1.dp,
        alpha = 1f,
        style = BloomStroke(
            color = strokeColour,
            innerBlurRadius = 2.0.dp,
            primaryLight = primary,
            secondaryLight = LightSource(
                position = LightPosition(0.5f, 0.8f, -0.5f),
                color = Color.White,
                intensity = 0.4f,
            ),
            dualPeak = true,
        ),
    )
    return remember(dark, primary) { LiquidHighlight(highlight) }
}

/**
 * The one backdrop-surface entry point the shell needs. Wraps miuix-blur's
 * `drawBackdrop` with the facade types; all parameters mirror it (see its KDoc).
 *
 * The [refraction] / [highlight] lambdas are evaluated at *draw* time so a
 * press animation (Animatable state read inside them) re-renders the surface
 * without recomposition — the same mechanism KernelSU relies on.
 *
 * @param blurRadiusDp Gaussian radius applied to the captured content; `null`
 *   records no blur pass.
 * @param refraction optional lens spec provider; no-ops where runtime shaders
 *   are unavailable.
 * @param vibrancy boosts backdrop saturation (see [vibrancy]).
 * @param extraPaddingDp raises the blur bleed (KernelSU: `max(padding, 40.dp)`).
 * @param highlight optional edge-bloom provider.
 * @param layerBlock per-frame graphics-layer block (press scaling, velocity
 *   stretch, rubber band) applied to the whole surface node.
 * @param onDrawSurface extra drawing over the blurred backdrop *behind* the
 *   node's content (tint pass, pressed-state dimming).
 * @param enabled `false` degrades to plain content drawing (no blur at all).
 */
fun Modifier.glassSurface(
    backdrop: GlassSurfaceBackdrop,
    shape: () -> Shape,
    blurRadiusDp: Float? = null,
    refraction: (() -> LiquidRefraction?)? = null,
    vibrancy: Boolean = false,
    extraPaddingDp: Float? = null,
    highlight: (() -> LiquidHighlight?)? = null,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
    onDrawSurface: (DrawScope.() -> Unit)? = null,
    enabled: Boolean = true,
): Modifier = this.drawBackdrop(
    backdrop = backdrop.backdrop,
    shape = shape,
    effects = {
        extraPaddingDp?.let { padding = maxOf(padding, it.dp.toPx()) }
        if (vibrancy) vibrancy()
        blurRadiusDp?.let { radius -> blur(radius.dp.toPx(), radius.dp.toPx()) }
        refraction?.invoke()?.let { spec ->
            lens(
                refractionHeight = spec.refractionHeight.toPx(),
                refractionAmount = spec.refractionAmount.toPx(),
                depthEffect = spec.depthEffect,
                chromaticAberration = spec.chromaticAberration,
            )
        }
    },
    highlight = highlight?.let { provider -> { provider.invoke()?.highlight } },
    layerBlock = layerBlock,
    onDrawSurface = onDrawSurface,
    enabled = enabled,
)
