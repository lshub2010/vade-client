package vade.ui

import androidx.compose.ui.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import vade.ui.skin.glass.MOTION_BLUR_FULL_VELOCITY_DP_PER_SEC
import vade.ui.skin.glass.MOTION_BLUR_MAX_RADIUS_DP
import vade.ui.skin.glass.MOTION_BLUR_SIGMA_X_PER_RADIUS
import vade.ui.skin.glass.MOTION_BLUR_SIGMA_Y_PER_RADIUS
import vade.ui.skin.glass.MOTION_BLUR_START_VELOCITY_DP_PER_SEC
import vade.ui.skin.glass.blurForVelocity
import vade.ui.skin.glass.motionBlur
import vade.ui.skin.glass.velocityToBlurRadiusDp

/**
 * The parts of §11 that are checkable without pixels:
 *
 *  - the velocity → radius curve (threshold, cap, continuity, monotonicity, sign, NaN);
 *  - the directional ratio between the two sigmas (Y must dominate X by a wide margin, or the
 *    effect degrades back into isotropic defocus);
 *  - `radiusDp <= 0` returning the *same* modifier instance — that is how "静止时严格为 0，禁止
 *    残留" and "无开销" are enforced structurally rather than by hoping a layer is cheap.
 *
 * `blurForVelocity` is `expect`/`actual`, so this runs the *desktop* actual — which is the point:
 * if a platform ever stops delegating to the shared curve, this test fails on that target.
 */
class MotionBlurMathTest {

    // --- curve -------------------------------------------------------------------------

    @Test
    fun radiusIsZeroAtAndBelowTheTriggerVelocity() {
        assertEquals(0f, blurForVelocity(0f), "v=0")
        assertEquals(0f, blurForVelocity(1199f), "v=1199")
        assertEquals(0f, blurForVelocity(MOTION_BLUR_START_VELOCITY_DP_PER_SEC), "v=1200, the threshold itself")
    }

    @Test
    fun radiusIsContinuousAndLinearAboveTheTrigger() {
        // The whole point of the rewrite: no jump to max at 1200. Halfway between trigger and
        // full speed must be exactly half the cap.
        assertEquals(1.5f, blurForVelocity(2400f), 1e-4f, "v=2400 is a quarter of the range → 1.5dp")
        assertEquals(3f, blurForVelocity(3600f), 1e-4f, "v=3600 is the midpoint → 3dp")
        assertEquals(4.5f, blurForVelocity(4800f), 1e-4f, "v=4800 is three quarters → 4.5dp")
    }

    @Test
    fun radiusNeverExceedsTheDocumentedCap() {
        // 6dp cap: past `full` velocity the curve clamps, it does not keep growing.
        assertEquals(MOTION_BLUR_MAX_RADIUS_DP, blurForVelocity(6000f), "v=6000, the full speed")
        assertEquals(MOTION_BLUR_MAX_RADIUS_DP, blurForVelocity(50_000f), "v=50000")
        assertEquals(MOTION_BLUR_MAX_RADIUS_DP, blurForVelocity(99_999f), "v=99999")
        assertEquals(MOTION_BLUR_MAX_RADIUS_DP, blurForVelocity(Float.MAX_VALUE), "v=Float.MAX")
    }

    @Test
    fun radiusGrowsMonotonicallyWithSpeed() {
        var previous = -1f
        var velocity = 0f
        while (velocity <= 8000f) {
            val radius = blurForVelocity(velocity)
            assertTrue(radius >= previous, "radius must not shrink as speed rises (v=$velocity)")
            assertTrue(radius in 0f..MOTION_BLUR_MAX_RADIUS_DP, "radius out of range at v=$velocity: $radius")
            previous = radius
            velocity += 50f
        }
        assertTrue(previous > 0f, "the sweep must reach a non-zero radius")
    }

    @Test
    fun negativeVelocityIsTreatedAsSpeed() {
        // Up-scroll and down-scroll smear the same amount; a negative speed must not yield a
        // negative radius (Skia rejects a negative sigma).
        assertEquals(blurForVelocity(3000f), blurForVelocity(-3000f), "sign must not matter")
        assertEquals(0f, blurForVelocity(-500f), "a slow negative speed is still 0")
    }

    @Test
    fun nonFiniteInputIsSafe() {
        // A first frame has no previous sample; a NaN sigma blanks the Skia layer.
        assertEquals(0f, blurForVelocity(Float.NaN))
        assertEquals(0f, blurForVelocity(Float.POSITIVE_INFINITY))
        assertEquals(0f, blurForVelocity(Float.NEGATIVE_INFINITY))
    }

    // --- directionality ----------------------------------------------------------------

    @Test
    fun blurIsDirectionalWithYAsTheDominantAxis() {
        // §11: "只做垂直方向为主的方向性模糊". If these ever converge the effect is isotropic
        // defocus again — the exact defect this pair of constants exists to prevent.
        assertTrue(
            MOTION_BLUR_SIGMA_Y_PER_RADIUS > MOTION_BLUR_SIGMA_X_PER_RADIUS * 4f,
            "Y sigma must dominate X by more than 4× (got Y=$MOTION_BLUR_SIGMA_Y_PER_RADIUS, " +
                "X=$MOTION_BLUR_SIGMA_X_PER_RADIUS)",
        )
        assertTrue(MOTION_BLUR_SIGMA_X_PER_RADIUS > 0f, "a 0 sigmaX leaves a hard layer-edge seam")
        assertTrue(MOTION_BLUR_SIGMA_Y_PER_RADIUS <= 1f, "sigmaY must stay a blur, not a smear-to-nothing")
    }

    // --- modifier contract -------------------------------------------------------------

    @Test
    fun zeroRadiusIsFreeAndReturnsTheSameModifier() {
        val base: Modifier = Modifier
        assertTrue(base.motionBlur(0f) === base, "0dp must return the receiver, not a new layer")
        assertTrue(base.motionBlur(-4f) === base, "negative radii are treated as off")
        assertTrue(base.motionBlur(6f) !== base, "a positive radius must actually wrap the modifier")
    }

    @Test
    fun sharedCurveIsTheSameFunctionTheActualUses() {
        // Guards the "one formula in commonMain" rule: the actual must be a pure delegation.
        val samples = listOf(-5000f, 0f, 1300f, 2400f, 3600f, 4800f, 6000f, 9000f)
        for (v in samples) {
            assertEquals(velocityToBlurRadiusDp(v), blurForVelocity(v), "actual diverged from the shared curve at v=$v")
        }
    }

    @Test
    fun documentedEndpointsMatchTheConstants() {
        // Keeps the KDoc formula and the constants in step: 1200 → 0, 6000 → 6.
        assertEquals(0f, velocityToBlurRadiusDp(MOTION_BLUR_START_VELOCITY_DP_PER_SEC))
        assertEquals(MOTION_BLUR_MAX_RADIUS_DP, velocityToBlurRadiusDp(MOTION_BLUR_FULL_VELOCITY_DP_PER_SEC))
    }
}
