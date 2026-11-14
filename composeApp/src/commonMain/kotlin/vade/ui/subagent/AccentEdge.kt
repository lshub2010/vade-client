// Card left accent edge — docs/visual-language.md §13.
//
// The bar is NOT a 3dp rectangle taped to the edge (§13.4 bans exactly that). It is a
// variable-width ribbon along the card's left edge:
//   · top: starts at the corner-arc start (width 0), follows the topLeft arc (bends with
//     the rounded corner), ramps 0 → 3dp within the first 8dp of path length;
//   · middle: full 3dp, straight;
//   · bottom: ramps 3dp → 0 over the last 12dp and vanishes at ~65% of the card height
//     (inside the §13.2 60–70% window) — it never reaches the bottom edge.
// Tapering is done by NARROWING THE PATH (two edges closing together), not by alpha —
// alpha fade would be "变淡", not "变细" (§13.4). The path is clipped to the card shape
// so the arc section never spills outside the rounded corner.

package vade.ui.subagent

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.Modifier
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Max ribbon width (§13.2). */
private val EDGE_MAX_WIDTH: Dp = 3.dp

/** Top ramp: 0 → 3dp within the first 8dp (§13.2). */
private val EDGE_TOP_RAMP: Dp = 8.dp

/** Bottom ramp: 3dp → 0 over the last 12dp (§13.2). */
private val EDGE_BOTTOM_RAMP: Dp = 12.dp

/** Vanishing point: 65% of the card height (§13.2: 60–70%, never the bottom edge). */
private const val EDGE_VANISH_FRACTION = 0.65f

/** Corner-arc polyline resolution — smooth at any sane card size. */
private const val ARC_STEPS = 32

/**
 * Draw the state-coloured left edge behind the content. [corner] must be the card's
 * topLeft corner radius (pass the same value the card shape uses) so the ribbon follows
 * the arc exactly.
 */
fun Modifier.accentEdge(color: Color, corner: Dp, shape: Shape): Modifier = drawWithContent {
    drawAccentEdge(color, corner, shape)
    drawContent()
}

private fun DrawScope.drawAccentEdge(color: Color, corner: Dp, shape: Shape) {
    val path = ribbonPath(size, corner.toPx(), EDGE_MAX_WIDTH.toPx(), EDGE_TOP_RAMP.toPx(), EDGE_BOTTOM_RAMP.toPx())
    if (path == null) return
    val outlinePath = when (val outline = shape.createOutline(size, layoutDirection, this)) {
        is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
        is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
        is Outline.Generic -> outline.path
    }
    clipPath(outlinePath) {
        drawPath(path, color)
    }
}

/**
 * The ribbon polygon in pixel coordinates, or null when the card is too small to show
 * anything meaningful (degenerate guard — an empty path would still be harmless).
 */
private fun ribbonPath(size: Size, cornerPx: Float, maxW: Float, topRamp: Float, bottomRamp: Float): Path? {
    if (size.height <= 0f || size.width <= 0f) return null
    val r = cornerPx.coerceIn(0f, min(size.width, size.height))
    val arcLen = (Math.PI / 2.0).toFloat() * r
    val straightLen = max(0f, size.height - r)
    val totalLen = arcLen + straightLen
    if (totalLen <= 0f) return null
    // Vanishing point: 65% of the card HEIGHT (§13.2's 60–70% window). Convert the target
    // y into path length: on the arc y = r·(1 − cos(d/r)); on the straight edge y = r + (d − arcLen).
    val vanishY = (size.height * EDGE_VANISH_FRACTION).coerceAtLeast(1f)
    val vanishD = if (vanishY <= r && r > 0f) {
        r * Math.acos((1.0 - vanishY / r).coerceIn(-1.0, 1.0)).toFloat()
    } else {
        arcLen + (vanishY - r)
    }.coerceIn(1f, totalLen)

    // Width along the path length d: ramps up over [0, topRamp], holds maxW, ramps down
    // to 0 exactly at vanishD over the last bottomRamp. Both ramps are width (not alpha).
    fun widthAt(d: Float): Float {
        val up = (d / max(topRamp, 1f)).coerceIn(0f, 1f)
        val down = ((vanishD - d) / max(bottomRamp, 1f)).coerceIn(0f, 1f)
        return maxW * up * down
    }

    val path = Path()
    var first = true
    // Outer edge: along the card silhouette (quarter arc, then the straight left edge).
    var d = 0f
    val stepD = vanishD / (ARC_STEPS * 2)
    while (d <= vanishD) {
        val point = outerPoint(d, r, arcLen)
        if (first) {
            path.moveTo(point.first, point.second)
            first = false
        } else {
            path.lineTo(point.first, point.second)
        }
        d += stepD
    }
    // Inner edge back up: silhouette inset by the local width.
    d = vanishD
    while (d >= 0f) {
        val w = widthAt(d)
        val point = innerPoint(d, r, arcLen, w)
        path.lineTo(point.first, point.second)
        d -= stepD
    }
    path.close()
    return path
}

/** Point on the card silhouette at path length d (arc → straight left edge). */
private fun outerPoint(d: Float, r: Float, arcLen: Float): Pair<Float, Float> =
    if (d <= arcLen && r > 0f) {
        val theta = (Math.PI / 2.0) - (d / r) // radians, from the top of the arc down to the left end
        Pair(r + (r * cos(theta)).toFloat(), r - (r * sin(theta)).toFloat())
    } else {
        val y = if (r > 0f) r + (d - arcLen) else d
        Pair(0f, y)
    }

/** The silhouette point inset by width w towards the card interior. */
private fun innerPoint(d: Float, r: Float, arcLen: Float, w: Float): Pair<Float, Float> =
    if (d <= arcLen && r > 0f) {
        val theta = (Math.PI / 2.0) - (d / r)
        val innerR = max(0f, r - w)
        Pair(r + (innerR * cos(theta)).toFloat(), r - (innerR * sin(theta)).toFloat())
    } else {
        val y = if (r > 0f) r + (d - arcLen) else d
        Pair(w, y)
    }
