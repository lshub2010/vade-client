package vade.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import vade.ui.motion.MotionBlurKernel
import vade.ui.motion.DEFAULT_MOTION_BLUR_KERNEL
import vade.ui.motion.boxKernelTaps
import vade.ui.motion.boxKernelWeights
import vade.ui.motion.triangleKernelWeights

/**
 * The triangle (Bartlett) kernel, and the beat-frequency argument it exists for.
 *
 * The parent probe found that on the pathological fixture (8× identical `MOTION BLUR`, 34 sp
 * monospace) the box kernel renders every letter as 2–3 evenly spaced horizontal echo bands.
 * That is the rectangle's frequency response at work: a 13-tap box has |sinc| sidelobes only
 * −13 dB down, so periodic glyph detail at a 4–7 px pitch comes back through the first
 * sidelobe. The triangle is box ⊛ box, so its response is the box response *squared* — this
 * file pins that identity and its consequence (less ripple on a periodic fixture) numerically,
 * so the RESULT.md §三.6 claim is reproducible without a screenshot.
 *
 * All frequency-domain maths here is a direct O(N²) DTFT sum — deliberately library-free, the
 * same way `MotionBlurBoxKernelTest` hand-rolls its profiles.
 */
class MotionBlurTriangleKernelTest {

    // --- weights -------------------------------------------------------------------------

    @Test
    fun weightsSumToOneSoBrightnessIsPreserved() {
        // gain=1 at the call site; a kernel that summed to anything else would darken or blow
        // out the layer. Float arithmetic needs 1e-6, same as the box test.
        for (taps in listOf(1, 3, 7, 13, 25, 31)) {
            val weights = triangleKernelWeights(taps)
            assertEquals(taps, weights.size)
            assertEquals(1f, weights.sum(), 1e-6f, "weights must sum to 1 for $taps taps")
        }
    }

    @Test
    fun weightsAreSymmetricWithThePeakAtTheCentre() {
        // A motion-blur streak must not shift content: asymmetry would move it by (asymmetry) px.
        for (taps in listOf(3, 7, 13, 25)) {
            val weights = triangleKernelWeights(taps)
            for (i in weights.indices) {
                assertEquals(weights[weights.size - 1 - i], weights[i], 1e-9f, "symmetry at $taps/$i")
            }
            val centre = taps / 2
            assertEquals(weights.max(), weights[centre], 1e-9f, "centre tap is the peak")
        }
    }

    @Test
    fun weightsTaperToNearZeroNotToHalfThePeak() {
        // The whole design choice: the *tapered* Bartlett (ends → the minimum weight) and not the
        // literal `N − |i − c|` triangle truncated at its 54%-of-peak ends. Truncated ends would
        // still be a hard corner and only −18.9 dB; the taper is what squares the box response
        // (−25.3 dB). end/centre = 1/(centre+1) ≈ 0.143 for 13 taps vs 0.538 for the truncated
        // form — the assert pins the tapered one.
        for (taps in listOf(7, 13, 25)) {
            val weights = triangleKernelWeights(taps)
            val centre = taps / 2
            val ratio = weights.first() / weights[centre]
            val expected = 1f / (centre + 1)
            assertEquals(expected, ratio, 1e-6f, "end/centre must be 1/(centre+1) at $taps taps")
            assertTrue(ratio < 0.5f, "tapered ends ($ratio) must be well below the truncated 0.538")
        }
    }

    @Test
    fun weightsRampLinearlyNotUniform() {
        // Guard against a copy-paste regression to the box: the triangle's defining property is
        // the *linear* taper — every step off-centre changes the weight by exactly one unit
        // (1/sum), which is what makes it the discrete box ⊛ box.
        val weights = triangleKernelWeights(13)
        val centre = 6
        val unit = weights[0]
        for (i in 1 until weights.size) {
            assertEquals(unit, abs(weights[i] - weights[i - 1]), 1e-7f, "constant ramp step at $i")
        }
        assertTrue(weights[centre] > weights[0] * 3f, "centre must be clearly peaked")
    }

    // --- the box ⊛ box identity ----------------------------------------------------------

