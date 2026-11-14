package vade.ui.icon

import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.tabler.Tabler
import com.composables.icons.tabler.outline.LayoutSidebarRightCollapse
import com.composables.icons.tabler.outline.LayoutSidebarRightExpand

/**
 * Shell-only vectors that have **no** counterpart in the shared [VadeIcons] vocabulary.
 *
 * Why this file exists rather than three more `val`s in `VadeIcons.kt`: `VadeIcons.kt` is the
 * merge point of three parallel icon sticks and conflicted five times, so the shell's own
 * two icons live in their own file (same package — the Tabler namespace is still confined to
 * `vade/ui/icon`, which `IconGuardTest.businessCodeDoesNotImportTablerDirectly` enforces).
 * Delete this file the day `VadeIcons` grows a panel/rail family.
 *
 * The pair replaces the `◂` / `▸` character pair that the right-panel toggle drew
 * (`shell/AppShell.kt`, docs/visual-language.md §3: icons are vectors, never glyphs). They
 * are a **two-state** pair on purpose — the button must still read as "open the panel" vs
 * "collapse the panel", which is the only thing the two characters carried.
 */
object ShellGlyphs {

    /** Right panel **closed**: open it (the sidebar-right + outward arrow glyph). */
    val PanelExpand: ImageVector = Tabler.Outline.LayoutSidebarRightExpand

    /** Right panel **open**: collapse it (the sidebar-right + inward arrow glyph). */
    val PanelCollapse: ImageVector = Tabler.Outline.LayoutSidebarRightCollapse
}
