package vade.ui.meta

import androidx.compose.ui.graphics.Color

/**
 * 会话/分组颜色：**有限调色盘 + 严格 `#RRGGBB`**（w-c-meta）。
 *
 * 服务端契约（`core/vade/session/manager.py` L89）：
 * `COLOR_PATTERN = ^#[0-9a-fA-F]{6}$` — 六位十六进制，可有可无的大小写。
 * 客户端**归一成大写**输出（测试②钉死），避免同一颜色以两种写法往返浪费一次 update。
 * 不做任意取色器：10 个预设（8–12 区间内），拖色板、HSL 滑杆一律不进本包。
 */
object MetaColors {

    /**
     * 预设调色盘（10 色，全部为有效 `#RRGGBB`）：从视觉语言的 accent 语义出发取的
     * 常用分类色相 —— 红/橙/黄/绿/青/蓝/靛/紫/粉 + 一档中性灰蓝。
     */
    val presets: List<String> = listOf(
        "#EF4444", // 红
        "#F97316", // 橙
        "#EAB308", // 黄
        "#22C55E", // 绿
        "#14B8A6", // 青
        "#3B82F6", // 蓝
        "#6366F1", // 靛
        "#8B5CF6", // 紫
        "#EC4899", // 粉
        "#64748B", // 中性灰蓝
    )

    /**
     * 把用户/磁盘上的任意写法归一成**大写 `#RRGGBB`**；非法输入返回 null。
     * 接受：带或不带 `#`、大小写混排；六位以外（3 位缩写、8 位 ARGB、多余空白以外的
     * 任何垃圾）一律拒绝 —— 与服务端 `^#[0-9a-fA-F]{6}$` 保持同一判定面。
     */
    fun normalize(raw: String): String? {
        val body = raw.trim().removePrefix("#")
        if (body.length != 6) return null
        val value = body.toIntOrNull(16) ?: return null
        // toIntOrNull(16) 丢掉了前导零信息，用位域重新拼回大写规范形。
        val r = (value ushr 16) and 0xFF
        val g = (value ushr 8) and 0xFF
        val b = value and 0xFF
        return "#%02X%02X%02X".format(r, g, b)
    }

    /** 与服务端 `COLOR_PATTERN.match` 同义的布尔面。 */
    fun isValid(raw: String): Boolean = normalize(raw) != null

    /** `#RRGGBB` → Compose [Color]；非法输入给中性灰（调用方不需要再判空）。 */
    fun parse(hex: String): Color {
        val normalized = normalize(hex) ?: return Color(0xFF64748B)
        val value = normalized.substring(1).toInt(16)
        return Color(0xFF000000 or value.toLong())
    }
}
