package vade.ui

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import vade.ui.skin.glass.DEFAULT_MOTION_BLUR_KERNEL
import vade.ui.skin.glass.MOTION_BLUR_MAX_RADIUS_DP
import vade.ui.skin.glass.MOTION_BLUR_SIGMA_X_PER_RADIUS
import vade.ui.skin.glass.MOTION_BLUR_SIGMA_Y_PER_RADIUS
import vade.ui.skin.glass.MotionBlurKernel
import vade.ui.skin.glass.boxKernelTaps
import vade.ui.skin.glass.boxKernelWeights

/**
 * The box kernel, and the shape difference that made it the default.
 *
 * The whole point of this file is that "box vs Gaussian" is decided by a *profile*, not by an
 * adjective: a 1-px tall feature smeared by a box has a flat top, a Gaussian has a bell. These
 * tests compute both profiles numerically and assert the shapes, so the claim in RESULT.md §三
 * is reproducible without re-reading pixels off a screenshot.
 *
 * `boxKernelTaps`/`boxKernelWeights` are `commonMain`, so this also pins the tap count that the
 * desktop and wasm actuals hand to `makeMatrixConvolution`.
 */
class MotionBlurBoxKernelTest {

    // --- tap count ----------------------------------------------------------------------

    @Test
    fun sixDpAtDensityOneIsThirteenTaps() {
        // The documented anchor: 6 dp @ 1× → 2×6+1 = 13. N−1 = 12 px = the smear span.
        assertEquals(13, boxKernelTaps(6f, 1f), "6dp@1x")
        assertEquals(12, boxKernelTaps(6f, 1f) - 1, "smear span in px must be 12")
    }

    @Test
    fun tapsAreAlwaysOdd() {
        // Skia rejects an even kernel dimension, and an even N has no exact centre tap (the blur
        // would shift the content by half a pixel). Sweep the whole usable curve at 3 densities.
        for (density in listOf(1f, 1.5f, 2f)) {
            var radius = 0.05f
            while (radius <= MOTION_BLUR_MAX_RADIUS_DP) {
                val taps = boxKernelTaps(radius, density)
                assertEquals(1, taps % 2, "taps must be odd at r=$radius d=$density (got $taps)")
                assertTrue(taps >= 1, "taps must be >= 1 at r=$radius d=$density (got $taps)")
                radius += 0.05f
            }
        }
    }

    @Test
    fun tapsGrowMonotonicallyWithRadius() {
        var previous = 0
        var radius = 0f
        while (radius <= MOTION_BLUR_MAX_RADIUS_DP) {
            val taps = boxKernelTaps(radius, 1f)
            assertTrue(taps >= previous, "taps must not shrink as radius rises (r=$radius)")
            previous = taps
            radius += 0.05f
        }
        assertTrue(previous >= 13, "the sweep must reach the 6dp cap (got $previous taps)")
    }

    @Test
    fun densityScalesTheSmearLength() {
        // The kernel is in device pixels, so a 2× display smears twice as many px for the same dp.
        assertEquals(13, boxKernelTaps(6f, 1f))
        assertEquals(25, boxKernelTaps(6f, 2f), "6dp@2x = 12px radius → 2*12+1")
        assertEquals(7, boxKernelTaps(3f, 1f), "3dp@1x")
    }

    @Test
    fun zeroAndNonFiniteRadiusDegradeToIdentity() {
        // A 1-tap kernel is the identity: the draw path never sees radius 0 (it returns early),
        // but a NaN must not become a NaN kernel dimension.
        assertEquals(1, boxKernelTaps(0f, 1f))
        assertEquals(1, boxKernelTaps(-3f, 1f))
        assertEquals(1, boxKernelTaps(Float.NaN, 1f))
        assertEquals(1, boxKernelTaps(6f, Float.NaN))
        assertEquals(1, boxKernelTaps(Float.POSITIVE_INFINITY, 1f))
    }

    // --- weights ------------------------------------------------------------------------

    @Test
    fun weightsSumToOneSoBrightnessIsPreserved() {
        // gain=1 in the actual; a kernel summing to anything else would darken/blow out the layer.
        for (taps in listOf(1, 3, 7, 13, 25, 31)) {
            val weights = boxKernelWeights(taps)
            assertEquals(taps, weights.size)
            assertEquals(1f, weights.sum(), 1e-6f, "weights must sum to 1 for $taps taps")
        }
    }

    @Test
    fun weightsAreUniformNotTapered() {
        // The defining property of a box kernel: every tap has equal weight. A tapered profile is
        // a Gaussian, and that is exactly the defect this path fixes.
        val weights = boxKernelWeights(13)
        val expected = 1f / 13f
        for (w in weights) assertEquals(expected, w, 1e-7f, "box taps must be uniform")
    }

    // --- profile shape (the actual A/B argument) ----------------------------------------

    /** Width of the analysis buffer: wide enough for the Gaussian's tails at the 6 dp cap. */
    private val profileWidth = 41

    /** Index of the impulse in the buffer (centre, so both tails are visible). */
    private val profileCentre = profileWidth / 2

    /**
     * A 1-px tall impulse smeared by an N-tap box: every output row in the span gets `1/N`.
     * Flat, with abrupt ends.
     */
    private fun boxProfile(taps: Int): DoubleArray {
        val weights = boxKernelWeights(taps).map { it.toDouble() }
        val out = DoubleArray(profileWidth)
        // Skia's kernel with offsetY = N/2 samples rows [y−(N/2) … y+(N/2)] with weights[k], so
        // tap k lands at (centre − N/2 + k).
        val start = profileCentre - taps / 2
        for (k in weights.indices) out[start + k] += weights[k]
        return out
    }

