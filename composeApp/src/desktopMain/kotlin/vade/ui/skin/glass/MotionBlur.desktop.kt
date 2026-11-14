package vade.ui.skin.glass

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
 * an `ImageFilter`. Skia applies a layer paint's image filter when `restore()` composites the
 * layer, so everything the wrapped node drew is filtered as one image. Layout and hit-testing are
 * untouched (§11: 模糊层不改变布局与命中区域).
 *
 * **Two kernels, one switch** ([MotionBlurKernel]):
 *
 *  - [MotionBlurKernel.Box] (default) — `ImageFilter.makeMatrixConvolution` with a 1×N kernel of
 *    equal `1/N` weights, N from [boxKernelTaps]. This is the physically honest motion blur: over
 *    one exposure the content sweeps continuously along Y, so the recorded image is the
 *    *integral* of the content over that span, and integrating over a window is exactly a
 *    convolution with a rectangle. The result on a vertical glyph stroke is a **flat plateau** of
 *    equal brightness with abrupt ends — what a real camera produces, and what reads as "moving
 *    fast".
 *  - [MotionBlurKernel.Gaussian] — the retained fallback: `makeBlur(sigmaX, sigmaY)` with
 *    [MOTION_BLUR_SIGMA_X_PER_RADIUS] (0.12) against [MOTION_BLUR_SIGMA_Y_PER_RADIUS] (0.55), a
 *    4.6× ratio. It is directional but its profile is a *bell*: the streak fades out at both ends,
 *    so a fast list reads as a defocused photograph. Kept because it is the degradation path (a
 *    platform without a convolution filter still gets a directional blur) and because the A/B in
 *    RESULT.md must stay reproducible.
 *
 * Rejected alternatives, checked against the real API surface of this Skiko build:
 *  - `graphicsLayer { renderEffect = ... }`: `RenderEffect` is Android-only in Compose; there is
 *    no `renderEffect` parameter on desktop/wasm, so it cannot be the cross-target path.
 *  - Draw into a `Picture`/`Image` first: an extra full-size bitmap per frame plus a second
 *    pass; `saveLayer` lets Skia choose the backing store.
 *  - `Modifier.Node`/`DrawModifierNode`: works (same `saveLayer` call) but needs `onDetach` to
 *    close ref-counted native handles; the shared cache below is simpler and does the same job.
 *  - A separable two-pass box: unnecessary. `makeMatrixConvolution` only touches
 *    `kernelW × kernelH` = `1 × N` coefficients per pixel, so a 1×N kernel *is* the separable
 *    fast path — there is no second pass left to save.
 */

/**
 * Sigma quantization for the Gaussian path so a per-frame radius wobble does not allocate a
 * filter every frame. 0.25 px is below one visible step at 1× density. The box path needs no
 * equivalent: its kernel is keyed by an integer tap count, quantized by construction.
 */
private const val SIGMA_STEP_PX = 0.25f

actual fun blurForVelocity(velocityDpPerSec: Float): Float = velocityToBlurRadiusDp(velocityDpPerSec)

