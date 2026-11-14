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
const val MOTION_BLUR_FULL_VELOCITY_DP_PER_SEC: Float = 4800f

/** Hard cap from §11 (the sheet-open case uses a larger 14dp cap; see [MOTION_BLUR_MAX_RADIUS_DP]). */
const val MOTION_BLUR_MAX_RADIUS_DP: Float = 10f

/** §11: after the speed drops below the threshold the radius must reach 0 within 120 ms. */
const val MOTION_BLUR_DECAY_MS: Int = 120

/**
 * The one and only velocity → radius curve.
 *
 * Piecewise-linear on purpose: `0` at [MOTION_BLUR_START_VELOCITY_DP_PER_SEC], rising to
 * [MOTION_BLUR_MAX_RADIUS_DP] at [MOTION_BLUR_FULL_VELOCITY_DP_PER_SEC] (3× the trigger
 * speed, which is where a normal fling lands), clamped above. Linear keeps the "blur is
 * speed, stop means sharp" reading obvious — an eased curve would keep pixels soft after the
 * finger has already stopped, which §11 forbids ("静止时严格为 0").
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
 */
expect fun Modifier.motionBlur(radiusDp: Float): Modifier
