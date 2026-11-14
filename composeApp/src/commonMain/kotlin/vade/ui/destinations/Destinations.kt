package vade.ui.destinations

import androidx.compose.ui.graphics.vector.ImageVector
import vade.ui.icon.VadeIcons

/**
 * The four primary destinations (D87 / FR-8.77). **Order is part of the contract**:
 * Android, Desktop and Web must show 会话 / 活动 / 能力 / 我的 in this sequence, and a
 * fifth entry is a defect — not a feature. Settings is a full-screen child page of
 * 我的 (D91), never a tab.
 *
 * w2-icons: the icon is a Tabler vector from [VadeIcons] (docs/visual-language.md §3 —
 * vectors only, no CJK glyph acting as an icon).
 *
 * g-glyph-clear: the transitional single-CJK `glyph` field (`话` / `活` / `能` / `我`) is
 * **gone**. `shell/NavRail` was its last reader and now draws [icon]; [label] keeps carrying
 * the words, so nothing readable is lost — only the character-as-icon route is. Re-adding a
 * glyph field here is a regression the icon guard will not forgive.
 */
enum class Destination(
    val id: String,
    val label: String,
    /** Tabler vector icon; business code renders it through `vade.ui.icon.VadeIcon`. */
    val icon: ImageVector,
) {
    Sessions("sessions", "会话", VadeIcons.Chat),
    Activity("activity", "活动", VadeIcons.Activity),
    Capabilities("capabilities", "能力", VadeIcons.Grid),
    Me("me", "我的", VadeIcons.Person),
    ;

    companion object {
        fun fromId(id: String): Destination? = entries.firstOrNull { it.id == id }
    }
}