actual fun Modifier.motionBlur(radiusDp: Float, kernel: MotionBlurKernel): Modifier {
    // §11: zero radius must cost nothing — no layer, no paint, no draw-phase work at all.
    if (radiusDp <= 0f) return this
    return this.drawWithContent {
        // dp → px with the *draw-time* density, so the blur follows display scaling.
        drawIntoCanvas { composeCanvas ->
            val canvas = composeCanvas.nativeCanvas
            val paint = when (kernel) {
                MotionBlurKernel.Box -> SkiaMotionPaints.forBox(boxKernelTaps(radiusDp, density))
                MotionBlurKernel.Triangle ->
                    SkiaMotionPaints.forTriangle(boxKernelTaps(radiusDp, density))
                MotionBlurKernel.Gaussian -> SkiaMotionPaints.forGaussian(
                    sigmaX = quantize(radiusDp * density * MOTION_BLUR_SIGMA_X_PER_RADIUS),
                    sigmaY = quantize(radiusDp * density * MOTION_BLUR_SIGMA_Y_PER_RADIUS),
                )
            }
            canvas.saveLayer(null, paint)
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
 * Why not allocate inside `drawWithContent`: both `makeBlur` and `makeMatrixConvolution` allocate
 * a ref-counted native handle, and a scroll asks for a new radius every frame. §11 caps this at
 * one blurred element at a time and Compose draws on a single thread, so a one-entry cache is
 * sufficient and race-free. The key carries the **kernel kind** as well as its parameters — a box
 * convolution and a Gaussian are different filters, and serving one from the other's slot would
 * silently paint the wrong effect at the same radius.
 *
 * The old pair is closed before the new one is created; the last pair lives until process exit —
 * a single bounded allocation (RESULT.md §已知限制).
 */
private object SkiaMotionPaints {
    private var paint: Paint? = null
    private var filter: ImageFilter? = null
    private var key: Any? = null

    /** 1×[taps] box convolution, all weights `1/taps`, `gain = 1` so brightness is preserved. */
    fun forBox(taps: Int): Paint {
        val wanted = BoxKey(taps)
        paint?.let { if (key == wanted) return it }
        return replace(wanted) {
            ImageFilter.makeMatrixConvolution(
                kernelW = 1,
                kernelH = taps,
                kernel = boxKernelWeights(taps),
                gain = 1f,
                bias = 0f,
                // Only the vertical axis is convolved: X has a single tap, so both offsets are
                // just the kernel centre — `taps / 2` down, `0` across.
                offsetX = 0,
                offsetY = taps / 2,
                // CLAMP, not DECAL: the layer is exactly the node's bounds, and DECAL would fade
                // the outermost rows toward transparent instead of extending the edge pixels.
                tileMode = FilterTileMode.CLAMP,
                convolveAlpha = true,
                // No chained input and no crop: this filter is the root of the layer's chain, and
                // Skiko 0.150.2 declares both parameters without defaults, so they must be explicit.
                input = null,
                crop = null,
            )
        }
    }

    /** Asymmetric Gaussian fallback — directional, but bell-profiled (see the file KDoc). */
    fun forGaussian(sigmaX: Float, sigmaY: Float): Paint {
        val wanted = GaussianKey(sigmaX, sigmaY)
        paint?.let { if (key == wanted) return it }
        return replace(wanted) {
            ImageFilter.makeBlur(sigmaX = sigmaX, sigmaY = sigmaY, mode = FilterTileMode.CLAMP)
        }
    }

    /**
     * 1×[taps] triangle (Bartlett) convolution — the tapered streak.
     *
     * Same call as [forBox] with a different weight vector, so the cost is identical: Skia still
     * evaluates `1 × N` coefficients per pixel. Only the *shape* changes, and that is the whole
     * experiment (RESULT.md §三.6): the rectangle's −13 dB sidelobes are what turn a 4–7 px glyph
     * cross-bar pitch into an echo; the triangle's −25 dB removes it.
     */
    fun forTriangle(taps: Int): Paint {
        val wanted = TriangleKey(taps)
        paint?.let { if (key == wanted) return it }
        return replace(wanted) {
            ImageFilter.makeMatrixConvolution(
                kernelW = 1,
                kernelH = taps,
                kernel = triangleKernelWeights(taps),
                gain = 1f,
                bias = 0f,
                offsetX = 0,
                offsetY = taps / 2,
                tileMode = FilterTileMode.CLAMP,
                convolveAlpha = true,
                input = null,
                crop = null,
            )
        }
    }

    private inline fun replace(newKey: Any, create: () -> ImageFilter): Paint {
        filter?.close()
        paint?.close()
        val created = create()
        filter = created
        return Paint().also {
            it.imageFilter = created
            paint = it
            key = newKey
        }
    }

    /**
     * Value-equal keys: the cache must hit when the *parameters* repeat, even though the call
     * site builds a fresh key object every frame. `data class` gives that for free.
     */
    private data class BoxKey(val taps: Int)
    private data class TriangleKey(val taps: Int)
    private data class GaussianKey(val sigmaX: Float, val sigmaY: Float)
}
