package vade.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.awaitApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import vade.ui.shell.floatingBarBottomMargin

/**
 * c-inset anti-regression guard.
 *
 * The Android safe-area fix (enableEdgeToEdge + statusBars/ime padding in MainActivity,
 * navigationBars padding on the bottom bars) must never leak into the desktop layout:
 * every inset the shell reads comes from CMP's multiplatform `WindowInsets`, whose
 * desktop actual reads the window host's `PlatformWindowInsets`. Desktop has no system
 * bars, so the reads must resolve to 0 dp — asserted here through a **real ComposeWindow**.
 * (The insets CompositionLocal is internal API, so it can only be provided by a genuine
 * window host; a hand-rolled bare composition cannot supply it and throws.)
 *
 * What is testable here (desktop JVM):
 *  1. The pure margin branch the insets feed into (`8 dp + inset` vs KernelSU's 28 dp).
 *  2. The desktop `WindowInsets.statusBars` / `WindowInsets.navigationBars` actuals resolve
 *     to 0 dp inside a real (hidden) ComposeWindow.
 *
 * NOT testable here, stated honestly:
 *  - the Android non-zero values need a real Android window (device screenshots are the
 *    evidence, see shots/c-inset/RESULT.md);
 *  - the wasm actual belongs to the same expect/actual set but is not exercised by a JVM
 *    test run.
 */
class InsetsGuardTest {

    // --- the pure branch the real insets feed -----------------------------------------

    @Test
    fun noNavInsetFallsBackToKsuMargin() {
        assertEquals(28.dp, floatingBarBottomMargin(0.dp))
    }

    @Test
    fun navInsetRidesOnTheEightDpMargin() {
        assertEquals(32.dp, floatingBarBottomMargin(24.dp)) // gesture pill ≈ 24 dp
        assertEquals(56.dp, floatingBarBottomMargin(48.dp)) // 3-button bar ≈ 48 dp
    }

    // --- desktop actuals of the insets the shell reads, in a real window ---------------

    @Test
    fun desktopSystemBarInsetsReadZeroInARealWindow(): Unit = runBlocking {
        val navBottom = CompletableDeferred<Dp>()
        val statusTop = CompletableDeferred<Dp>()
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
                    statusTop.complete(
                        WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                    )
                }
            }
        }
        try {
            assertEquals(0.dp, withTimeout(30_000) { statusTop.await() })
            assertEquals(0.dp, withTimeout(30_000) { navBottom.await() })
        } finally {
            app.cancel()
        }
    }
}
