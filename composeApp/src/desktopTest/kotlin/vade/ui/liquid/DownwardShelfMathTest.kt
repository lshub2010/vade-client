// g-shadow-android regression guards — PURE MATH over the downward-shelf bands (no
// canvas, no pixels): the three assertions the task pins plus the budget/monotonicity
// contracts the 第 1.5 级 calibration established. See shots/g-shadow-android/RESULT.md.
//
// Why these must stay pure math: the g-shadow-real run proved pixel measurements can be
// fooled (sample-row traps, quantisation surprises); the shelf's *contract* — something
// lands below, nothing lands above, the peak fits the 8-bit budget — is a property of
// the band list itself and belongs next to the code that builds it.

package vade.ui.component.liquid

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownwardShelfMathTest {

    /** Composer geometry on the 420 dpi bucket: `xl` = 28 dp corners. */
    private val radiusPx = 28f * 2.625f
    private val pxPerDp = 2.625f

    private fun floatBands(dark: Boolean = false, trimToBudget: Boolean = true): List<ShelfBand> =
        shelfBands(VadeShadows.float.layers, radiusPx, pxPerDp, dark = dark, trimToBudget = trimToBudget)

    /** Per-depth quantised composite (gray levels /255) — what an 8-bit surface shows. */
    private fun quantizedInkAt(bands: List<ShelfBand>, depthPx: Float): Float {
        var keep = 1f
        for (band in bands) {
            val t = (depthPx / band.heightPx).coerceIn(0f, 1f)
            val q = (band.alpha0 * (1f - t) * 255f).roundToInt().coerceIn(0, 255)
            keep *= 1f - q / 255f
        }
        return 1f - keep
    }

    // --- the three task-mandated assertions ---------------------------------------------

    @Test
    fun shadowPeakBelowIsAtLeastOneQuantum() {
        val peak = shelfPeakBelow(floatBands()) * 255f
        assertTrue(peak >= 1f, "float shelf peak $peak/255 < 1 — the shadow paints nothing")
    }

    @Test
    fun shadowLeakAboveStaysSubQuantum() {
        val leak = shelfLeakAbove(floatBands()) * 255f
        assertTrue(leak <= 1.5f, "shelf leaks $leak/255 above the element — §8.3 上方不得有阴影")
        assertEquals(0f, leak, "the shelf paints strictly below the bottom edge — leak must be exactly 0")
    }

    @Test
    fun shelfKeepsItsLayers() {
        assertTrue(shelfBandCount(floatBands()) > 0, "the shelf lost every band")
        assertTrue(
            shelfBandCount(floatBands()) >= 2,
            "§8.1 needs a multi-layer gradient, got ${shelfBandCount(floatBands())} band(s)",
        )
    }

    // --- the 第 1.5 级 calibration contracts ---------------------------------------------

    @Test
    fun quantizedPeakStaysInsideTheThreePercentBudget() {
        val peak = shelfQuantizedPeak(floatBands()) * 255f
        assertTrue(
            peak <= SHELF_PEAK_BUDGET_QUANTA,
            "quantised float peak $peak/255 exceeds the ≤8/255 (3%) budget — " +
                "the trim must absorb token changes",
        )
    }

    @Test
    fun withoutTheTrimTheFloatTokenBubblesOverTheBudget() {
        // Pins WHY the trim exists: with the token's alpha ratios the raw composite lands
        // on {6, 9, 10}/255 — 8 is unreachable, so an untrimmed float shelf measures 10
        // on the emulator (probe-v2.png, shelf-noTrim). If this ever goes <= budget the
        // trim became a no-op and must be re-derived.
        val peak = shelfQuantizedPeak(floatBands(trimToBudget = false)) * 255f
        assertTrue(peak > SHELF_PEAK_BUDGET_QUANTA, "untrimmed peak $peak — the trim is dead code?")
    }

    @Test
    fun quantizedFalloffIsMonotoneWithoutStaircaseSteps() {
        val bands = floatBands()
        var previous = quantizedInkAt(bands, 0f)
        for (depth in 1..120) {
            val ink = quantizedInkAt(bands, depth.toFloat())
            assertTrue(
                ink <= previous + 1e-6f,
                "quantised ink rises at depth ${depth}px: $previous -> $ink — the falloff must be monotone",
            )
            assertTrue(
                previous - ink <= 2.5f / 255f,
                "quantised ink steps ${(previous - ink) * 255f}/255 in one pixel at depth ${depth}px — 阶梯",
            )
            previous = ink
        }
    }

    @Test
    fun falloffSpansAtLeastThreeVisibleLevelsBelowTheElement() {
        val bands = floatBands()
        val levels = (1..150)
            .map { depth -> (quantizedInkAt(bands, depth.toFloat()) * 255f).roundToInt() }
            .toSet()
        assertTrue(levels.size >= 3, "the falloff shows only $levels distinct levels — a slab, not a fade")
    }

    @Test
    fun darkShelfKeepsAllLayersAndTracksTheVisibleDeltaBudget() {
        val dark = floatBands(dark = true)
        assertEquals(VadeShadows.float.forDark().layers.size, dark.size, "dark shelf lost bands to the trim")
        // On the #0E0E10 canvas the visible delta is ink × 14/255 — the ≤8/255 budget applies
        // to the DELTA (CardShadowPolicyTest's dark convention), not to the raw ink.
        val delta = shelfQuantizedPeak(dark) * 255f * (14f / 255f)
        assertTrue(delta <= SHELF_PEAK_BUDGET_QUANTA, "dark shelf visible delta $delta/255 over budget")
    }

    @Test
    fun bandsNeverExceedTheirOwnHalfHeightCorner() {
        // A radius above half the band height makes Skia rescale the radii — the band's
        // top corners would square off against the element's curl.
        for (band in floatBands()) {
            assertTrue(
                band.radiusPx <= band.heightPx / 2f + 1e-3f,
                "band radius ${band.radiusPx} > height/2 ${band.heightPx / 2f} — Skia would rescale",
            )
        }
    }
}
