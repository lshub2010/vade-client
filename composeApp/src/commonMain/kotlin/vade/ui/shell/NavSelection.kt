// g-nav-selected · the **one** selected-state renderer for all three navigation forms.
//
// Why this file exists: `NavRail`, `FixedBottomBar` and `FloatingBottomBar` used to each
// hand-write their own selected state, and the three had drifted apart —
//   · rail      : a 30 dp / 16 dp-radius 16 % accent block behind the icon, label tinted;
//   · fixed     : colour + font weight only, **no block at all**;
//   · floating  : a full-tab-width lavender capsule (≈56 dp tall, ≈2/3 of the pill height).
// Every nav-wide property added since (two-state semantics, vector icons) had to be
// re-patched into each copy, and one of them was always missed — the fixed bar shipped
// without `selected`/`Role.Tab` for exactly this reason while 51 tests stayed green,
// because a source guard only opens the files that already have an offender.
//
// So the geometry, the fill, the icon tint and the label colour now live here and
// nowhere else. **Adding a nav-wide property means editing this file**; the three forms
// only decide *where* the item sits (rail / docked row / pill row), never how it is
// selected. `NavSelectionGuardTest` fails the build if a form goes back to hand-rolling
// its own selected state.
//
// Spec (user decisions, verbatim):
//   2026-11-15 (g-nav-selected):    「统一成「图标外包小圆角块（≈32dp，16% accent）
//                + 文字变色」，三形态一致（近 miuix/KSU，且不是大色块）」
//   2026-11-15 (g-nav-selected2):   「两者结合：小圆角块 + 实心图标 + 文字变色」
//   selected   : ≈32 dp rounded block behind the icon, accent @ 16 %; the icon switches to
//                its **Tabler filled variant** (`ShellGlyphs.NavFilledVariants` — the one
//                mapping, keyed by the outline vector); icon + label accent;
//   unselected : no block (`Color.Transparent`, never a surface fill tied to the bar's own
//                background), the **outline** vector + icon/label onSurfaceVariant;
//   corner     : the skin's shape token — `shapes.sm` (16 dp in v2), never a literal, so a
//                skin that widens its shape scale widens the block with it.
//
// Variant discipline (no silent mixing): the family switch happens HERE and nowhere else.
// A selected item whose outline vector has no registered filled variant keeps the outline
// shape and is tagged as such — the guard test reads the tags and fails on any screen where
// an icon's family disagrees with its tab's selection state. The four real destinations are
// all registered, so the fallback is dead code for them (a totality test pins that).

package vade.ui.shell

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import vade.ui.UiText
import vade.ui.icon.ShellGlyphs
import vade.ui.skin.LocalSkin
import vade.ui.skin.SkinShapes

/** Test tag on the selection block — one node per tab, in every form. */
internal const val NavSelectionBlockTag: String = "vade.nav.selection.block"

/**
 * Family markers on the icon (g-nav-selected2), each on its own node *inside* the block —
 * the `Image` itself may already carry the caller's tag (rail's `vade.nav.rail.icon.<id>`)
 * and one node holds exactly one `TestTag`.
 *
 * The tag is derived from the **resolved vector** (`navSelectionVector`), not from the
 * `selected` flag: "filled" means a mapped `Tabler.Filled` vector was actually substituted.
 * `NavSelectionGuardTest` asserts that every icon's family agrees with its tab's state —
 * a selected tab without a registered variant shows up here as an outline-tagged selected
 * icon, which is exactly the silent mixing this layer must not do.
 */
internal const val NavSelectionFilledIconTag: String = "vade.nav.icon.filled"
internal const val NavSelectionOutlineIconTag: String = "vade.nav.icon.outline"

/** State markers on the label — same chain: tag from `selected`, colour from `navTabColor`. */
internal const val NavSelectionLabelSelectedTag: String = "vade.nav.label.selected"
internal const val NavSelectionLabelIdleTag: String = "vade.nav.label.idle"

/** Side of the block behind the icon. 32 dp in all three forms (≈ miuix/KSU, not a colour slab). */
internal val NavSelectionBlockSize: Dp = 32.dp

/** The vector inside the block. Unchanged from the pre-convergence rail (20 dp). */
internal val NavSelectionIconSize: Dp = 20.dp

/** Accent wash of the block: **16 %** — enough to read as "here", not a filled chip. */
internal const val NavSelectionAccentAlpha: Float = 0.16f

/** Unread badge diameter, shared by all three forms. */
internal val NavBadgeSize: Dp = 13.dp

/**
 * Corner radius of the block, from the skin's shape scale.
 *
 * `shapes.sm` is the v2 16 dp step (the token the rail's 30 dp block already used). Kept as
 * a pure function of [SkinShapes] so "all three forms use the same token" is assertable
 * without rendering — a literal would silently survive a skin change.
 */
internal fun navSelectionCorner(shapes: SkinShapes): Dp = shapes.sm

