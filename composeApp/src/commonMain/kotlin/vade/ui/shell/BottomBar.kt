// w2-bottombar · shell-owned bottom bar (three forms).
//
// The C-09 `Skin.BottomBar` stays frozen in `skin/`, so the three D87/FR-8.20
// forms are rendered HERE instead of being delegated to a skin: the task's
// Fixed form needs a real backdrop blur (`layerBackdrop` capture + blur behind
// a transparent bar), and the floating forms are the KernelSU liquid pill —
// the old "vertical alpha gradient" stand-ins are no longer reachable from the
// app shell. The bar is a container, like NavRail: assembled from foundation
// primitives + public skin tokens. Structure follows KernelSU's
// `BottomBarMiuix` (Copyright (c) 2023-2026 weishu & contributors, Apache
// License 2.0 — see README.md).

package vade.ui.shell

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import vade.ui.UiText
import vade.ui.component.liquid.GlassBackdrop
import vade.ui.component.liquid.glassSurface
import vade.ui.icon.VadeIcons
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.LocalSkin

/**
 * One bottom-bar entry. This is the shell-side item model: the frozen C-09
 * `NavItem` cannot grow an icon slot, so the bar takes the icon here —
 * `icon != null` renders the vector (the Tabler pack lands here), `icon == null`
 * falls back to the CJK glyph. When the icon pack merges, only the builder in
 * `AppShell` changes.
 */
data class BottomBarItem(
    val id: String,
    val label: String,
    /**
     * Vector icon (2 dp stroke, 24×24 viewport expected); the renderers fall back to the
     * neutral `VadeIcons.Circle` vector when null — never to a character glyph (w-g-icons).
     */
    val icon: ImageVector? = null,
    /** Legacy single-glyph fallback; transitional, only `FloatingBottomBar` still reads it. */
    val glyph: String? = null,
    val badge: String? = null,
)

/** Pill row height: 56 dp content + 2×4 dp padding (KernelSU geometry). */
internal val FloatingBarPillHeight: Dp = 64.dp

/**
 * Navigation-bar bottom inset: the real gesture/3-button inset on Android (non-zero
 * under edge-to-edge), 0 on desktop and wasm — CMP's `WindowInsets` actuals report no
 * system bars there, so bar geometry is unchanged on those targets (c-inset).
 */
@Composable
internal fun navigationBarBottomInset(): Dp =
    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/** `8 dp + inset` when the device has a nav inset, else KernelSU's 28 dp. */
@Composable
internal fun floatingBarBottomMargin(): Dp = floatingBarBottomMargin(navigationBarBottomInset())

/** Pure form of [floatingBarBottomMargin]; the Dp branch is pinned by `InsetsGuardTest`. */
internal fun floatingBarBottomMargin(navInset: Dp): Dp =
    if (navInset > 0.dp) 8.dp + navInset else 28.dp

/**
 * The three bottom-bar forms. [backdrop] is the app-content capture (attached
 * in `AppShell`); the fixed bar blurs it, the floating pill refracts it.
 */
@Composable
internal fun VadeBottomBar(
    items: List<BottomBarItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    style: BottomBarStyle,
    backdrop: GlassBackdrop,
    modifier: Modifier = Modifier,
) {
    when (style) {
        BottomBarStyle.Fixed -> FixedBottomBar(items, selected, onSelect, backdrop, modifier)
        BottomBarStyle.Floating -> FloatingBottomBar(
            items = items,
            selectedIndex = selected,
            onSelected = onSelect,
            backdrop = backdrop,
            isBlurEnabled = false,
            dark = LocalSkin.current.colors.isDark,
            modifier = modifier
                .pointerInput(Unit) {
                    // KernelSU: swallow taps on the margins so content under the
                    // padding never receives them.
                    detectTapGestures { }
                }
                .padding(start = 28.dp, end = 28.dp, bottom = floatingBarBottomMargin()),
        )

        BottomBarStyle.FloatingBlur -> FloatingBottomBar(
            items = items,
            selectedIndex = selected,
            onSelected = onSelect,
            backdrop = backdrop,
            isBlurEnabled = true,
            dark = LocalSkin.current.colors.isDark,
            modifier = modifier
                .pointerInput(Unit) {
                    detectTapGestures { }
                }
                .padding(start = 28.dp, end = 28.dp, bottom = floatingBarBottomMargin()),
        )
    }
}

