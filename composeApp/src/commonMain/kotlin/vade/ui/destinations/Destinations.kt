package vade.ui.destinations

/**
 * The four primary destinations (D87 / FR-8.77). **Order is part of the contract**:
 * Android, Desktop and Web must show 会话 / 活动 / 能力 / 我的 in this sequence, and a
 * fifth entry is a defect — not a feature. Settings is a full-screen child page of
 * 我的 (D91), never a tab.
 */
enum class Destination(
    val id: String,
    val label: String,
    /** Single CJK glyph used as the icon until a real icon pack lands in U1-g. */
    val glyph: String,
) {
    Sessions("sessions", "会话", "话"),
    Activity("activity", "活动", "活"),
    Capabilities("capabilities", "能力", "能"),
    Me("me", "我的", "我"),
    ;

    companion object {
        fun fromId(id: String): Destination? = entries.firstOrNull { it.id == id }
    }
}
