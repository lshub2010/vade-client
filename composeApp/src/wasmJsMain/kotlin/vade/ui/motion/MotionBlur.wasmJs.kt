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
 * Wasm (Skiko/CanvasKit) actual for §11 motion blur.
 *
 * Same technique as `MotionBlur.desktop.kt`, because wasm and desktop share the skiko backend:
 * `Modifier.drawWithContent` + `drawIntoCanvas { it.nativeCanvas }` + `saveLayer` with an
 * `ImageFilter` on the layer paint. Both kernels are available here for the same reason as on
 * desktop — [MotionBlurKernel.Box] via `ImageFilter.makeMatrixConvolution` (a 1×N rectangle, i.e.
 * the integral of the motion over the exposure) and [MotionBlurKernel.Gaussian] via
 * `makeBlur(sigmaX, sigmaY)` with the asymmetric pair, which is directional but bell-profiled.
 *
 * Kept as a separate file rather than hoisted into a shared source set on purpose: the two
 * targets stay independently compilable, so if CanvasKit ever diverges (e.g. `saveLayer` is
 * missing or `makeMatrixConvolution` is a no-op there) only this file degrades and desktop keeps
 * working. RESULT.md records which of the two was actually photographed.
 *
 * §11 requires wasm to be able to turn this OFF (`[ui].motion_blur`); the switch lives at the
 * call site (`MotionProbe`'s `blurEnabled`), not here — `radiusDp = 0` is the single off-path and
 * it returns `this` with no layer at all.
 */

private const val SIGMA_STEP_PX = 0.25f

actual fun blurForVelocity(velocityDpPerSec: Float): Float = velocityToBlurRadiusDp(velocityDpPerSec)

actual fun Modifier.motionBlur(radiusDp: Float, kernel: MotionBlurKernel): Modifier {
    if (radiusDp <= 0f) return this
    return this.drawWithContent {
        drawIntoCanvas { composeCanvas ->
            val canvas = composeCanvas.nativeCanvas
            val paint = when (kernel) {
                MotionBlurKernel.Box -> SkiaMotionPaints.forBox(boxKernelTaps(radiusDp, density))
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

/** Same quantization rule as desktop: never 0, so the token axis stays a real blur. */
private fun quantize(sigma: Float): Float =
    (round(sigma / SIGMA_STEP_PX) * SIGMA_STEP_PX).coerceAtLeast(SIGMA_STEP_PX)

/** Same one-entry, kernel-aware cache as desktop — see that file for the reasoning. */
private object SkiaMotionPaints {
    private var paint: Paint? = null
    private var filter: ImageFilter? = null
    private var key: Any? = null

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
                offsetX = 0,
                offsetY = taps / 2,
                tileMode = FilterTileMode.CLAMP,
                convolveAlpha = true,
                input = null,
                crop = null,
            )
        }
    }

    fun forGaussian(sigmaX: Float, sigmaY: Float): Paint {
        val wanted = GaussianKey(sigmaX, sigmaY)
        paint?.let { if (key == wanted) return it }
        return replace(wanted) {
            ImageFilter.makeBlur(sigmaX = sigmaX, sigmaY = sigmaY, mode = FilterTileMode.CLAMP)
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

    private data class BoxKey(val taps: Int)
    private data class GaussianKey(val sigmaX: Float, val sigmaY: Float)
}