/**
 * `Fixed` form: a transparent navigation-bar row over a **real** backdrop blur
 * (24 dp glass radius from the skin's glass tokens) — the KernelSU
 * `BlurredBar(NavigationBar(color = Transparent))` shape. No vertical alpha
 * ramp anywhere: if the blur cannot run (no runtime shader), the surface
 * degrades to the plain tint, never to a fake gradient.
 *
 * c-inset: the glass surface extends through the navigation-bar inset and the
 * label row is padded above it, so the labels sit clear of the system gesture
 * pill / 3-button bar while the tint keeps covering the system area (KSU's
 * BlurredBar semantics). [navigationBarBottomInset] is 0 on desktop/wasm, so
 * the geometry there stays exactly 1 dp hairline + 64 dp glass.
 */
@Composable
private fun FixedBottomBar(
    items: List<BottomBarItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    backdrop: GlassBackdrop,
    modifier: Modifier = Modifier,
) {
    val skin = LocalSkin.current
    val tint = if (skin.colors.isDark) skin.glass.tintDark else skin.glass.tintLight
    val navInset = navigationBarBottomInset()
    Column(modifier = modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(skin.colors.hairline))
        Box(
            Modifier
                .fillMaxWidth()
                .height(FloatingBarPillHeight + navInset)
                .glassSurface(
                    backdrop = backdrop.asSurfaceBackdrop(),
                    shape = { RectangleShape },
                    blurRadiusDp = skin.glass.blurRadiusDp,
                    onDrawSurface = { drawRect(tint) },
                ),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(bottom = navInset),
            ) {
                items.forEachIndexed { index, item ->
                    FixedBottomBarItem(
                        item = item,
                        selected = index == selected,
                        onClick = { onSelect(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.FixedBottomBarItem(
    item: BottomBarItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val skin = LocalSkin.current
    val tint = if (selected) skin.colors.primary else skin.colors.onSurfaceVariant
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            // g-nav-fix ①: the *third* nav form was the one without two-state semantics.
            // `NavRail` and `FloatingBottomBarItem` both publish `selected` + `Role.Tab`;
            // this item only had `.clickable`, so on the default mobile form (`App(initialBar
            // = BottomBarStyle.Fixed)`) "which tab am I on" existed as colour + font weight
            // alone — inaudible to a screen reader and unassertable in a test (device dump:
            // 0 nodes with `selected=true`, against 1 for rail and 1 for the pill).
            //
            // Same shape as the other two on purpose — one semantics model, three renderers.
            // `selected` is written on BOTH states (false, never absent): "tab, not selected"
            // and "no selection information at all" are different announcements.
            .semantics(mergeDescendants = true) {
                this.selected = selected
                role = Role.Tab
            }
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            // w-g-icons：图标槽只出矢量——icon 缺省落中性 Circle，绝不回退 "•" 字符（v2 §3）。
            Image(
                painter = rememberVectorPainter(item.icon ?: VadeIcons.Circle),
                contentDescription = item.label,
                modifier = Modifier.size(20.dp),
                colorFilter = ColorFilter.tint(tint),
            )
            if (item.badge != null) {
                Box(
                    Modifier
                        .size(13.dp)
                        .background(skin.colors.error, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { UiText(item.badge, size = 8, color = skin.colors.onError) }
            }
        }
        UiText(
            text = item.label,
            size = 11,
            weight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = tint,
        )
    }
}
