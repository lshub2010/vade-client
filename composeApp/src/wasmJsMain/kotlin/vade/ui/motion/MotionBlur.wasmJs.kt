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
 * `Modifier.drawWithContent` + `drawIntoCanvas { it.nativeCanvas }` + `saveLayer` with
 * `ImageFilter.makeBlur(sigmaX, sigmaY, FilterTileMode.CLAMP)` on the layer paint. The two
 * sigmas are asymmetric for the same reason as on desktop — the smear must be directional
 * (mostly Y), not isotropic defocus; see [MOTION_BLUR_SIGMA_X_PER_RADIUS] /
 * [MOTION_BLUR_SIGMA_Y_PER_RADIUS].
 *
 * Kept as a separate file rather than hoisted into a shared source set on purpose: the two
 * targets stay independently compilable, so if CanvasKit ever diverges (e.g. `saveLayer` is
 * missing or `makeBlur` is a no-op there) only this file degrades and desktop keeps working.
 * RESULT.md records which of the two was actually photographed.
 *
 * §11 requires wasm to be able to turn this OFF (`[ui].motion_blur`); the switch lives at the
 * call site (`MotionProbe`'s `blurEnabled`), not here — `radiusDp = 0` is the single off-path and
 * it returns `this` with no layer at all.
 */

private const val SIGMA_STEP_PX = 0.25f

actual fun blurForVelocity(velocityDpPerSec: Float): Float = velocityToBlurRadiusDp(velocityDpPerSec)

actual fun Modifier.motionBlur(radiusDp: Float): Modifier {
    if (radiusDp <= 0f) return this
    return this.drawWithContent {
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

/** Same quantization rule as desktop: never 0, so the token axis stays a real blur. */
private fun quantize(sigma: Float): Float =
    (round(sigma / SIGMA_STEP_PX) * SIGMA_STEP_PX).coerceAtLeast(SIGMA_STEP_PX)

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
