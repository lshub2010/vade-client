package vade.ui.motion

import androidx.compose.ui.Modifier
import kotlin.math.abs

/**
 * docs/visual-language.md §11 — motion blur.
 *
 * The contract lives here in `commonMain`; only the *painting* is platform code, because the
 * doc's implementation path differs per target (Android 12+ `Modifier.blur`, desktop/wasm
 * Skiko `ImageFilter.makeBlur`). The velocity → radius mapping is deliberately shared: two
 * hand-copied formulas would drift, and §11 is part of the visual single-source-of-truth.
 */

/** Below this speed nothing moves fast enough to smear (§11: 1200 dp/s). */
const val MOTION_BLUR_START_VELOCITY_DP_PER_SEC: Float = 1200f

/** Speed at which the radius reaches [MOTION_BLUR_MAX_RADIUS_DP]; slower = proportionally less. */
const val MOTION_BLUR_FULL_VELOCITY_DP_PER_SEC: Float = 6000f

/**
 * Cap for the **scroll** case (§11's sheet-open case uses a larger 14dp cap).
 *
 * 6 dp, not 10: a 10 dp radius smeared 34 sp glyphs into unreadable colour blocks, which reads
 * as "out of focus" rather than "moving fast". Motion blur must stay legible at speed and be
 * perfectly sharp at rest, so the cap is the largest radius that still leaves glyph counters
 * visible.
 */
const val MOTION_BLUR_MAX_RADIUS_DP: Float = 6f

/** §11: after the speed drops below the threshold the radius must reach 0 within 120 ms. */
const val MOTION_BLUR_DECAY_MS: Int = 120

/**
 * Horizontal Gaussian sigma per dp of radius — the *token* axis.
 *
 * §11 asks for a directional blur ("只做垂直方向为主的方向性模糊"): a scrolling list moves only
 * along Y, so the smear must live on Y. A truly zero sigmaX leaves a hard seam where
 * `FilterTileMode.CLAMP` meets the layer bounds and makes vertical glyph strokes look
 * razor-cut, so X keeps `0.12 × radius`: enough to anti-alias that edge, far too small to read
 * as defocus (at the 6 dp cap, sigmaX ≈ 0.7 px at density 1 — roughly 4.6× narrower than Y).
 */
const val MOTION_BLUR_SIGMA_X_PER_RADIUS: Float = 0.12f

/**
 * Vertical Gaussian sigma per dp of radius — the *dominant* axis.
 *
 * `0.55 × radius` puts the 6 dp cap at sigmaY ≈ 3.3 px (density 1). Physically a motion-blurred
 * edge is a box of length `speed × exposure`; a Gaussian with sigma ≈ box/3.5 is the usual
 * visual stand-in, so this factor is a look, not a measurement (RESULT.md §已知限制). Chosen by
 * eye against `shots/motion/freeze-6.png`: 34 sp monospace glyphs collapse into a vertical
 * streak whose outlines stay readable — the old isotropic `sigmaX = sigmaY = 5 px` erased them.
 */
const val MOTION_BLUR_SIGMA_Y_PER_RADIUS: Float = 0.55f

/**
 * The one and only velocity → radius curve.
 *
 * `radius = 6 × clamp((v − 1200) / (6000 − 1200), 0, 1)` — **continuous**, not a step at the
 * threshold. Piecewise-linear on purpose: linear keeps the "blur *is* speed, stop means sharp"
 * reading obvious, and an eased curve would keep pixels soft after the finger has already
 * stopped, which §11 forbids ("静止时严格为 0"). Continuous rather than "jump to max above the
 * trigger" because a fling crosses 1200 dp/s constantly — a step would strobe the list between
 * sharp and fully blurred every time the sampled velocity wobbled around the threshold.
 *
 * Non-finite input (a first frame with no previous sample) yields 0, never NaN: a NaN radius
 * would reach Skia as a NaN sigma and blank the layer.
 */
fun velocityToBlurRadiusDp(velocityDpPerSec: Float): Float {
    if (!velocityDpPerSec.isFinite()) return 0f
    val speed = abs(velocityDpPerSec)
    if (speed <= MOTION_BLUR_START_VELOCITY_DP_PER_SEC) return 0f
    val t = (speed - MOTION_BLUR_START_VELOCITY_DP_PER_SEC) /
        (MOTION_BLUR_FULL_VELOCITY_DP_PER_SEC - MOTION_BLUR_START_VELOCITY_DP_PER_SEC)
    return t.coerceIn(0f, 1f) * MOTION_BLUR_MAX_RADIUS_DP
}

/**
 * Radius in dp for a scroll velocity in dp/s.
 *
 * `expect` only so the platform code has a stable, named entry point; every actual is a
 * one-liner delegating to [velocityToBlurRadiusDp] so the *value* can never diverge between
 * Android, desktop and wasm.
 */
expect fun blurForVelocity(velocityDpPerSec: Float): Float

/**
 * Blurs the content this modifier is attached to, by [radiusDp].
 *
 * Contract (§11):
 *  - `radiusDp <= 0` ⇒ **this same modifier instance**, no layer, no paint, no draw phase
 *    cost. The doc requires "静止时严格为 0（禁止残留）"; an early `return this` is the only
 *    way to guarantee that, so every actual must keep the guard *before* allocating anything.
 *  - Layout and hit-testing are untouched: this only changes painting.
 *  - The blur is applied to the node's own content, not to whatever is behind it (that is
 *    haze's job for glass surfaces, and §11 forbids stacking the two).
 *  - Desktop/wasm smear **only along Y** ([MOTION_BLUR_SIGMA_X_PER_RADIUS] vs
 *    [MOTION_BLUR_SIGMA_Y_PER_RADIUS]). Android's `Modifier.blur` takes one radius with no
 *    per-axis parameter, so that target stays isotropic — a documented platform gap, see
 *    RESULT.md §已知限制.
 */
expect fun Modifier.motionBlur(radiusDp: Float): Modifier