    /** Same impulse through a Gaussian with `sigma` px — Skia's `makeBlur` profile, truncated at 3σ. */
    private fun gaussianProfile(sigma: Double): DoubleArray {
        val reach = (sigma * 3).roundToInt()
        val out = DoubleArray(profileWidth)
        var total = 0.0
        val raw = DoubleArray(2 * reach + 1)
        for (k in -reach..reach) {
            val v = exp(-(k * k) / (2 * sigma * sigma))
            raw[k + reach] = v
            total += v
        }
        for (k in raw.indices) out[profileCentre - reach + k] += raw[k] / total
        return out
    }

    @Test
    fun boxProfileHasAFlatTopAndGaussianProfileIsBellShaped() {
        val taps = 13
        // sigmaY at the 6dp cap, density 1, after the 0.25px quantization: 6 × 0.55 = 3.30 → 3.25.
        val sigmaY = 3.25
        val box = boxProfile(taps)
        val gauss = gaussianProfile(sigmaY)

        val boxPeak = box.max()
        val gaussPeak = gauss.max()

        // Flat top: the 13 in-span samples of the box are *all* exactly the peak.
        val boxInSpan = box.filter { it > 1e-12 }
        assertEquals(taps, boxInSpan.size, "the box impulse response must be exactly N wide")
        for (v in boxInSpan) {
            assertEquals(boxPeak, v, 1e-12, "every in-span box tap must equal the peak (plateau)")
        }

        // Bell: a Gaussian's peak sits well above its own 3σ tail, and it spreads past the box.
        val gaussInSpan = gauss.filter { it > 1e-9 }
        assertTrue(
            gaussInSpan.size > taps,
            "the Gaussian must spread wider than the box (got ${gaussInSpan.size} vs $taps)",
        )
        val gaussTailRatio = gauss.minOf { if (it > 1e-12) it else 1.0 } / gaussPeak
        assertTrue(
            gaussTailRatio < 0.02,
            "a Gaussian's outermost sample must be a small fraction of its peak (got $gaussTailRatio)",
        )

        // The two profiles are normalised to the same total mass, so this is a pure shape claim.
        // Tolerance is 1e-6, not 1e-9: the weights are `Float` (`1f/13f` does not sum to exactly
        // 1.0 once widened to `Double`), which is also why the actual passes `gain = 1f`.
        assertEquals(1.0, box.sum(), 1e-6, "box mass")
        assertEquals(1.0, gauss.sum(), 1e-6, "gaussian mass")
    }

    @Test
    fun boxIsFlatterThanGaussianAcrossItsWholeSpan() {
        // Quantitative shape statistic: coefficient of variation (stdev / mean) of the profile
        // samples that carry the effect. A plateau is exactly 0; a bell is not. This is the number
        // behind "平台形 vs 钟形" in RESULT.md §三.
        val taps = 13
        val box = boxProfile(taps).filter { it > 1e-12 }
        val gauss = gaussianProfile(3.25).filter { it > 1e-12 }

        fun coefficientOfVariation(values: List<Double>): Double {
            val mean = values.average()
            val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
            return sqrt(variance) / mean
        }

        val boxCv = coefficientOfVariation(box)
        val gaussCv = coefficientOfVariation(gauss)
        assertEquals(0.0, boxCv, 1e-9, "a box profile is perfectly flat (CV must be 0)")
        assertTrue(
            gaussCv > 0.3,
            "a Gaussian profile must be clearly non-flat (got CV=$gaussCv, expected > 0.3)",
        )
    }

    @Test
    fun boxAndGaussianDifferByMoreThanAScaleFactor() {
        // The A/B is only meaningful if the two kernels are different *shapes*, not the same shape
        // at a different strength. Pearson correlation over the two profiles must stay well below
        // 1 — a box and a bell are not proportional.
        val taps = 13
        val box = boxProfile(taps)
        val gauss = gaussianProfile(3.25)

        val boxMean = box.average()
        val gaussMean = gauss.average()
        var cov = 0.0
        var boxVar = 0.0
        var gaussVar = 0.0
        for (i in box.indices) {
            cov += (box[i] - boxMean) * (gauss[i] - gaussMean)
            boxVar += (box[i] - boxMean) * (box[i] - boxMean)
            gaussVar += (gauss[i] - gaussMean) * (gauss[i] - gaussMean)
        }
        val corr = cov / (sqrt(boxVar) * sqrt(gaussVar))

        assertTrue(
            abs(corr) < 0.9,
            "box and Gaussian profiles must be distinguishable (correlation=$corr, expected < 0.9)",
        )
    }

    // --- default ------------------------------------------------------------------------

    @Test
    fun shippedDefaultIsTheBoxKernel() {
        // The decision recorded in RESULT.md §四: box wins on profile *and* frame time, so it is
        // the default and the Gaussian is the retained fallback.
        assertEquals(MotionBlurKernel.Box, DEFAULT_MOTION_BLUR_KERNEL)
    }

    @Test
    fun gaussianFallbackConstantsAreStillTheDirectionalPair() {
        // The fallback must not silently become isotropic while nobody is looking: it is what a
        // platform without a convolution filter degrades to.
        assertTrue(
            MOTION_BLUR_SIGMA_Y_PER_RADIUS > MOTION_BLUR_SIGMA_X_PER_RADIUS * 4f,
            "the Gaussian fallback must stay directional (Y=$MOTION_BLUR_SIGMA_Y_PER_RADIUS, " +
                "X=$MOTION_BLUR_SIGMA_X_PER_RADIUS)",
        )
    }
}
