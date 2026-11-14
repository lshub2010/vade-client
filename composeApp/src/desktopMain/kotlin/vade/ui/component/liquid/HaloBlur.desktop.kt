// g-float desktop actual: skiko's MaskFilter (the same mechanism the MotionBlur
// batch proved renders on the desktop backend).

package vade.ui.component.liquid

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.MaskFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.RRect

internal actual fun DrawScope.drawBlurredHalo(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    cornerRadius: Float,
    dx: Float,
    dy: Float,
    colorArgb: Int,
    blurSigmaPx: Float,
): Boolean {
    drawIntoCanvas { composeCanvas ->
        val canvas = composeCanvas.nativeCanvas
        canvas.drawRRect(
            RRect.makeLTRB(
                left + dx,
                top + dy,
                right + dx,
                bottom + dy,
                cornerRadius,
                cornerRadius,
            ),
            SkiaHaloPaints.forColorSigma(colorArgb, blurSigmaPx),
        )
    }
    return true
}

/**
 * σ is passed straight to `MaskFilter.makeBlur` (skiko's parameter IS σ, unlike
 * android's radius). Paint + filter are ref-counted natives, so they are cached on
 * the (colour, σ) key — the same discipline as SkiaMotionPaints.
 */
private object SkiaHaloPaints {
    private var key: Pair<Int, Float>? = null
    private var paint: Paint? = null

    fun forColorSigma(colorArgb: Int, blurSigmaPx: Float): Paint {
        val wanted = Pair(colorArgb, blurSigmaPx)
        paint?.let { cached -> if (key == wanted) return cached }
        paint?.close()
        val created = Paint().also { p ->
            p.color = colorArgb
            p.maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, blurSigmaPx)
        }
        paint = created
        key = wanted
        return created
    }
}