    /** Discrete convolution of two equal-length arrays. */
    private fun convolve(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(a.size + b.size - 1)
        for (i in a.indices) for (j in b.indices) out[i + j] += a[i] * b[j]
        return out
    }

    @Test
    fun triangleIsBoxConvolvedWithBoxOnTheSameGrid() {
        // For odd N = 2h+1 the Bartlett window equals box(h+1) ⊛ box(h+1). The probe always
        // builds both kernels on the same `boxKernelTaps` grid, so this must hold at the exact
        // tap counts the radius curve produces (6dp@1x → 13, @2x → 25).
        for (taps in listOf(5, 7, 13, 25, 31)) {
            val halfBox = taps / 2 + 1
            val selfConv = convolve(boxKernelWeights(halfBox), boxKernelWeights(halfBox))
            assertEquals(taps, selfConv.size, "box(h+1)⊛box(h+1) must be N long")
            val triangle = triangleKernelWeights(taps)
            for (i in triangle.indices) {
                assertEquals(selfConv[i], triangle[i], 1e-6f, "box⊛box identity at $taps/$i")
            }
        }
    }

    @Test
    fun triangleSharesTheBoxTapCount() {
        // Same N for the same radius — only the weights differ, so the smear span (and the
        // frame-time cost) is identical between the two kernels.
        for (radius in listOf(1f, 3f, 6f)) {
            for (density in listOf(1f, 2f)) {
                val taps = boxKernelTaps(radius, density)
                assertEquals(taps, triangleKernelWeights(taps).size)
            }
        }
    }

    @Test
    fun degenerateTapCountsThrowLikeTheBoxKernel() {
        assertFailsWith<IllegalArgumentException> { triangleKernelWeights(0) }
        assertFailsWith<IllegalArgumentException> { triangleKernelWeights(-3) }
    }

    // --- frequency response: the beat argument -------------------------------------------

    /** |DTFT| of a normalised kernel at frequency `f` (cycles/sample), direct sum. */
    private fun magnitudeResponse(kernel: FloatArray, f: Double): Double {
        var re = 0.0
        var im = 0.0
        for (n in kernel.indices) {
            val angle = 2.0 * PI * f * n
            re += kernel[n] * kotlin.math.cos(angle)
            im -= kernel[n] * sin(angle)
        }
        return kotlin.math.sqrt(re * re + im * im)
    }

    @Test
    fun triangleResponseIsTheBoxResponseSquared() {
        // |box ⊛ box| = |box|² pointwise. The N-tap Bartlett is the (N+1)/2-tap box convolved
        // with itself (the test above), so its response is *that* box's response squared — not
        // the N-tap box's. Below the main lobe both boxes are ≈ 1, which is why RESULT.md can
        // say "the box response squared" operationally; here it is said exactly.
        val halfBox = boxKernelWeights(7)
        val triangle = triangleKernelWeights(13)
        var f = 0.005
        while (f < 0.5) {
            val boxGain = magnitudeResponse(halfBox, f)
            val triangleGain = magnitudeResponse(triangle, f)
            assertEquals(boxGain * boxGain, triangleGain, 2e-3, "|triangle| = |box7|² at f=$f")
            f += 0.005
        }
    }

    @Test
    fun triangleSuppressesTheGlyphBeatPitchesMoreThanTheBox() {
        // The observed beat: glyph cross-bars at a 4–7 px pitch hit the 13-tap kernel with
        // f = 1/4 … 1/7 cycles/px. The triangle's gain must be strictly lower at every one of
        // those pitches — and by roughly the squared ratio, not a rounding error.
        val box = boxKernelWeights(13)
        val triangle = triangleKernelWeights(13)
        for (period in listOf(4.0, 5.0, 6.0, 7.0)) {
            val f = 1.0 / period
            val boxGain = magnitudeResponse(box, f)
            val triangleGain = magnitudeResponse(triangle, f)
            assertTrue(
                triangleGain < boxGain * 0.75,
                "triangle must clearly cut the ${period.toInt()}px pitch " +
                    "(got ${"%.3f".format(triangleGain)} vs box ${"%.3f".format(boxGain)})",
            )
        }
    }

