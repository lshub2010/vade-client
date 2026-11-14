// g-float regression tests: the floating pill's on-screen footprint vs the shell's
// bottom-bar zone.
//
// The pill's stack from the window bottom is  `barInsets(12dp) + margin + 64dp`:
//  - margin = `floatingBarBottomMargin()` = 8dp + nav inset (or 28dp with none);
//  - the pill itself is `FloatingBarPillHeight` = 64dp.
// On the API 30 device (3-button GSI, edge-to-edge) this measured 188px ≈ 75dp of
// margin stack + 64dp of pill — while the shell's `BOTTOM_BAR_ZONE` reserves 88dp of
// content padding, so the last row of every screen can scroll to a spot that is
// still behind the pill (the user-visible 「胶囊压掉上方最后一行文字」).
//
// AppShell.kt is owned by another batch, so the zone number itself cannot be
// asserted here; what CAN be pinned is the footprint formula and its inputs — if
// anyone moves the pill height, the margin branches, or the 8dp+inset rule, these
// tests go red and the zone owner must re-derive the target
// (12dp + floatingBarBottomMargin() + FloatingBarPillHeight).

package vade.ui.component.liquid

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import vade.ui.shell.FloatingBarPillHeight
import vade.ui.shell.floatingBarBottomMargin

class FloatingBarFootprintTest {

    private fun floatingFootprint(navInset: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
        12.dp + floatingBarBottomMargin(navInset) + FloatingBarPillHeight

    // --- margin branches (⑤ the two bar forms derive from disjoint, pinned inputs) ----

    @Test
    fun floatingMarginIsInsetPlusEightWhenTheDeviceHasANavBar() {
        assertEquals(32.dp, floatingBarBottomMargin(24.dp))
        assertEquals(56.dp, floatingBarBottomMargin(48.dp))
    }

    @Test
    fun floatingMarginFallsBackToKernelSusTwentyEightWithoutAnInset() {
        assertEquals(28.dp, floatingBarBottomMargin(0.dp))
    }

    // --- the pill geometry is pinned (④ pill height + bottom margin) -------------------

    @Test
    fun pillHeightStaysAtTheKernelSu64dpGeometry() {
        // 56dp content + 2×4dp padding — the c-inset batch measured this on-device.
        assertEquals(64.dp, FloatingBarPillHeight)
    }

    @Test
    fun floatingFootprintWithoutNavInsetIs104dp() {
        // 12 (AppShell barInsets) + 28 (no-inset margin) + 64 (pill) — the value
        // `BOTTOM_BAR_ZONE` must meet or exceed; today's 88dp shortfall is the
        // reported overlap (see shots/g-float/RESULT.md).
        assertEquals(104.dp, floatingFootprint(0.dp))
    }

    @Test
    fun floatingFootprintWithA48dpInsetIs132dp() {
        // Edge-to-edge 3-button device: the zone must grow with the inset, which is
        // why a static 88dp constant can never be right.
        assertEquals(132.dp, floatingFootprint(48.dp))
    }
}
