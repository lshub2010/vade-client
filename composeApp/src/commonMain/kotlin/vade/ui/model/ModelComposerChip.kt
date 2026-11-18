// The composer model chip (g-model-chip).
//
// The session page's model-switcher TRIGGER lives in the composer's bottom row — the same
// visual line the send orb sits on — as a compact chip: accent icon + model label (one line,
// ellipsized, width-capped) + a chevron. It never adds a line of height and never pushes the
// transcript; the sheet it opens (ModelPickerSheet) is the §9 floating card.
//
// Every decision here is read from [ModelState] / ModelPickerContent — the chip has no
// policy of its own:
//  - rows available  → the label is `ModelState.currentLabel()` (what answers the NEXT turn,
//    D101) so a switch recomposes the chip immediately;
//  - loading / load-failed / no-models / read-only → their own short label, and the chip
//    stays clickable: "点了没反应" is forbidden — every state opens the sheet, which is
//    where the honest body copy lives.
//
// Guards this file must satisfy: vectors only (IconGuardTest scans vade.ui.model), corners
// from the skin scale (chip = `xs`, §10), no toolkit imports, and the send orb outside the
// panel is page layout — the chip is INSIDE `Skin.InputBar`'s `leading` slot, so the panel
// shrinks around it and the orb can never be displaced.

package vade.ui.model

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import vade.ui.UiText
import vade.ui.icon.VadeIcon
import vade.ui.icon.VadeIcons
import vade.ui.skin.LocalSkin

/**
 * The one-line label for the chip, derived from the same content decision the sheet body
 * renders — never a hardcoded 「假数据」 or a silent blank. `Rows` = what answers next turn;
 * the other four states name themselves, and the sheet explains them in full.
 */
internal fun modelChipLabel(state: ModelState): String = when (val content = state.content()) {
    is ModelPickerContent.Rows -> state.currentLabel()
    is ModelPickerContent.Loading -> content.title
    is ModelPickerContent.LoadFailed -> "加载失败"
    is ModelPickerContent.NoModels -> content.title
    is ModelPickerContent.ReadOnly -> "只读"
}

/**
 * The composer model chip. Rendered through `Skin.InputBar(secondRow = …)` — inside the
 * panel, on the second line under the input row, sharing the panel's base and corners.
 * [expanded] tints the border accent while the sheet is open (the only visual feedback the
 * chip owes; the sheet itself carries the state).
 */
@Composable
fun ModelComposerChip(
    state: ModelState,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(skin.shapes.xs)
    Row(
        modifier = modifier
            // 触摸区：chip 带文字标签（非纯图标控件），36dp 行高贴合 composer 底行；
            // 图标本身另有 contentDescription 命名整个控件（见下）。
            .heightIn(min = 36.dp)
            .widthIn(max = 148.dp)
            .clip(shape)
            .background(skin.colors.primary.copy(alpha = if (skin.colors.isDark) 0.14f else 0.08f))
            .border(1.dp, if (expanded) skin.colors.primary else skin.colors.hairline, shape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "更改会话模型" }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 彩色图标（workbuddy 参考）：accent 上的 sparkle，装饰性 —— 标签已命名控件。
        VadeIcon(
            VadeIcons.Model,
            contentDescription = null,
            size = 14.dp,
            tint = skin.colors.primary,
        )
        Spacer(Modifier.width(4.dp))
        // 模型名：单行 + 省略号 + 宽度上限 —— 超长名不上整串，chip 不许把发送球挤走。
        UiText(
            text = modelChipLabel(state),
            size = 12,
            color = skin.colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 104.dp),
        )
        Spacer(Modifier.width(3.dp))
        VadeIcon(
            VadeIcons.ChevronDown,
            contentDescription = null,
            size = 12.dp,
            tint = skin.colors.onSurfaceVariant,
        )
    }
}