    @Test
    fun triangleSidelobeFloorIsWellBelowTheBoxOne() {
        // The headline number in RESULT.md §三.6: the 13-tap box's worst sidelobe is ≈ −13 dB;
        // the triangle's ≈ −25 dB. Measured over the sidelobe band (excluding the DC main lobe,
        // f ≤ 1/13), in dB.
        val box = boxKernelWeights(13)
        val triangle = triangleKernelWeights(13)
        fun worstSidelobe(kernel: FloatArray): Double {
            var worst = 0.0
            var f = 1.5 / kernel.size
            while (f < 0.5) {
                worst = maxOf(worst, magnitudeResponse(kernel, f))
                f += 0.001
            }
            return 20.0 * kotlin.math.log10(worst / kernel.sum())
        }
        val boxDb = worstSidelobe(box)
        val triangleDb = worstSidelobe(triangle)
        assertTrue(boxDb > -16.0, "box worst sidelobe should be ≈ −13 dB (got $boxDb dB)")
        assertTrue(triangleDb < boxDb - 8.0, "triangle must suppress the sidelobe floor by >8 dB " +
            "(box $boxDb dB vs triangle $triangleDb dB)")
    }

    // --- image-space echo depth (the visible symptom) ------------------------------------

    /**
     * Ripple depth (max−min, normalised) of a periodic cross-bar pattern after convolution.
     * `period` is in px, duty 50%. Interior window excludes the conv edges. This is the
     * image-space form of the beat: the echo bands the parent saw are exactly this ripple.
     */
    private fun echoDepth(kernel: FloatArray, period: Int): Double {
        val n = 256
        val signal = DoubleArray(n) { if (it % period < period / 2) 1.0 else 0.0 }
        val half = kernel.size / 2
        val out = DoubleArray(n)
        for (y in 0 until n - 2 * half) {
            var acc = 0.0
            for (k in kernel.indices) acc += kernel[k] * signal[y + k]
            out[y] = acc
        }
        val from = 64
        val to = n - 2 * half - 64
        val interior = out.copyOfRange(from, to)
        return interior.max() - interior.min()
    }

    @Test
    fun triangleRippleOnAPeriodicFixtureIsMeasurablySmallerThanTheBox() {
        // On the 4px-pitch fixture the box's 13-tap window holds 3.25 periods, so its output
        // oscillates as the window phase slips; the tapered window averages the same content
        // with softer ends and must ripple less. Print both numbers — they belong in RESULT.md.
        for (period in listOf(4, 5, 7)) {
            val boxRipple = echoDepth(boxKernelWeights(13), period)
            val triangleRipple = echoDepth(triangleKernelWeights(13), period)
            println(
                "echo period=${period}px: box p2p=${"%.4f".format(boxRipple)} " +
                    "triangle p2p=${"%.4f".format(triangleRipple)}",
            )
            assertTrue(
                triangleRipple < boxRipple * 0.75,
                "triangle ripple must be clearly smaller at ${period}px pitch " +
                    "(got $triangleRipple vs box $boxRipple)",
            )
        }
    }

    // --- default -------------------------------------------------------------------------

    @Test
    fun shippedDefaultIsTheBoxKernel() {
        // The measured decision (RESULT.md §三.6): on real mixed content every kernel's echo
        // seam is ≈ 0 (box −0.009, triangle −0.000, gaussian 0.005 — the stacked-band echo only
        // exists on the pathological 34sp fixture), so the physical-integral box stays the
        // default; the triangle stays selectable for large/periodic content where it halves the
        // seam (0.027 → 0.010) at identical cost (99.0% of off, the fastest of the three).
        assertEquals(MotionBlurKernel.Box, DEFAULT_MOTION_BLUR_KERNEL)
    }

    @Test
    fun triangleWeightsDifferFromBoxAtTheSameTaps() {
        // The two paths share makeMatrixConvolution and the tap grid; if the weights ever came
        // out identical the A/B would be measuring nothing.
        val box = boxKernelWeights(13)
        val triangle = triangleKernelWeights(13)
        var differences = 0
        for (i in box.indices) if (abs(box[i] - triangle[i]) > 1e-6f) differences++
        assertTrue(differences >= 11, "13 taps must differ in at least 11 positions (got $differences)")
    }
}
