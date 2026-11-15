package vade.ui.icon

import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.tabler.Tabler
import com.composables.icons.tabler.filled.ChartAreaLine
import com.composables.icons.tabler.filled.LayoutGrid
import com.composables.icons.tabler.filled.MessageCircle
import com.composables.icons.tabler.filled.User
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

    /**
     * g-nav-selected2 —— 导航目的地（描边图标）→ **Tabler 实心变体**（选中态用）。
     *
     * 用户拍板（2026-11-15，逐字）：「两者结合：小圆角块 + 实心图标 + 文字变色」。选中项在
     * 32 dp 圆角块里画**实心**变体，未选中项画原来的描边变体——所以每个目的地的两种画法
     * 必须成对登记在这里，一处、且仅一处；`NavSelection.kt` 是唯一的消费方，三个形态
     * 不许自己解析（`NavSelectionGuardTest` 在守）。
     *
     * 键是 `VadeIcons` 里的描边 vector（**按对象同一性**命中：`Destination.icon` 与
     * `VadeIcons.navIconOrNull(id)` 返回的就是这些同一对象）。实心 getter 名全部用
     * `javap` 核过 `icons-tabler-filled-cmp-jvm-2.2.1.jar`（`Message_circleKt` /
     * `Layout_gridKt` / `UserKt` / `Chart_area_lineKt`），不凭命名猜——本仓已踩过
     * `Tabler.Filled.PlayerStop`（不是 `PlayerStopFilled`）这颗雷。
     *
     * **「活动」没有上游实心变体**：Tabler 的 `activity` 是一条纯描边心电图线，filled 工件
     * （999 个类）里没有 `ActivityKt`。选最接近的 **`ChartAreaLine`**（折线 + 填充面积，
     * 保留"随时间的活动量"语义）——这是**登记在案**的替代，不是静默混用：选中项四个全部
     * 来自 filled 工件，未选中项四个全部来自 outline 工件，同屏同一选中态内两族不混
     * （`NavSelectionGuardTest` 的混用测试在盯）。
     */
    val NavFilledVariants: Map<ImageVector, ImageVector> = mapOf(
        VadeIcons.Chat to Tabler.Filled.MessageCircle,
        VadeIcons.Activity to Tabler.Filled.ChartAreaLine,
        VadeIcons.Grid to Tabler.Filled.LayoutGrid,
        VadeIcons.Person to Tabler.Filled.User,
    )

    /**
     * 描边导航图标的实心变体；没有登记的 vector 返回 `null`。
     *
     * `null` **不是**静默回退的许可：调用方（`NavSelection.kt`）对选中的未登记项会保留描边
     * 并打上 outline 家族标记——同屏混用测试立刻红。导航四目的地全部登记（全覆盖测试在守）；
     * `VadeIcons.Circle`（未知 id 的中性兜底）有意**不**登记——它只应出现在导航之外。
     */
    fun navFilledVariantOf(outline: ImageVector): ImageVector? = NavFilledVariants[outline]
}
