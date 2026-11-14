package vade.ui.motion

import androidx.compose.ui.Modifier
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * docs/visual-language.md §11 — motion blur.
 *
 * The contract lives here in `commonMain`; only the *painting* is platform code, because the
 * doc's implementation path differs per target (Android 12+ `Modifier.blur`, desktop/wasm
 * Skiko `ImageFilter` — `makeMatrixConvolution` for the box kernel, `makeBlur` for the
 * Gaussian fallback). The velocity → radius mapping is deliberately shared: two hand-copied
 * formulas would drift, and §11 is part of the visual single-source-of-truth.
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
 * Vertical Gaussian sigma per dp of radius — the *dominant* axis of the Gaussian fallback.
 *
 * `0.55 × radius` puts the 6 dp cap at sigmaY ≈ 3.3 px (density 1). Physically a motion-blurred
 * point is a rectangle of length `speed × exposure`; a Gaussian with sigma ≈ box/3.5 is the
 * usual visual stand-in, so this factor is a look, not a measurement. Chosen by eye against
 * `shots/motion/freeze-6.png`: 34 sp monospace glyphs collapse into a vertical streak whose
 * outlines stay readable — the old isotropic `sigmaX = sigmaY = 5 px` erased them.
 *
 * **This is the fallback path.** [MotionBlurKernel.Box] reproduces the real kernel (a plateau,
 * not a bell) and is the default; the Gaussian stays selectable so the A/B in RESULT.md can be
 * re-run, and as the degradation path where a convolution kernel is unavailable.
 */
const val MOTION_BLUR_SIGMA_Y_PER_RADIUS: Float = 0.55f

/**
 * Which kernel the platform should paint.
 *
 * - [Box] — the honest physics. Over one exposure the content sweeps continuously along the
 *   motion vector, so every position in that span contributes for an equal share of the
 *   exposure. Convolving with a rectangle of that length *is* the integral, and it is what a
 *   real camera produces: a uniform streak with hard ends, not a soft bell.
 * - [Triangle] — the *tapered* streak: a Bartlett window, i.e. box ⊛ box. Same smear span as
 *   [Box], same "stops dead at the ends" reading, but the two hard corners of the rectangle are
 *   replaced by linear ramps. Its frequency response is the half-length box response *squared*,
 *   which is the point: a rectangle's |sinc| has −13 dB sidelobes, so any periodic detail
 *   shorter than the kernel (glyph cross-bars at 4–7 px pitch, hatch patterns, table rules)
 *   comes back as a visible **echo** — several evenly spaced copies of the same stroke.
 *   Squaring drops the worst sidelobe to −25 dB and cuts the gain at those pitches by 2.3×
 *   (5 px) to 3.8× (4 px), with exact nulls at 6 and 7 px (measured: `MotionBlurTriangleKernelTest`),
 *   which removes the echo while keeping the streak. The cost is that it is no longer the exact
 *   physical integral; it is the integral seen through a 1-px-wide shutter, which is what a
 *   real camera's rolling shutter also does.
 * - [Gaussian] — the visual stand-in used before this probe measured the difference. A bell
 *   profile makes a moving list look like a defocused photograph: the streak fades out at both
 *   ends instead of stopping.
 *
 * Android cannot select: `Modifier.blur` takes one radius and no kernel, so it is always an
 * isotropic Gaussian (see [motionBlur] and RESULT.md §已知限制).
 */
enum class MotionBlurKernel {
    Box,
    Triangle,
    Gaussian,
}

/**
 * Kernel the probe ships with. Decided by measurement, not by taste — RESULT.md §三/§五 record
 * the streak profile, the beat measurement and the frame times that chose it.
 */
val DEFAULT_MOTION_BLUR_KERNEL: MotionBlurKernel = MotionBlurKernel.Box

/**
 * Number of taps in the vertical box kernel, in **device pixels**.
 *
 * `N = 2 × max(1, round(radiusDp × density)) + 1`, always odd.
 *
 * The intent is "smear length = 2 × radius px, sampled at 1 tap per px", i.e.
 * `round(2 × radiusPx) + 1` taps. Writing it as `2 × round(radiusPx) + 1` keeps the oddness
 * *structural* instead of hoped-for: Skia's `makeMatrixConvolution` rejects an even kernel
 * dimension, and the naive form produces an even N at every half-pixel radius (1.5 dp @ 1×
 * → 4), so half the curve would need a special case. Both forms agree where it matters:
 * 6 dp @ 1× → **13** taps, and `N − 1` = the smear span in px.
 */
fun boxKernelTaps(radiusDp: Float, density: Float): Int {
    if (!radiusDp.isFinite() || !density.isFinite()) return 1
    val radiusPx = radiusDp * density
    if (radiusPx <= 0f) return 1
    val half = radiusPx.roundToInt().coerceAtLeast(1)
    return 2 * half + 1
}

/**
 * Weights for the box kernel: `1 / N` each.
 *
 * They sum to exactly 1, so the caller passes `gain = 1` and the layer keeps its brightness —
 * a kernel that summed to anything else would darken or blow out the list.
 */
fun boxKernelWeights(taps: Int): FloatArray {
    require(taps >= 1) { "kernel needs at least one tap, got $taps" }
    val weight = 1f / taps
    return FloatArray(taps) { weight }
}

/**
 * Weights for the triangle kernel: a **Bartlett window**, `w[i] ∝ half + 1 − |i − centre|`.
 *
 * This is box ⊛ box sampled on the *same* N-tap grid as [boxKernelWeights], so the streak keeps
 * the box's span (13 px at 6 dp) and its hard-stopping ends, but the rectangle's two 90° corners
 * become linear ramps that run all the way to zero.
 *
 * Why the taper and not the literal `N − |i − centre|` form: that form is the *same* triangle
 * truncated at 54% of its peak (7/13 at the ends), which is still a hard corner — it only reaches
 * −18.9 dB of sidelobe suppression. Tapering to 0 is what makes the response the *half-length*
 * box's response squared: −25.3 dB. `MotionBlurTriangleKernelTest` measures both and pins the
 * difference, so this is a measured choice, not a preference. Both keep N odd, so the centre tap
 * is exact.
 */
fun triangleKernelWeights(taps: Int): FloatArray {
    require(taps >= 1) { "kernel needs at least one tap, got $taps" }
    val centre = taps / 2
    var sum = 0f
    val raw = FloatArray(taps) { i ->
        val w = (centre + 1 - abs(i - centre)).toFloat()
        sum += w
        w
    }
    // Normalise in place: gain stays 1 at the call site, so the layer's brightness is untouched.
    for (i in raw.indices) raw[i] = raw[i] / sum
    return raw
}

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
 *  - Desktop/wasm smear **only along Y**: [MotionBlurKernel.Box] (default) convolves with a
 *    1×N rectangle — the integral of the motion over the exposure, so the streak has a flat
 *    top and hard ends. [MotionBlurKernel.Gaussian] is the retained fallback, asymmetric
 *    ([MOTION_BLUR_SIGMA_X_PER_RADIUS] vs [MOTION_BLUR_SIGMA_Y_PER_RADIUS]).
 *  - Android's `Modifier.blur` takes one radius, with no per-axis and no kernel parameter, so
 *    that target is always an isotropic Gaussian regardless of [kernel] — a documented
 *    platform gap, see RESULT.md §已知限制.
 */
expect fun Modifier.motionBlur(
    radiusDp: Float,
    kernel: MotionBlurKernel = DEFAULT_MOTION_BLUR_KERNEL,
): Modifier
