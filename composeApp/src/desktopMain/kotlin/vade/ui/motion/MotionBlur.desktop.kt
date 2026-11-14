package vade.ui.motion

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Paint
import kotlin.math.round

/**
 * Desktop (Skiko) actual for §11 motion blur.
 *
 * Path, exactly as §11 prescribes for desktop/wasm: `Modifier.drawWithContent` +
 * `drawIntoCanvas { it.nativeCanvas }` + `Canvas.saveLayer(null, paint)` where the paint carries
 * `ImageFilter.makeBlur(sigmaX, sigmaY, FilterTileMode.CLAMP)`. Skia applies a layer paint's
 * image filter when `restore()` composites the layer, so everything the wrapped node drew is
 * blurred as one image. Layout and hit-testing are untouched (§11: 模糊层不改变布局与命中区域).
 *
 * **Directional, not isotropic.** §11 asks for a blur whose dominant axis is the direction of
 * motion, and this probe only ever blurs a vertically scrolling list, so the two sigmas are
 * deliberately asymmetric: [MOTION_BLUR_SIGMA_Y_PER_RADIUS] (0.55) against
 * [MOTION_BLUR_SIGMA_X_PER_RADIUS] (0.12), a 4.6× ratio. An isotropic `makeBlur(s, s)` is what
 * the first version did, and it read as camera defocus — glyphs went soft on every side instead
 * of streaking down the page.
 *
 * Rejected alternatives, checked against the real API surface of this Skiko build:
 *  - `graphicsLayer { renderEffect = ... }`: `RenderEffect` is Android-only in Compose; there is
 *    no `renderEffect` parameter on desktop/wasm, so it cannot be the cross-target path.
 *  - Draw into a `Picture`/`Image` first: an extra full-size bitmap per frame plus a second
 *    pass; `saveLayer` lets Skia choose the backing store.
 *  - `Modifier.Node`/`DrawModifierNode`: works (same `saveLayer` call) but needs `onDetach` to
 *    close ref-counted native handles; the shared cache below is simpler and does the same job.
 *  - `ImageFilter.makeMatrixConvolution` for a true box/motion kernel: the honest physics, but
 *    it evaluates `width × height` coefficients per pixel with no separable fast path, so it
 *    would cost far more than two 1-D Gaussians for a difference this probe cannot see at 6 dp.
 */

/**
 * Sigma quantization so a per-frame radius wobble does not allocate a filter every frame.
 * 0.25 px is below one visible step at 1× density.
 */
private const val SIGMA_STEP_PX = 0.25f

actual fun blurForVelocity(velocityDpPerSec: Float): Float = velocityToBlurRadiusDp(velocityDpPerSec)

actual fun Modifier.motionBlur(radiusDp: Float): Modifier {
    // §11: zero radius must cost nothing — no layer, no paint, no draw-phase work at all.
    if (radiusDp <= 0f) return this
    return this.drawWithContent {
        // dp → px with the *draw-time* density, so the blur follows display scaling.
        val sigmaX = quantize(radiusDp * density * MOTION_BLUR_SIGMA_X_PER_RADIUS)
        val sigmaY = quantize(radiusDp * density * MOTION_BLUR_SIGMA_Y_PER_RADIUS)
        drawIntoCanvas { composeCanvas ->
            val canvas = composeCanvas.nativeCanvas
            canvas.saveLayer(null, SkiaBlurPaints.forSigmas(sigmaX, sigmaY))
            drawContent()
            canvas.restore()
        }
    }
}

/**
 * Snap to [SIGMA_STEP_PX] but never to 0: `makeBlur` treats a 0 sigma as "no blur on that axis",
 * which would leave the layer edge unsoftened. One step is the smallest value Skia renders as a
 * real (if sub-pixel) blur.
 */
private fun quantize(sigma: Float): Float =
    (round(sigma / SIGMA_STEP_PX) * SIGMA_STEP_PX).coerceAtLeast(SIGMA_STEP_PX)

/**
 * One cached `Paint` + `ImageFilter` pair for the whole process.
 *
 * Why not allocate inside `drawWithContent`: `ImageFilter.makeBlur` allocates a ref-counted
 * native handle, and a scroll asks for a new radius every frame. §11 caps this at one blurred
 * element at a time and Compose draws on a single thread, so a one-entry cache keyed by the
 * quantized sigma *pair* is sufficient and race-free. The old pair is closed before the new one
 * is created; the last pair lives until process exit — a single bounded allocation (RESULT.md
 * §已知限制).
 */
private object SkiaBlurPaints {
    private var paint: Paint? = null
    private var filter: ImageFilter? = null
    private var sigmaX: Float = Float.NaN
    private var sigmaY: Float = Float.NaN

    fun forSigmas(nextX: Float, nextY: Float): Paint {
        paint?.let { if (nextX == sigmaX && nextY == sigmaY) return it }
        filter?.close()
        paint?.close()
        val created = ImageFilter.makeBlur(
            sigmaX = nextX,
            sigmaY = nextY,
            mode = FilterTileMode.CLAMP,
        )
        filter = created
        return Paint().also {
            it.imageFilter = created
            paint = it
            sigmaX = nextX
            sigmaY = nextY
        }
    }
}
