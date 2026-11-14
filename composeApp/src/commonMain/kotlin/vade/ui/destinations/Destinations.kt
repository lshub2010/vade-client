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
 */
enum class Destination(
    val id: String,
    val label: String,
    /** Tabler vector icon; business code renders it through `vade.ui.icon.VadeIcon`. */
    val icon: ImageVector,
    /**
     * Transitional single CJK glyph. Kept **only** so the shell call sites
     * (`NavRail` / `AppShell` / bottom bar) keep compiling until the merged icon pass
     * switches them to [icon]; the parent stick deletes this field together with those
     * call sites.
     */
    @Deprecated("Tabler 图标合入后删除")
    val glyph: String,
) {
    Sessions("sessions", "会话", VadeIcons.Chat, "话"),
    Activity("activity", "活动", VadeIcons.Activity, "活"),
    Capabilities("capabilities", "能力", VadeIcons.Grid, "能"),
    Me("me", "我的", VadeIcons.Person, "我"),
    ;

    companion object {
        fun fromId(id: String): Destination? = entries.firstOrNull { it.id == id }
    }
}
