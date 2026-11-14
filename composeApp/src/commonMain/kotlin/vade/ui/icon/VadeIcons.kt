package vade.ui.icon

import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.tabler.Tabler
import com.composables.icons.tabler.filled.PlayerStop
import com.composables.icons.tabler.outline.Activity
import com.composables.icons.tabler.outline.ArrowUp
import com.composables.icons.tabler.outline.Check
import com.composables.icons.tabler.outline.ChevronDown
import com.composables.icons.tabler.outline.ChevronLeft
import com.composables.icons.tabler.outline.ChevronRight
import com.composables.icons.tabler.outline.Dots
import com.composables.icons.tabler.outline.Folder
import com.composables.icons.tabler.outline.LayoutGrid
import com.composables.icons.tabler.outline.MessageCircle
import com.composables.icons.tabler.outline.Palette
import com.composables.icons.tabler.outline.Paperclip
import com.composables.icons.tabler.outline.Pencil
import com.composables.icons.tabler.outline.Pin
import com.composables.icons.tabler.outline.Refresh
import com.composables.icons.tabler.outline.Search
import com.composables.icons.tabler.outline.Trash
import com.composables.icons.tabler.outline.User
import com.composables.icons.tabler.outline.X

/**
 * The app's icon vocabulary (w2-icons, docs/visual-language.md §3): **semantic names** over
 * Tabler Icons vectors. Business code imports `VadeIcons.Send`, never
 * `com.composables.icons.tabler.*` — that way an icon-pack swap stays a one-file change and
 * the visual-language stroke/fill rules are enforced in exactly one place.
 *
 * Naming contract (read with the icon, not the Tabler id):
 *  - [Send] / [Stop]   — the composer's send sphere: filled-arrow state vs stopped state.
 *    [Stop] is the **only filled vector** in the set (v2 §3: fill expresses selection /
 *    the streaming stop; everything else is 2dp stroke).
 *  - [Back] / [Close]  — navigation gestures (detail back, sheet/search dismiss).
 *  - [ChevronDown] / [ChevronRight] — expand/collapse affordances (tool cards, groups).
 *  - [Chat] / [Activity] / [Grid] / [Person] — the four destinations, in FR-8.77 order.
 *
 * Every name was checked against the actual `icons-tabler-*-cmp` 2.2.1 jars
 * (`javap` on the extension getters), not written from memory:
 * outline icons live on `Tabler.Outline`, the one fill icon on `Tabler.Filled`.
 */
object VadeIcons {

    /** Composer send (outline arrow-up; the sphere itself carries the accent state). */
    val Send: ImageVector = Tabler.Outline.ArrowUp

    /** Composer stop while streaming — the single filled exception (v2 §3). */
    val Stop: ImageVector = Tabler.Filled.PlayerStop

    /** Attach a file to the composer. */
    val Attach: ImageVector = Tabler.Outline.Paperclip

    /** Overflow / "more" row actions. */
    val More: ImageVector = Tabler.Outline.Dots

    /** Navigate back from a pushed detail. */
    val Back: ImageVector = Tabler.Outline.ChevronLeft

    /** Search entry (top bar / palette trigger). */
    val Search: ImageVector = Tabler.Outline.Search

    /** Dismiss (sheet, search, right panel). */
    val Close: ImageVector = Tabler.Outline.X

    /** Expand a collapsed section (tool card, group). */
    val ChevronDown: ImageVector = Tabler.Outline.ChevronDown

    /** Collapse / drill into a row. */
    val ChevronRight: ImageVector = Tabler.Outline.ChevronRight

    /** Compose a new session. */
    val Compose: ImageVector = Tabler.Outline.Pencil

    /** Confirm / approve. */
    val Check: ImageVector = Tabler.Outline.Check

    /** Destructive delete (paired with a confirm step). */
    val Trash: ImageVector = Tabler.Outline.Trash

    /** Retry / regenerate. */
    val Refresh: ImageVector = Tabler.Outline.Refresh

    /** Destination 1: 会话. */
    val Chat: ImageVector = Tabler.Outline.MessageCircle

    /** Destination 2: 活动. */
    val Activity: ImageVector = Tabler.Outline.Activity

    /** Destination 3: 能力. */
    val Grid: ImageVector = Tabler.Outline.LayoutGrid

    /** Destination 4: 我的. */
    val Person: ImageVector = Tabler.Outline.User

    /** Pin a session to the top of its group. */
    val Pin: ImageVector = Tabler.Outline.Pin

    /** Grouped / project sessions. */
    val Folder: ImageVector = Tabler.Outline.Folder

    /** Appearance / skin settings. */
    val Palette: ImageVector = Tabler.Outline.Palette
}
