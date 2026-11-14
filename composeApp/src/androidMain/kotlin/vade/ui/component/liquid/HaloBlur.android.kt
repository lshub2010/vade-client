// g-float Android actual: android.graphics.BlurMaskFilter through the framework
// paint. BlurMaskFilter is in Android's hardware-acceleration supported set (the
// unsupported list covers setShadowLayer-on-non-text, setDither, drawLoops… not
// MaskFilter), and this was re-verified by rendering on the API 30 real device
// with a scanline (see shots/g-float/RESULT.md).

package vade.ui.component.liquid

import android.graphics.BlurMaskFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas

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
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    paint.color = colorArgb
    // android's "radius" maps to σ ≈ 0.57735·radius + 0.5 (SkBlurMask
    // ConvertRadiusToSigma) — invert it so every platform gets the same σ.
    val radius = ((blurSigmaPx - 0.5f) / 0.57735f).coerceIn(0.5f, 250f)
    paint.maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
    val path = android.graphics.Path()
    path.addRoundRect(
        left + dx,
        top + dy,
        right + dx,
        bottom + dy,
        cornerRadius,
        cornerRadius,
        android.graphics.Path.Direction.CW,
    )
    drawContext.canvas.nativeCanvas.drawPath(path, paint)
    return true
}
