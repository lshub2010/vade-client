package vade.ui.skin.glass

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.rememberHazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import vade.ui.skin.Skin

/**
 * The one shared [HazeState] a full-screen glass setup uses.
 *
 * Haze 2.x has no "blur whatever happens to be behind me" — the *backdrop* layers must
 * register themselves on the state (`Modifier.hazeSource(state)`) and every glass layer
 * reads from that same state. v2 needs glass on top bar, bottom bar, floating composer,
 * sheets, drawer and search overlay at once (visual language §5), so the app owns a
 * single state at the root and provides it here; [GlassSurface] picks it up without the
 * call site threading a parameter through the whole tree.
 *
 * A screen that never provides this local (tests, isolated previews) still gets a
 * correct — if blur-less — surface: [GlassSurface] then creates a private empty state,
 * and the layer degrades to tint + hairline + shadow (the §5 Android ≤30 scrim shape).
 */
val LocalGlassHazeState = staticCompositionLocalOf<HazeState?> { null }

/**
 * A glass surface: real backdrop blur (Haze 2.1.0) + a hairline edge + a float-layer
 * shadow, in the docs/visual-language.md §5/§8 semantics.
 *
 * Probe-verified constraints (haze probe, `tmp/haze-probe` RESULT.md — do not "simplify"):
 *
 *  - **The tint goes through `colorEffects(listOf(HazeColorEffect.tint(tint)))`, never
 *    `backgroundColor(...)`.** `backgroundColor` paints *behind* the opaque blurred
 *    backdrop, so it is a visual no-op — with it the glass renders as the raw un-tinted
 *    blur mean and washes out text contrast (2.15:1 measured on the probe). The tint
 *    itself comes from [Skin.glass] (`tintLight`/`tintDark` per [SkinColors.isDark]).
 *  - **`blurredEdgeTreatment` takes a `BlurredEdgeTreatment`, not a `Shape`** — passing
 *    the shape directly does not compile.
 *  - **Content is never blurred by the glass.** `hazeBlur` only filters the backdrop;
 *    this node's own content draws unfiltered on top. Do not split glass and content
 *    into separate layers to "fix" sharpness — the probe proved that changes nothing
 *    (PNG md5 identical).
 *  - The backdrop must be registered on the *same* state this surface reads
 *    ([LocalGlassHazeState]) — otherwise the layer degrades to tint + hairline + shadow.
 *
 * Shadow (§8 row 2): float layers carry a real shadow; Compose's elevation shadow is the
 * platform approximation of the §8 spec (`0 16 40 rgba(0,0,0,.55)` dark / `0 12 32 …18%`
 * light), tinted with [SkinColors.shadowFloat] so skins keep it neutral-black per theme.
 * The hairline is [SkinGlass.hairlineOnGlass] — the glass form of `colors.hairline`
 * (§5: deepen it one step on busy backdrops); setting it to `colors.hairline` falls back
 * to the plain token.
 *
 * @param shape clipping shape for the blur edges, the border and the shadow.
 * @param skin the active skin — supplies [Skin.glass] radii/tints and the colours.
 * @param content drawn unfiltered on top of the glass.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape,
    skin: Skin,
    content: @Composable () -> Unit,
) {
    val state = LocalGlassHazeState.current ?: rememberHazeState()
    val tint = if (skin.colors.isDark) skin.glass.tintDark else skin.glass.tintLight
    Box(
        modifier
            // §8: a float layer is defined by having a shadow — draw it first so it
            // casts from the surface bounds and is not clipped by the blur layer.
            .shadow(
                elevation = 16.dp,
                shape = shape,
                ambientColor = skin.colors.shadowFloat,
                spotColor = skin.colors.shadowFloat,
            )
            .hazeBlur(
                input = HazeInput.Sources(state = state),
                style = HazeBlurStyle {
                    blurEnabled(true)
                    blurRadius(skin.glass.blurRadiusDp.dp)
                    // 染色只能走 colorEffects —— backgroundColor 画在不透明模糊层之后，
                    // 完全无效（haze 探针 RESULT.md §坑1）。
                    colorEffects(listOf(HazeColorEffect.tint(tint)))
                    noiseFactor(0f)
                    blurredEdgeTreatment(BlurredEdgeTreatment(shape))
                },
            )
            .border(1.dp, skin.glass.hairlineOnGlass, shape),
    ) {
        content()
    }
}