/**
 * Block fill. Selected = accent at [NavSelectionAccentAlpha]; unselected = **explicitly**
 * transparent, never `surfaceVariant`/`surface` — writing the container's own colour down a
 * second time turns every idle slot into a solid chip the moment the rail sits on glass.
 */
internal fun navSelectionFill(selected: Boolean, accent: Color): Color =
    if (selected) accent.copy(alpha = NavSelectionAccentAlpha) else Color.Transparent

/**
 * The one label/icon colour rule for all three nav forms: accent when selected,
 * `onSurfaceVariant` when idle.
 */
@Composable
internal fun navTabColor(selected: Boolean): Color {
    val colors = LocalSkin.current.colors
    return if (selected) colors.primary else colors.onSurfaceVariant
}

/** The one weight rule: SemiBold when selected, Normal when idle. */
internal fun navTabWeight(selected: Boolean): FontWeight =
    if (selected) FontWeight.SemiBold else FontWeight.Normal

/**
 * The vector one nav item actually draws (g-nav-selected2): the outline vector as given when
 * idle; its **registered Tabler filled variant** when selected. Unregistered vectors keep the
 * outline shape — visible through [navSelectionIconVariantTag], never silently mixed.
 */
internal fun navSelectionVector(icon: ImageVector, selected: Boolean): ImageVector =
    if (selected) ShellGlyphs.navFilledVariantOf(icon) ?: icon else icon

/**
 * Which family [navSelectionVector] resolved to, as a test tag. Derived from the *resolved
 * vector* (a mapped substitution happened), not from the `selected` flag — so a selected tab
 * with no registered variant is tagged outline and the guard's mixing test fails on it.
 */
internal fun navSelectionIconVariantTag(icon: ImageVector, selected: Boolean): String =
    if (navSelectionVector(icon, selected) !== icon) {
        NavSelectionFilledIconTag
    } else {
        NavSelectionOutlineIconTag
    }

/**
 * Icon slot of one navigation item: the shared 32 dp rounded block + the tinted vector.
 *
 * g-nav-selected2: the vector is resolved through [navSelectionVector] — outline when idle,
 * the mapped Tabler filled variant when selected — and the family tag rides its own node
 * because [iconModifier] may already carry the caller's tag (rail's `vade.nav.rail.icon.<id>`)
 * and one node holds exactly one `TestTag`.
 *
 * [iconModifier] carries per-form testing hooks (rail's `vade.nav.rail.icon.<id>` tag);
 * [contentDescription] is the caller's call — the rail labels the item below the icon, so
 * it passes `null` to avoid announcing the name twice. [badge] is the unread pill, pinned to
 * the block's top-end corner so it sits identically in all three forms.
 */
@Composable
internal fun NavSelectionIconSlot(
    icon: ImageVector,
    contentDescription: String?,
    selected: Boolean,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
    badge: (@Composable () -> Unit)? = null,
) {
    val skin = LocalSkin.current
    val tint = navTabColor(selected)
    val vector = navSelectionVector(icon, selected)
    Box(
        modifier = modifier
            .size(NavSelectionBlockSize)
            .clip(RoundedCornerShape(navSelectionCorner(skin.shapes)))
            .background(navSelectionFill(selected, skin.colors.primary))
            .testTag(NavSelectionBlockTag),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.testTag(navSelectionIconVariantTag(icon, selected)),
        ) {
            Image(
                painter = rememberVectorPainter(vector),
                contentDescription = contentDescription,
                modifier = iconModifier.size(NavSelectionIconSize),
                colorFilter = ColorFilter.tint(tint),
            )
        }
        badge?.let { b ->
            Box(Modifier.align(Alignment.TopEnd)) { b() }
        }
    }
}

/**
 * Label of one navigation item. Size stays per-form (rail 10 sp, bars 11 sp — it is
 * geometry, not selection state); colour and weight are the shared rules above. g-nav-selected2:
 * the label also carries a state tag (`vade.nav.label.selected` / `.idle`) so the guard can
 * pin "the accent-coloured one is exactly the selected tab's label" per form without reading
 * text styles (which semantics don't expose).
 */
@Composable
internal fun NavTabLabel(
    text: String,
    selected: Boolean,
    size: Int,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    UiText(
        text = text,
        modifier = modifier.testTag(
            if (selected) NavSelectionLabelSelectedTag else NavSelectionLabelIdleTag,
        ),
        size = size,
        weight = navTabWeight(selected),
        color = navTabColor(selected),
        maxLines = maxLines,
    )
}

/** Unread badge: error fill + onError digit. Was three slightly different copies. */
@Composable
internal fun NavBadge(text: String) {
    val colors = LocalSkin.current.colors
    Box(
        Modifier
            .size(NavBadgeSize)
            .background(colors.error, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        UiText(text, size = 8, color = colors.onError)
    }
}
