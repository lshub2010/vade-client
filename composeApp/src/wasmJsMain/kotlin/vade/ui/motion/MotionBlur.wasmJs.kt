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
 * `ImageFilter.makeBlur(sigma, sigma, FilterTileMode.CLAMP)` on the layer paint.
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

private const val SIGMA_PER_RADIUS = 0.5f
private const val SIGMA_STEP_PX = 0.25f

actual fun blurForVelocity(velocityDpPerSec: Float): Float = velocityToBlurRadiusDp(velocityDpPerSec)

actual fun Modifier.motionBlur(radiusDp: Float): Modifier {
    if (radiusDp <= 0f) return this
    return this.drawWithContent {
        val raw = radiusDp * density * SIGMA_PER_RADIUS
        val sigma = (round(raw / SIGMA_STEP_PX) * SIGMA_STEP_PX).coerceAtLeast(SIGMA_STEP_PX)
        drawIntoCanvas { composeCanvas ->
            val canvas = composeCanvas.nativeCanvas
            canvas.saveLayer(null, SkiaBlurPaints.forSigma(sigma))
            drawContent()
            canvas.restore()
        }
    }
}

private object SkiaBlurPaints {
    private var paint: Paint? = null
    private var filter: ImageFilter? = null
    private var sigma: Float = Float.NaN

    fun forSigma(nextSigma: Float): Paint {
        paint?.let { if (nextSigma == sigma) return it }
        filter?.close()
        paint?.close()
        val created = ImageFilter.makeBlur(
            sigmaX = nextSigma,
            sigmaY = nextSigma,
            mode = FilterTileMode.CLAMP,
        )
        filter = created
        return Paint().also {
            it.imageFilter = created
            paint = it
            sigma = nextSigma
        }
    }
}
