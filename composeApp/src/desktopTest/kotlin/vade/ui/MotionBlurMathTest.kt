package vade.ui

import androidx.compose.ui.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import vade.ui.motion.MOTION_BLUR_MAX_RADIUS_DP
import vade.ui.motion.MOTION_BLUR_START_VELOCITY_DP_PER_SEC
import vade.ui.motion.blurForVelocity
import vade.ui.motion.motionBlur
import vade.ui.motion.velocityToBlurRadiusDp

/**
 * The parts of §11 that are checkable without pixels:
 *
 *  - the velocity → radius curve (threshold, cap, monotonicity, sign, NaN);
 *  - `radiusDp <= 0` returning the *same* modifier instance — that is how "静止时严格为 0，禁止
 *    残留" and "无开销" are enforced structurally rather than by hoping a layer is cheap.
 *
 * `blurForVelocity` is `expect`/`actual`, so this runs the *desktop* actual — which is the point:
 * if a platform ever stops delegating to the shared curve, this test fails on that target.
 */
class MotionBlurMathTest {

    @Test
    fun radiusIsZeroAtAndBelowTheTriggerVelocity() {
        assertEquals(0f, blurForVelocity(0f))
        assertEquals(0f, blurForVelocity(1199.9f))
        assertEquals(0f, blurForVelocity(MOTION_BLUR_START_VELOCITY_DP_PER_SEC))
    }

    @Test
    fun radiusNeverExceedsTheDocumentedCap() {
        // §11 caps the scroll case at 10dp. Anything above the "full" speed must clamp, not grow.
        assertEquals(MOTION_BLUR_MAX_RADIUS_DP, blurForVelocity(4800f))
        assertEquals(MOTION_BLUR_MAX_RADIUS_DP, blurForVelocity(50_000f))
        assertEquals(MOTION_BLUR_MAX_RADIUS_DP, blurForVelocity(Float.MAX_VALUE))
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
    fun directionDoesNotMatterAndNonFiniteInputIsSafe() {
        // Up-scroll and down-scroll smear the same amount; a first frame with no previous
        // sample must not produce NaN (a NaN sigma blanks the Skia layer).
        assertEquals(blurForVelocity(3000f), blurForVelocity(-3000f))
        assertEquals(0f, blurForVelocity(Float.NaN))
        assertEquals(0f, blurForVelocity(Float.POSITIVE_INFINITY))
        assertEquals(0f, blurForVelocity(Float.NEGATIVE_INFINITY))
    }

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
        for (v in listOf(-5000f, 0f, 1300f, 2400f, 4800f, 9000f)) {
            assertEquals(velocityToBlurRadiusDp(v), blurForVelocity(v), "actual diverged from the shared curve at v=$v")
        }
    }
}
