// g-inset2 · composer safe-area: the chat InputBar capsule must clear the system
// navigation bar. P1-1 (real-device API 30, three-button nav): the capsule's
// bottom rounded corners and hit area fell inside the nav-bar zone — ◁ ○ □ drew
// straight on top of the capsule (w-verify `08`/`09`, zoom `ev-detail-navbar-over-capsule`).
//
// The read copies `BottomBar.kt`'s cross-platform pattern (c-inset): CMP's
// multiplatform `WindowInsets.navigationBars` — non-zero on Android under
// edge-to-edge, 0 on desktop and wasm (the shell's `InsetsGuardTest` pins the
// desktop actuals) — so desktop/wasm composer geometry is unchanged. It is
// duplicated here instead of imported because `navigationBarBottomInset()` is
// internal to the `shell` package and `shell/**` is out of this batch's
// writable scope; if the two ever diverge, unify behind one helper.
//
// Keyboard interplay (no double count): MainActivity's ROOT `imePadding()` already
// lifts the whole app above the IME, and a shown IME covers the nav bar. The
// composer therefore adds only the nav-bar remainder the IME does not cover —
// `max(0 dp, navInset − ime)` — so `ime + clearance` is always exactly
// `max(ime, nav)`, never the sum. Test ③ of `ComposerInsetsTest` pins that invariant.

package vade.ui.component.insets

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Pure decision the composable below feeds: extra bottom clearance the composer
 * adds under its fixed 16 dp base. [imeBottom] is the raw IME bottom inset
 * (0 dp when the keyboard is closed), [navInset] the navigation-bar bottom
 * inset. Both resolve to 0 dp on desktop and wasm, so the result is
 * `(0 − 0).coerceAtLeast(0)` = 0 dp there and today's geometry is untouched.
 */
fun composerNavClearance(imeBottom: Dp, navInset: Dp): Dp =
    (navInset - imeBottom).coerceAtLeast(0.dp)

/**
 * The composer's live nav-bar clearance: [composerNavClearance] fed by CMP's
 * multiplatform `WindowInsets` reads. The reads are the RAW platform values —
 * the inset consumption MainActivity's root padding performs affects padding
 * modifiers only, never `asPaddingValues()`, so this stays deterministic even
 * though an ancestor already applied `imePadding`.
 */
@Composable
fun composerNavClearance(): Dp {
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return composerNavClearance(imeBottom, navInset)
}
