// g-float regression tests: the floating pill's outer halo must FADE — monotonic,
// continuous, no staircase, no border — never the hard-edged grey slab / ringed
// border the user reported (「一坨灰块」 → 「阴影为什么有边框」).
//
// The halo now has two constructions, both pinned here:
//  1. real gaussian blur (MaskFilter, one blurred draw) — the shipped path on all
//     three targets; the ink factor keeps the *below-edge* peak inside the budget;
//  2. a no-MaskFilter fallback of 40 overlapping filled layers — coverage-accumulated
//     ramp whose per-layer edge step is a fraction of one gray level.
//
// The old 12 disjoint ring-difference bands (inverted ramp, ~25/255 outer step) are
// gone; these tests keep them gone.

package vade.ui.component.liquid

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import vade.ui.shell.FloatingHaloAlphaDark
import vade.ui.shell.FloatingHaloAlphaLight

class HaloFalloffTest {

    // --- (1) the fade is monotonic, outward-fading -----------------------------------

    @Test
    fun cumulativeFalloffIsMonotonicallyNonIncreasingOutward() {
        for (strength in listOf(0.03f, 0.05f, 0.1f, 1f)) {
            var previous = Float.MAX_VALUE
            for (coverage in HALO_FALLBACK_LAYERS downTo 0) {
                val alpha = haloCumulativeAlpha(strength, coverage, HALO_FALLBACK_LAYERS)
                assertTrue(alpha <= previous + 1e-6f, "coverage $coverage rose: $alpha > $previous @ strength=$strength")
                previous = alpha
            }
        }
    }

    @Test
    fun fullCoveragePeaksAtExactlyTheStrengthAndZeroCoverageIsZero() {
        assertEquals(0.03f, haloCumulativeAlpha(0.03f, HALO_FALLBACK_LAYERS, HALO_FALLBACK_LAYERS), 1e-4f)
        assertEquals(0.05f, haloCumulativeAlpha(0.05f, HALO_FALLBACK_LAYERS, HALO_FALLBACK_LAYERS), 1e-4f)
        assertEquals(0f, haloCumulativeAlpha(0.03f, 0, HALO_FALLBACK_LAYERS), 1e-6f)
        assertEquals(0f, haloLayerAlpha(0f, HALO_FALLBACK_LAYERS))
    }

    // --- (2) no staircase ------------------------------------------------------------

    @Test
    fun fallbackLayerEdgeStepStaysUnderTwoGrayLevels() {
        // Every filled layer's own antialiased edge is the smallest visible step;
        // 40 layers × 0.25dp keep it a fraction of one gray level at card strengths.
        for (strength in listOf(0.03f, 0.05f, 0.11f)) {
            val step = haloLayerAlpha(strength, HALO_FALLBACK_LAYERS) * 255f
            assertTrue(step <= 2f, "layer edge step $step > 2/255 @ strength $strength")
        }
    }

    @Test
    fun fallbackPerLayerWidthStaysUnderZeroPointFourDpAtThePillRadius() {
        val layerWidthDp = 10f / HALO_FALLBACK_LAYERS
        assertTrue(layerWidthDp <= 0.4f, "fallback layer width ${layerWidthDp}dp > 0.4dp")
    }

    @Test
    fun adjacentCumulativeStepStaysUnderStrengthOverSix() {
        for (strength in listOf(0.03f, 0.05f, 0.11f)) {
            val step = abs(
                haloCumulativeAlpha(strength, HALO_FALLBACK_LAYERS, HALO_FALLBACK_LAYERS) -
                    haloCumulativeAlpha(strength, HALO_FALLBACK_LAYERS - 1, HALO_FALLBACK_LAYERS),
            )
            assertTrue(step <= strength / 6f + 1e-6f, "step $step > strength/6 @ $strength")
        }
    }

    @Test
    fun fallbackIsAtLeastTwoLayers() {
        assertTrue(HALO_FALLBACK_LAYERS >= 2, "fallback must stack ≥2 layers, got $HALO_FALLBACK_LAYERS")
    }

    // --- (3) ink budget (light + dark, blur path) ------------------------------------

    @Test
    fun pillLightPeakStaysInsideTheThreePercentBudget() {
        // 0.03 × 255 = 7.65 ≤ 8 (3 % of 255).
        assertTrue(FloatingHaloAlphaLight * 255f <= 8f, "light halo peak ${FloatingHaloAlphaLight * 255f} > 8/255")
    }

    @Test
    fun blurBelowEdgePeakStaysInsideTheBudget() {
        // The gaussian's visible maximum sits just BELOW the shape edge (the offset
        // pushes it there): Φ(offset/σ)·ink with offset 5dp, σ 5dp → 0.8413·ink.
        // ink = strength × HALO_BLUR_INK_FACTOR must keep that peak ≤ 8/255.
        val peak = FloatingHaloAlphaLight * HALO_BLUR_INK_FACTOR * 0.8413f * 255f
        assertTrue(peak <= 8f, "blur below-edge peak $peak > 8/255")
    }

    @Test
    fun darkHaloStaysUnderTheSpecCapAndTheBudget() {
        assertTrue(FloatingHaloAlphaDark <= 0.08f, "dark halo alpha ${FloatingHaloAlphaDark} > 0.08 cap")
        assertTrue(FloatingHaloAlphaDark <= FloatingHaloAlphaLight * 2f, "dark halo must stay within 2× light")
        // Black ink on the dark canvas (#0E0E10 ≈ 14/255): the visible delta.
        assertTrue(FloatingHaloAlphaDark * 14f <= 8f, "dark-on-dark delta ${FloatingHaloAlphaDark * 14f} > 8/255")
    }
}
