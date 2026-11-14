package vade.ui.motion

import android.os.Build
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.dp

/**
 * Android actual for §11 motion blur.
 *
 * §11 says Android 12+ (API 31) uses `Modifier.blur(radius, edgeTreatment = Unbounded)` and
 * Android 11 and below degrades to **no blur at all** (not a substitute animation), and the
 * setting is not exposed there. `Modifier.blur` itself no-ops below 31, but it still installs a
 * graphics layer, so the version check is explicit: below 31 this returns `this` and costs
 * nothing, matching the desktop/wasm `radiusDp <= 0` fast path.
 *
 * `BlurredEdgeTreatment.Unbounded` (not the default `Rectangle`): a scrolling list is clipped
 * by its own parent, so the blur must be allowed to bleed past the node's bounds instead of
 * being squeezed into a rectangle that shows a hard edge.
 *
 * **Platform gap — this actual is isotropic.** `Modifier.blur` takes a single radius and Compose
 * exposes no per-axis variant on any API level (`RenderEffect.createBlurEffect` also takes one
 * sigma per axis but only through a graphics-layer `RenderEffect`, which `Modifier.blur` does
 * not let us build from a radius pair). Desktop/wasm smear mostly along Y
 * ([MOTION_BLUR_SIGMA_X_PER_RADIUS] vs [MOTION_BLUR_SIGMA_Y_PER_RADIUS]); Android therefore
 * looks like mild defocus rather than a vertical streak. Closing the gap needs a
 * `graphicsLayer { renderEffect = RenderEffect.createBlurEffect(sx, sy, CLAMP) }` path gated on
 * API 31 — recorded in RESULT.md §已知限制 rather than guessed at here, because it cannot be
 * verified without a device.
 */

actual fun blurForVelocity(velocityDpPerSec: Float): Float = velocityToBlurRadiusDp(velocityDpPerSec)

actual fun Modifier.motionBlur(radiusDp: Float): Modifier {
    if (radiusDp <= 0f) return this
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return this
    return this.blur(radiusDp.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
}
