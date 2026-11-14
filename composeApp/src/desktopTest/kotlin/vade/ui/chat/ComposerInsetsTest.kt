// g-inset2 · composer safe-area guard (P1-1) + content-side padding pins (P1-3).
//
// The chat composer (InputBar capsule) now rides on
// `16.dp + composerNavClearance()` — the nav-bar remainder the IME does not
// cover, `max(0 dp, navInset − ime)`. Everything below is testable on desktop
// JVM: the pure clearance/padding math, the union-not-sum invariant, and the
// real-window desktop actuals (0 dp). The Android non-zero values are NOT
// testable on JVM — the device screenshots under `shots/g-inset2/` are the
// evidence, same split as `InsetsGuardTest` documents for the shell bars.

package vade.ui.chat

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.awaitApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import vade.ui.component.insets.composerNavClearance
import vade.ui.shell.FloatingBarPillHeight
import vade.ui.shell.floatingBarBottomMargin

class ComposerInsetsTest {

    // --- the pure clearance the composer's 16 dp base rides on -------------------------

    /** ① + ④ (three-button): a 48 dp nav inset lifts the capsule by exactly that inset. */
    @Test
    fun threeButtonNavInsetLiftsTheComposerCapsuleByTheInset() {
        assertEquals(48.dp, composerNavClearance(0.dp, 48.dp))
        assertEquals(64.dp, 16.dp + composerNavClearance(0.dp, 48.dp))
    }

    /** ④ (gesture): a 24 dp gesture-pill inset lifts the capsule by exactly 24 dp. */
    @Test
    fun gesturePillInsetLiftsTheComposerCapsuleByTheInset() {
        assertEquals(24.dp, composerNavClearance(0.dp, 24.dp))
        assertEquals(40.dp, 16.dp + composerNavClearance(0.dp, 24.dp))
    }

    /** ③ (no double count, shown keyboard): the IME covers the nav bar → clearance is 0. */
    @Test
    fun keyboardOpenAddsNoClearanceAboveTheKeyboard() {
        assertEquals(0.dp, composerNavClearance(800.dp, 48.dp))
        // The composer's bottom padding stays at today's 16 dp base — nothing stacks on the IME.
        assertEquals(16.dp, 16.dp + composerNavClearance(800.dp, 48.dp))
    }

    /**
     * ③ + ⑥ (the union invariant): `ime + clearance == max(ime, nav)` for every
     * state — the composer only ever adds the nav remainder the IME does not
     * cover, so the insets can never sum. This is what keeps the capsule glued
     * to the keyboard top (MainActivity's root `imePadding()` lifts the app)
     * while still clearing the nav bar when the keyboard is closed.
     */
    @Test
    fun imePlusClearanceIsAlwaysTheUnionNeverTheSum() {
        val cases = listOf(
            0.dp to 48.dp, // keyboard closed, 3-button nav
            0.dp to 24.dp, // keyboard closed, gesture pill
            24.dp to 48.dp, // mid gesture animation
            30.dp to 48.dp, // keyboard just appearing
            800.dp to 48.dp, // keyboard fully open over 3-button nav
            800.dp to 24.dp, // keyboard fully open over gesture pill
            600.dp to 600.dp, // pathological equality
            0.dp to 0.dp, // desktop / no-inset host
        )
        cases.forEach { (ime, nav) ->
            val union = if (ime > nav) ime else nav
            assertEquals(union, ime + composerNavClearance(ime, nav), "ime=$ime nav=$nav")
        }
    }

    /** ⑥ (no phantom insets): zero insets in, zero clearance out — bottom-only, nothing else. */
    @Test
    fun zeroInsetsMeansZeroClearanceBottomOnly() {
        assertEquals(0.dp, composerNavClearance(0.dp, 0.dp))
    }

    // --- desktop actuals, through a real (hidden) window host --------------------------
    // Same harness as InsetsGuardTest: the insets CompositionLocal is internal and
    // only a genuine window host can supply it.

    /**
     * ② (desktop pixel parity): in a real ComposeWindow every inset the composer
     * reads resolves to 0 dp, so its bottom padding stays exactly today's 16 dp —
     * the desktop/wasm layout is unchanged pixel for pixel.
     */
    @Test
    fun desktopWindowActualsReadZeroAndComposerPaddingStays16dp(): Unit = runBlocking {
        val navBottom = CompletableDeferred<androidx.compose.ui.unit.Dp>()
        val imeBottom = CompletableDeferred<androidx.compose.ui.unit.Dp>()
        val clearance = CompletableDeferred<androidx.compose.ui.unit.Dp>()
        val app = launch {
            awaitApplication {
                Window(
                    onCloseRequest = { exitApplication() },
                    // Hidden: the assertion is about inset VALUES, not pixels.
                    visible = false,
                ) {
                    navBottom.complete(
                        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                    )
                    imeBottom.complete(
                        WindowInsets.ime.asPaddingValues().calculateBottomPadding(),
                    )
                    clearance.complete(composerNavClearance())
                }
            }
        }
        try {
            assertEquals(0.dp, withTimeout(30_000) { navBottom.await() })
            assertEquals(0.dp, withTimeout(30_000) { imeBottom.await() })
            assertEquals(0.dp, withTimeout(30_000) { clearance.await() })
            assertEquals(16.dp, 16.dp + withTimeout(30_000) { clearance.await() })
        } finally {
            app.cancel()
        }
    }

    // --- ⑤ content side: the §1 list spacer must track the composer height -------------

    /**
     * ⑤ (content not permanently covered): the §1 spacer pads the list by the
     * composer's MEASURED height (its padding included) + 24 dp. With g-inset2
     * the measured height includes the nav clearance, so the newest message
     * rises 1:1 with the inset. Even a minimal 64 dp capsule must scroll clear
     * of a bottom overlay as tall as the floating pill on 3-button nav.
     */
    @Test
    fun listSpacerTracksComposerHeightSoTheNewestMessageScrollsClear() {
        assertEquals(88.dp, composerListSpacer(64.dp)) // 64 dp height + 24 dp gap
        val minimalComposerHeight = 64.dp + 16.dp + composerNavClearance(0.dp, 48.dp)
        val spacer = composerListSpacer(minimalComposerHeight)
        val pillStack = FloatingBarPillHeight + floatingBarBottomMargin(48.dp)
        assertTrue(spacer > pillStack, "spacer=$spacer must exceed pill stack=$pillStack")
    }

    /**
     * ⑤ (P1-3 shell-side target, pinned arithmetic only): `AppShell.kt:108
     * BOTTOM_BAR_ZONE = 88.dp` belongs to another batch (g-glass) and is out of
     * this batch's writable scope — this test pins the numbers the fix must
     * reach: the floating pill stack is 64 dp + (8 dp + inset), i.e. 128 dp on
     * 3-button nav and 96 dp on the gesture pill, both over the current 88 dp.
     */
    @Test
    fun floatingPillStackExceedsTheShell88dpZoneOnBothNavStyles() {
        assertEquals(120.dp, FloatingBarPillHeight + floatingBarBottomMargin(48.dp))
        assertEquals(96.dp, FloatingBarPillHeight + floatingBarBottomMargin(24.dp))
        assertTrue(
            FloatingBarPillHeight + floatingBarBottomMargin(48.dp) > 88.dp,
            "88 dp zone hides the last row behind the pill on 3-button nav",
        )
    }
}
