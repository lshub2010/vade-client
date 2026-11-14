// g-float: platform hook for REAL gaussian shadows.
//
// The user's verdict (「阴影为什么有边框，不应该直接淡出了吗」) killed the ring-stack
// halo: disjoint hard-edged rings can never read as a fade. The halo is now drawn
// through the platform's MaskFilter gaussian — one blurred rounded-rect draw, no
// rings. The geometry is passed as *parameters* (rect + corner radius + offset in
// px, colour as ARGB, blur as σ) so each actual builds its own native path — no
// cross-platform Path/Paint conversion APIs involved:
//  - Android: android.graphics.BlurMaskFilter through the framework paint
//    (hardware-acceleration supported; re-verified on the API 30 real device —
//    see shots/g-float/RESULT.md for the before/after scanlines);
//  - desktop/wasmJs: org.jetbrains.skia Paint.maskFilter + nativeCanvas, the same
//    mechanism MotionBlur.desktop.kt already proved renders on this skiko build.

package vade.ui.component.liquid

import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * Draws ONE gaussian-blurred rounded-rect silhouette (the halo) at
 * `[left+dx, top+dy, right+dx, bottom+dy]` with uniform [cornerRadius] px, colour
 * [colorArgb] (ARGB, alpha already baked in) and gaussian σ [blurSigmaPx] px.
 * Returns `false` when the platform cannot blur (caller falls back to the
 * filled-layer ramp). offsetX must stay 0 — §8.3: the light is straight above.
 */
internal expect fun DrawScope.drawBlurredHalo(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    cornerRadius: Float,
    dx: Float,
    dy: Float,
    colorArgb: Int,
    blurSigmaPx: Float,
): Boolean
