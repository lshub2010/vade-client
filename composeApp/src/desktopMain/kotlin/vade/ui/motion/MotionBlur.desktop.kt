package vade.ui.motion

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Paint

/**
 * Desktop (Skiko) actual for §11 motion blur.
 *
 * Path, exactly as §11 prescribes for desktop/wasm: `Modifier.drawWithContent` +
 * `drawIntoCanvas { it.nativeCanvas }` + `Canvas.saveLayer(null, paint)` where the paint carries
 * `ImageFilter.makeBlur(sigmaX, sigmaY, FilterTileMode.CLAMP)`. Skia applies a layer paint's
 * image filter when `restore()` composites the layer, so everything the wrapped node drew is
 * blurred as one image. Layout and hit-testing are untouched (§11: 模糊层不改变布局与命中区域).
 *
 * Rejected alternatives, checked against the real API surface of this Skiko build:
 *  - `graphicsLayer { renderEffect = ... }`: `RenderEffect` is Android-only in Compose; there is
 *    no `renderEffect` parameter on desktop/wasm, so it cannot be the cross-target path.
 *  - Draw into a `Picture`/`Image` first: an extra full-size bitmap per frame plus a second
 *    pass; `saveLayer` lets Skia choose the backing store.
 *  - `Modifier.Node`/`DrawModifierNode`: works (same `saveLayer` call) but needs `onDetach` to
 *    close ref-counted native handles; the shared cache below is simpler and does the same job.
 */

/**
 * Radius → Gaussian sigma. Android's `Modifier.blur(radius)` hands the radius (px) to
 * `RenderEffect.createBlurEffect`, whose parameter behaves like a sigma; Skia's `makeBlur` takes
 * sigma directly. 0.5 puts 10 dp on desktop in the same visual family as 10 dp on Android
 * instead of being roughly twice as soft. Judged by eye against `shots/motion/freeze-10.png`;
 * it is a look, not a measured equivalence — see RESULT.md §已知限制.
 */
private const val SIGMA_PER_RADIUS = 0.5f

/** Sigma quantization so a per-frame radius wobble does not allocate a filter every frame. */
private const val SIGMA_STEP_PX = 0.25f

actual fun blurForVelocity(velocityDpPerSec: Float): Float = velocityToBlurRadiusDp(velocityDpPerSec)

actual fun Modifier.motionBlur(radiusDp: Float): Modifier {
    // §11: zero radius must cost nothing — no layer, no paint, no draw-phase work at all.
    if (radiusDp <= 0f) return this
    return this.drawWithContent {
        // dp → px with the *draw-time* density, so the blur follows display scaling.
        val raw = radiusDp * density * SIGMA_PER_RADIUS
        val sigma = (kotlin.math.round(raw / SIGMA_STEP_PX) * SIGMA_STEP_PX).coerceAtLeast(SIGMA_STEP_PX)
        drawIntoCanvas { composeCanvas ->
            val canvas = composeCanvas.nativeCanvas
            canvas.saveLayer(null, SkiaBlurPaints.forSigma(sigma))
            drawContent()
            canvas.restore()
        }
    }
}

/**
 * One cached `Paint` + `ImageFilter` pair for the whole process.
 *
 * Why not allocate inside `drawWithContent`: `ImageFilter.makeBlur` allocates a ref-counted
 * native handle, and a scroll asks for a new radius every frame. §11 caps this at one blurred
 * element at a time and Compose draws on a single thread, so a one-entry cache keyed by the
 * quantized sigma is sufficient and race-free. The old pair is closed before the new one is
 * created; the last pair lives until process exit — a single bounded allocation (RESULT.md
 * §已知限制).
 */
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
