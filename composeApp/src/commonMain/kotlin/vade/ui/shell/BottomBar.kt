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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.LocalSkin
import vade.ui.skin.glass.glassPaint

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
 * The three bottom-bar forms. 模糊走与顶栏/输入框**同一条** haze 管线（`glassPaint`）——
 * 这里以前是 miuix-blur 的第二条管线（整窗 `captureGlassBackdrop` + `drawBackdrop`），
 * 它的捕获节点每帧把整个内容区重录一次（`LayerBackdropNode.draw` 无条件 `DrawScope.record`），
 * 是"滑动掉帧"最像的一块；而且它一次都没查过 [GlassCapability]（审查 P1-2）。
 *
 * @param barZoneBlurred 这一页的**栏区已经被另一层玻璃糊过**（会话页的底栏带：带子最强端
 *   就在屏幕底，正好盖住整条栏区）。为 `true` 时底栏**不建自己的 haze 节点**，只画染色 +
 *   hairline —— §5「同屏模糊层数 ≤3」的落地方式：同一块背景只买一次采样。
 */
@Composable
internal fun VadeBottomBar(
    items: List<BottomBarItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    style: BottomBarStyle,
    modifier: Modifier = Modifier,
    barZoneBlurred: Boolean = false,
) {
    when (style) {
        BottomBarStyle.Fixed -> FixedBottomBar(
            items = items,
            selected = selected,
            onSelect = onSelect,
            modifier = modifier,
            barZoneBlurred = barZoneBlurred,
        )
        BottomBarStyle.Floating -> FloatingBottomBar(
            items = items,
            selectedIndex = selected,
            onSelected = onSelect,
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
            isBlurEnabled = true,
            dark = LocalSkin.current.colors.isDark,
            // 会话页：底栏带已经把这整条栏区糊掉了 ⇒ 胶囊只补染色，不再采一次背景。
            samplesBackdrop = !barZoneBlurred,
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
    modifier: Modifier = Modifier,
    barZoneBlurred: Boolean = false,
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
                // 与顶栏 / composer 同一条门禁（能力 + 有无背景），染色取 §5 的 26 %/34 %。
                // `barZoneBlurred` ⇒ 不建节点：会话页的底栏带已经把这条栏区糊过了。
                .glassPaint(
                    skin = skin,
                    shape = RectangleShape,
                    tint = tint,
                    blurBackdrop = !barZoneBlurred,
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
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        // g-nav-selected: the block + tint + label colour all come from `NavSelection.kt`.
        // This form used to have *no* block at all while the other two had one.
        val badge = item.badge
        NavSelectionIconSlot(
            icon = bottomBarIcon(item),
            contentDescription = item.label,
            selected = selected,
            badge = if (badge == null) null else { { NavBadge(badge) } },
        )
        NavTabLabel(text = item.label, selected = selected, size = 11, maxLines = 1)
    }
}
