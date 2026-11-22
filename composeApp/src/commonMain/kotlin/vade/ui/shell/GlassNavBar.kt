package vade.ui.shell

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vade.ui.UiText
import vade.ui.icon.VadeIcons
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.LocalSkin
import vade.ui.skin.NavItem
import vade.ui.skin.glass.GlassSurface

/**
 * v2 bottom navigation (docs/visual-language.md §5 blur list, §12「导航/底栏」row).
 *
 * Replaces the skin's `BottomBar` in the shell: the `FloatingBlur` form there was a
 * **vertical alpha gradient** — the fake blur §5 explicitly bans ("U1-a 的底栏就是这么做
 * 的，v2 必须换掉"). This bar is real glass ([GlassSurface], haze 2.1.0, 24dp blur, §5
 * tint 26%/34%, 1px hairline, §8 float shadow) and must sit **outside** the haze backdrop
 * source (haze 2.1.0: an effect node cannot draw an ancestor source node).
 *
 * Selection (task item 2): the selected icon reads in `accent` (primary tint) plus the label
 * in accent with heavier weight. **No** pill/tinted block behind the selected item
 * (Material You tell). Height exactly 64dp; whole cell is the touch target (≥44dp).
 *
 * [BottomBarStyle] survives as the *form* switch: `Fixed` docks the bar (square edges at
 * the screen bottom), the two floating forms render the floating capsule with 12dp insets.
 * All three forms are glass now — the style picks geometry, not blur realism.
 */
@Composable
fun GlassNavBar(
    items: List<NavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    style: BottomBarStyle,
) {
    val skin = LocalSkin.current
    val floating = style != BottomBarStyle.Fixed
    val shape = if (floating) {
        RoundedCornerShape(skin.shapes.xl)
    } else {
        RoundedCornerShape(topStart = skin.shapes.xl, topEnd = skin.shapes.xl)
    }

    GlassSurface(
        modifier = Modifier.fillMaxWidth().height(64.dp),
        shape = shape,
        skin = skin,
        // 能力门禁只看真实背景 + 平台阶梯；`HazeAlwaysCompat` 已删（审查 P0-1）。
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            items.forEachIndexed { index, item ->
                val isSelected = index == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .semantics { contentDescription = item.label }
                        .clickable { onSelect(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    // v2 §3（w-g-icons）：图标槽只出矢量——NavItem 是冻结模型没有 icon 槽，
                    // 按 id 映射 VadeIcons（[VadeIcons.navIconOrNull]），未知 id 落中性
                    // Circle，绝不回退 "•"。选中态由 tint + 下方 label 的字重/颜色表达
                    // （矢量无字重可加重；无填充块，§12 禁大色块 pill）。
                    Image(
                        painter = rememberVectorPainter(VadeIcons.navIconOrNull(item.id) ?: VadeIcons.Circle),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        colorFilter = ColorFilter.tint(
                            if (isSelected) skin.colors.primary else skin.colors.onSurfaceVariant,
                        ),
                    )
                    Spacer(Modifier.height(3.dp))
                    UiText(
                        text = item.label,
                        size = 10,
                        weight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) skin.colors.primary else skin.colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
