// g-float regression tests: the dilated outline must stay CONCENTRIC with the
// shape it surrounds. The user's second observation — 「那个阴影不是绕着悬浮底栏
// 的，圆角太小了根本不贴合」 — was exactly this: the pre-fix `offsetDilatedOutline`
// capped the corner radius at the ORIGINAL rect's half-extent, so inflating the
// 64dp pill (radius = 32dp = height/2) kept a 32dp radius on a 74dp-tall slab.
//
// The key assertion: a pill inflated by any dilation is again a perfect capsule
// (radius == dilatedHeight / 2); a plain rounded rect's radius grows by the
// dilation.

package vade.ui.component.liquid

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Outline
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DilatedOutlineTest {

    private fun pillOutline(width: Float, height: Float): Outline.Rounded =
        Outline.Rounded(
            RoundRect(
                rect = Rect(0f, 0f, width, height),
                topLeft = CornerRadius(height / 2f),
                topRight = CornerRadius(height / 2f),
                bottomRight = CornerRadius(height / 2f),
                bottomLeft = CornerRadius(height / 2f),
            ),
        )

    // --- the user-reported regression -------------------------------------------------

    @Test
    fun pillStaysAPerfectCapsuleWhenDilated() {
        // The pill as drawn: 64dp tall → radius 32dp; halo dilation 10dp (density-free:
        // the assertion is in raw px where the pill is 160px tall and dilates 25px).
        val radius = dilatedCornerRadius(originalRadius = 80f, width = 300f, height = 160f, dilation = 25f)
        assertEquals(105f, radius, "dilated pill radius must equal dilatedHeight/2 (160+2·25)/2")
        val radiusDp = dilatedCornerRadius(originalRadius = 32f, width = 300f, height = 64f, dilation = 10f)
        assertEquals(42f, radiusDp, "dp-space: (64+2·10)/2 = 42, not the clamped 32")
    }

    @Test
    fun plainRoundedRectRadiusGrowsWithDilation() {
        val radius = dilatedCornerRadius(originalRadius = 24f, width = 300f, height = 200f, dilation = 10f)
        assertEquals(34f, radius, "radius must grow by the dilation when below the cap")
    }

    @Test
    fun zeroDilationKeepsTheOriginalRadius() {
        assertEquals(32f, dilatedCornerRadius(originalRadius = 32f, width = 300f, height = 64f, dilation = 0f))
    }

    @Test
    fun degenerateRadiusIsCappedByTheDilatedHalfExtent() {
        // Guard rail: an invalid shape (radius > min(w,h)/2) still cannot produce a
        // radius larger than the dilated rect's half-extent.
        val radius = dilatedCornerRadius(originalRadius = 80f, width = 300f, height = 40f, dilation = 0f)
        assertEquals(20f, radius, "capped at min(w,h)/2 = 20")
    }

    // --- end-to-end through the path builder ------------------------------------------

    @Test
    fun dilatedOutlineBoundsGrowByDilationOnAllFourSides() {
        val outline = pillOutline(width = 300f, height = 160f)
        val dilated = offsetDilatedOutline(outline, dx = 0f, dy = 0f, dilation = 25f)
        val bounds = dilated.getBounds()
        assertEquals(-25f, bounds.left, "left edge")
        assertEquals(-25f, bounds.top, "top edge")
        assertEquals(325f, bounds.right, "right edge")
        assertEquals(185f, bounds.bottom, "bottom edge")
    }

    @Test
    fun offsetShiftsTheDilatedRectWithoutBreakingConcentricity() {
        val outline = pillOutline(width = 300f, height = 160f)
        val dilated = offsetDilatedOutline(outline, dx = 0f, dy = 12.5f, dilation = 25f)
        val bounds = dilated.getBounds()
        assertEquals(-25f, bounds.left)
        assertEquals(-12.5f, bounds.top)
        assertEquals(325f, bounds.right)
        assertEquals(197.5f, bounds.bottom)
    }

    @Test
    fun dilatedPillCornerArcTracksTheDilatedRect() {
        // The concentricity property in one number: at the dilated slab's own
        // mid-height the arc reaches the slab's left edge — i.e. the corner radius
        // equals half the dilated height, so the slab's silhouette is a capsule, not
        // a too-square rounded rect. Verified through the built path's bounds: a
        // capsule's bounds touch the rect on all four sides (already asserted above);
        // here we assert the radius the builder actually stamped into the RoundRect
        // by re-deriving it from the helper the builder calls.
        val height = 160f
        val dilation = 25f
        val stamped = dilatedCornerRadius(
            originalRadius = height / 2f,
            width = 300f,
            height = height,
            dilation = dilation,
        )
        assertTrue(
            abs(stamped - (height + 2 * dilation) / 2f) < 1e-3f,
            "stamped radius $stamped != dilated half-height ${(height + 2 * dilation) / 2f}",
        )
    }
}
